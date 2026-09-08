package hrf.gg

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

import slick.jdbc.HsqldbProfile.api._

import hrf.gg.GoodGame._

/** notify-turn and notify-wait, lifted out of GoodGame.main so they can be
  * route-tested and so the "already told them?" decision they used to each
  * re-implement now lives once, in TurnNotifier. See NOTIFICATION_DEDUP_PLAN.md.
  *
  * `sendEmail` is injected purely so tests can capture outgoing mail; in
  * production main passes an adapter onto EmailSender.sendTurnEmail.
  */
class NotifyRoutes(
    db : Database,
    baseUrl : String,
    internalKey : String,
    sendEmail : TurnNotifier.Outgoing => Unit,
)(implicit system : ActorSystem) {

    private implicit val ec : scala.concurrent.ExecutionContext = system.dispatcher

    private val notifier = new TurnNotifier(db, baseUrl, sendEmail)

    private def exec[R, E <: Effect](action : DBIOAction[R, NoStream, E]) : R =
        Await.result(db.run(action.withPinnedSession), Duration.Inf)

    private implicit class Ascii(val s : String) {
        def ascii = s.filter(c => c >= 32 && c < 128)
        def asciiplus = s.filter(c => (c >= 32 && c < 128) || (c > 158 && c < 256 && c.isLetter))
    }

    // Mirror of GoodGame.main's hasRight: an empty .result.head throws
    // NoSuchElementException, which main's authExceptionHandler turns into 403.
    private def hasRight[R, E <: Effect with Effect.Read](userId : String, userSecret : String, journalId : String, right : String)(then : => DBIOAction[R, NoStream, E]) : DBIOAction[R, NoStream, E] =
        users.filter(_.id === userId).filter(_.secret === userSecret).result.head.flatMap { _ =>
            accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === right).result.head.flatMap { _ =>
                then
            }
        }

    private def parseLog(lines : List[String], cap : Int) : List[(Int, String)] =
        lines.filter(_.startsWith("LOG ")).flatMap { l =>
            val rest = l.drop(4)
            val tab = rest.indexOf('\t')
            if (tab > 0)
                scala.util.Try(rest.take(tab).toInt).toOption.map(idx => idx -> rest.drop(tab + 1).take(cap).asciiplus)
            else None
        }

    val route : Route =
        (post & path("notify-turn" / Segment / Segment / Segment / Segment / IntNumber)) { case (userId, userSecret, journalId, lobbyId, index) =>
            decodeRequest {
                entity(as[String]) { body =>
                    val lines = body.split('\n').toList

                    val metaName = lines.find(_.startsWith("META ")).map(_.drop(5).take(32).ascii).getOrElse("")

                    val targetFactions = lines.filter(_.startsWith("TARGET ")).flatMap { l =>
                        val rest = l.drop(7)
                        val sp = rest.indexOf(' ')
                        if (sp > 0) Some(rest.take(sp).take(32).ascii -> rest.drop(sp + 1).take(32).ascii) else None
                    }.filter(_._1.nonEmpty).distinct

                    val logEntries = parseLog(lines, 200)

                    // any client with the journal open can independently detect a wait transition
                    // and call this, so require only read access, not append
                    val journal = exec(hasRight(userId, userSecret, journalId, "read") {
                        journals.filter(_.id === journalId).result.head
                    })

                    targetFactions.foreach { case (targetUserId, factionNm) =>
                        // the vendored client never sends prompt text -> promptOpt = None (index-only)
                        notifier.dispatch(journalId, targetUserId, lobbyId, index, None, logEntries,
                            TurnNotifier.Info(factionNm, factionNm.take(1), journal.name, metaName, None))
                    }

                    complete(StatusCodes.Accepted)
                }
            }
        } ~
        (post & path("internal" / "notify-wait" / Segment / Segment / Segment)) { case (key, gameJournalId, letter) =>
            if (internalKey.isEmpty || key != internalKey)
                complete(StatusCodes.Forbidden, "")
            else decodeRequest {
                entity(as[String]) { body =>
                    val bodyLines = body.split('\n').toList
                    val maxIndex = bodyLines.find(_.startsWith("INDEX ")).map(_.drop(6).trim.toInt).getOrElse(0)
                    // watch.js now reports the client's live "whose turn" prompt text so a
                    // multi-step turn (new distinct prompt, same log index) re-notifies.
                    val promptText = bodyLines.find(_.startsWith("PROMPT ")).map(_.drop(7).take(2000).asciiplus).getOrElse("")
                    val logEntries = parseLog(bodyLines, 50000)

                    val lobbyIds = exec(plays.map(_.journalId).result).distinct
                    val found = lobbyIds.flatMap { lobbyId =>
                        val entryLines = exec(entries.filter(_.journalId === lobbyId).sortBy(_.index).map(_.text).result).toList
                        val info = parseLobby(entryLines)
                        if (info.gameJournalId == gameJournalId) Some((lobbyId, info)) else None
                    }.headOption

                    found.foreach { case (lobbyId, info) =>
                        info.letterToUserId.get(letter).foreach { targetUserId =>
                            notifier.dispatch(gameJournalId, targetUserId, lobbyId, maxIndex, Some(promptText), logEntries,
                                TurnNotifier.Info(factionName(letter), letter, info.title, info.meta, info.letterToName.get(letter)))
                        }
                    }

                    complete(StatusCodes.Accepted)
                }
            }
        }
}
