package hrf.gg

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import slick.jdbc.HsqldbProfile.api._

import hrf.gg.GoodGame._

/** The one shared "tell this player it's their turn (unless we already have)"
  * path, replacing the near-duplicate inline blocks in notify-turn and
  * notify-wait (see NOTIFICATION_DEDUP_PLAN.md). The dedup question is
  * delegated wholesale to NotifyDecision.shouldNotify.
  *
  * `sendEmail` is injected so tests can capture outgoing mail instead of
  * hitting Resend; `log` is injected so tests can assert the skip-reason
  * lines. Everything else goes through the real Slick tables against whatever
  * `db` is passed (a file DB in prod, an in-memory one in tests).
  */
object TurnNotifier {
    /** Per-notification specifics the two routes compute differently
      * (faction, title, play link, display name) but the helper just needs.
      */
    case class Info(
        factionName : String,
        factionLetter : String,
        gameTitle : String,
        meta : String,
        preferredName : Option[String], // from the lobby journal; falls back to Users.name
    )

    /** Exactly the arguments EmailSender.sendTurnEmail would be called with. */
    case class Outgoing(
        to : String,
        playerName : String,
        factionName : String,
        factionLetter : String,
        gameTitle : String,
        link : String,
        recentLog : List[String],
    )
}

class TurnNotifier(
    db : Database,
    baseUrl : String,
    sendEmail : TurnNotifier.Outgoing => Unit,
    log : String => Unit = Predef.println,
) {
    import TurnNotifier._

    private def exec[R, E <: Effect](action : DBIOAction[R, NoStream, E]) : R =
        Await.result(db.run(action), Duration.Inf)

    def dispatch(
        journalId : String,
        userId : String,
        lobbyId : String,
        index : Int,
        promptOpt : Option[String],
        logEntries : List[(Int, String)],
        info : Info,
    ) : Unit = {
        val existing = exec(
            notifiedTurns.filter(n => n.journalId === journalId && n.userId === userId).result.headOption
        )
        val last = existing.map(n => NotifyDecision.LastNotified(n.index, n.lastPrompt))

        if (!NotifyDecision.shouldNotify(last, index, promptOpt)) {
            log("Skipping turn email for " + userId + " (" + journalId + "): already notified at index " + last.map(_.index).getOrElse(index))
            return
        }

        val targetUser = exec(users.filter(_.id === userId).result.headOption)
        // Plays rows are keyed by the lobby journal, not the per-chapter game journal.
        val secret = exec(plays.filter(p => p.journalId === lobbyId && p.userId === userId).map(_.secret).result.headOption)

        // Record that we've handled this (index, prompt) so a still-waiting
        // repeated poll goes quiet - but only when there's a real user row to
        // hang it off (NotifiedTurns FKs to Users). A missing user is the one
        // exceptional case that keeps re-logging.
        def markNotified() : Unit = {
            val newPrompt = promptOpt.orElse(last.map(_.prompt)).getOrElse("")
            exec(
                if (existing.isDefined)
                    notifiedTurns
                        .filter(n => n.journalId === journalId && n.userId === userId)
                        .map(n => (n.index, n.lastPrompt))
                        .update((index, newPrompt))
                else
                    notifiedTurns += NotifiedTurn(journalId, userId, index, newPrompt)
            )
        }

        (targetUser, secret) match {
            case (Some(u), Some(s)) if u.email.exists(_.nonEmpty) =>
                markNotified()
                // A fresh turn email restarts the once-per-24h reminder clock:
                // clear any RemindedTurns row so notify-reminder re-seeds it at
                // "now" on its next poll. Without this, a turn that lands >24h
                // after the player's previous turn fires "your turn" and
                // "still your turn" simultaneously.
                exec(remindedTurns.filter(n => n.journalId === journalId && n.userId === userId).delete)
                val since = last.map(_.index).getOrElse(0)
                val recentLog = logEntries.filter(_._1 > since).sortBy(_._1).map(_._2).takeRight(30)
                val playerName = info.preferredName.getOrElse(u.name)
                sendEmail(Outgoing(
                    u.email.get,
                    playerName,
                    info.factionName,
                    info.factionLetter,
                    info.gameTitle,
                    baseUrl + "/play/" + info.meta + "/" + s,
                    recentLog,
                ))
            case (Some(_), Some(_)) =>
                markNotified()
                log("Skipping turn email for " + userId + " (" + journalId + "): no email address registered")
            case (Some(_), None) =>
                markNotified()
                log("Skipping turn email for " + userId + " (" + journalId + "): no play/secret found on lobby " + lobbyId)
            case (None, _) =>
                log("Skipping turn email for " + userId + " (" + journalId + "): user record not found")
        }
    }
}
