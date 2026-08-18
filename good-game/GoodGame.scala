package hrf.gg

import slick.jdbc.HsqldbProfile.api._
import slick.jdbc.HsqldbProfile.api.DBIO.seq

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer

import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

object GoodGame {
    case class User(name : String, secret : String, id : String, email : Option[String] = None)

    class Users(tag : Tag) extends Table[User](tag, "Users") {
        def name = column[String]("name")
        def secret = column[String]("secret")
        def id = column[String]("id", O.PrimaryKey)
        def email = column[Option[String]]("email")
        def * = (name, secret, id, email).mapTo[User]
    }

    val users = TableQuery[Users]


    case class Journal(name : String, public : Boolean, status : String, message : String, id : String)

    class Journals(tag : Tag) extends Table[Journal](tag, "Journals") {
        def name = column[String]("name")
        def public = column[Boolean]("public")
        def status = column[String]("status")
        def message = column[String]("message")
        def id = column[String]("id", O.PrimaryKey)
        def * = (name, public, status, message, id).mapTo[Journal]
    }

    val journals = TableQuery[Journals]


    case class Entry(journalId : String, index : Int, userId : String, text : String)

    class Entries(tag : Tag) extends Table[Entry](tag, "Entries") {
        def journalId = column[String]("journalId")
        def index = column[Int]("index")
        def userId = column[String]("userId")
        def text = column[String]("text")
        def * = (journalId, index, userId, text).mapTo[Entry]
        def pk = primaryKey("Entries" + "Key", (journalId, index))
        def journal = foreignKey("Entries" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("Entries" + "Users", userId, users)(_.id)
    }

    val entries = TableQuery[Entries]


    case class AccessRight(journalId : String, userId : String, right : String)

    class AccessRights(tag : Tag) extends Table[AccessRight](tag, "AccessRights") {
        def journalId = column[String]("journalId")
        def userId = column[String]("userId")
        def right = column[String]("right")
        def * = (journalId, userId, right).mapTo[AccessRight]
        def pk = primaryKey("AccessRights" + "Key", (journalId, userId, right))
        def journal = foreignKey("AccessRights" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("AccessRights" + "Users", userId, users)(_.id)
    }

    val accessRights = TableQuery[AccessRights]


    case class Play(journalId : String, userId : String, secret : String)

    class Plays(tag : Tag) extends Table[Play](tag, "Plays") {
        def journalId = column[String]("journalId")
        def userId = column[String]("userId")
        def secret = column[String]("secret")
        def * = (journalId, userId, secret).mapTo[Play]
        def journal = foreignKey("Play" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("Play" + "Users", userId, users)(_.id)
    }

    val plays = TableQuery[Plays]


    case class NotifiedTurn(journalId : String, userId : String, index : Int)

    class NotifiedTurns(tag : Tag) extends Table[NotifiedTurn](tag, "NotifiedTurns") {
        def journalId = column[String]("journalId")
        def userId = column[String]("userId")
        def index = column[Int]("index")
        def * = (journalId, userId, index).mapTo[NotifiedTurn]
        def pk = primaryKey("NotifiedTurns" + "Key", (journalId, userId))
        def journal = foreignKey("NotifiedTurns" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("NotifiedTurns" + "Users", userId, users)(_.id)
    }

    val notifiedTurns = TableQuery[NotifiedTurns]


    object EmailSender {
        def htmlEscape(s : String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        def sendTurnEmail(to : String, playerName : String, factionName : String, gameTitle : String, link : String, recentLog : List[String])(implicit system : ActorSystem) {
            import system.dispatcher

            val apiKey = sys.env.getOrElse("RESEND_API_KEY", "")

            if (apiKey.isEmpty) {
                println("RESEND_API_KEY not set, skipping turn email to " + to)
                return
            }

            val from = sys.env.getOrElse("RESEND_FROM", "onboarding@resend.dev")

            def jsonEscape(s : String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

            val greeting = if (playerName.nonEmpty) playerName else "there"
            val faction = if (factionName.nonEmpty) factionName else "your faction"
            val title = if (gameTitle.nonEmpty) gameTitle else "Arcs"

            val subject = faction + ": your turn — " + title

            val logHtml =
                if (recentLog.nonEmpty)
                    "<p><b>Since your last turn:</b></p><ul style=\"padding-left:20px;margin:8px 0;\">" +
                    recentLog.map(l => "<li style=\"margin:2px 0;\">" + htmlEscape(l) + "</li>").mkString("") +
                    "</ul>"
                else
                    ""

            val html =
                "<p>Hi " + htmlEscape(greeting) + ",</p>" +
                "<p>It's your turn as <b>" + htmlEscape(faction) + "</b> in <b>" + htmlEscape(title) + "</b>.</p>" +
                logHtml +
                "<p><a href=\"" + link + "\">Take your turn &rarr;</a></p>"

            val json = "{" +
                "\"from\":\"" + jsonEscape(from) + "\"," +
                "\"to\":\"" + jsonEscape(to) + "\"," +
                "\"subject\":\"" + jsonEscape(subject) + "\"," +
                "\"html\":\"" + jsonEscape(html) + "\"" +
            "}"

            val request = HttpRequest(
                method = HttpMethods.POST,
                uri = "https://api.resend.com/emails",
                headers = List(Authorization(OAuth2BearerToken(apiKey))),
                entity = HttpEntity(ContentTypes.`application/json`, json)
            )

            Http().singleRequest(request).onComplete {
                case scala.util.Success(response) =>
                    if (!response.status.isSuccess())
                        println("Resend API error sending to " + to + ": " + response.status)
                    response.discardEntityBytes()
                case scala.util.Failure(e) =>
                    println("Failed to send turn email to " + to + ": " + e.getMessage)
            }
        }
    }


    case class LobbyInfo(meta : String, title : String, gameJournalId : String, letterToUserId : Map[String, String])

    def parseLobby(lines : List[String]) : LobbyInfo = {
        def field(prefix : String) = lines.find(_.startsWith(prefix)).map(_.drop(prefix.length).trim).getOrElse("")

        val letterToUserId = lines.filter(_.startsWith("user ")).flatMap { l =>
            val rest = l.drop(5)
            val sp = rest.indexOf(' ')
            if (sp > 0) Some(rest.take(sp).trim -> rest.drop(sp + 1).trim) else None
        }.toMap

        LobbyInfo(field("meta "), field("title "), field("server "), letterToUserId)
    }

    def factionName(letter : String) = letter match {
        case "Y" => "Yellow"
        case "W" => "White"
        case "R" => "Red"
        case "B" => "Blue"
        case other => other
    }

    def main(args : Array[String]) {
        if (args.size != 6) {
            println("gg <create|run> <directory> <database> <url> <cdn> <port>")
            return
        }

        val mode = args(0)
        val database = args(1)
        val directory = args(2)
        val url = args(3)
        val cdn = args(4)
        val port = args(5).toInt

        def readFile(path : String) = {
            import java.nio.charset.StandardCharsets._
            import java.nio.file.{Files, Paths}

            new String(Files.readAllBytes(Paths.get(path)), UTF_8)
        }

        implicit class Ascii(val s : String) {
            def ascii = s.filter(c => c >= 32 && c < 128)
            def asciiplus = s.filter(c => (c >= 32 && c < 128) || (c > 158 && c < 256 && c.isLetter))
            def safe = ascii.filter(_ != '<').filter(_ != '>').filter(_ != '"').filter(_ != '\\')
            def safeplus = asciiplus.filter(_ != '<').filter(_ != '>').filter(_ != '"').filter(_ != '\\')
        }

        def newSecret(n : Int) = {
            val random = new scala.util.Random()

            0.until(n).map(_ => "abcdefghijklmnopqrstuvwxyz".charAt(random.nextInt(26))).mkString("")
        }

        val db = Database.forURL("jdbc:hsqldb:file:" + database + ";hsqldb.cache_rows=10000;hsqldb.nio_data_file=false", driver="org.hsqldb.jdbcDriver")

        object execute {
            import scala.concurrent.Await
            import scala.concurrent.duration.Duration

            def apply[E <: Effect](actions : DBIOAction[_, NoStream, E]*) = Await.result(db.run(DBIO.seq(actions : _*).withPinnedSession), Duration.Inf)
            def apply[R](action : DBIOAction[R, NoStream, Effect.Read]) : R = Await.result(db.run(action.withPinnedSession), Duration.Inf)
        }

        if (mode == "create") {
            execute(users.schema.create, journals.schema.create, entries.schema.create, accessRights.schema.create, plays.schema.create, notifiedTurns.schema.create)
            println("Created database.")
            return
        }

        if (mode != "run") {
            println("Unknown mode.")
            return
        }

        implicit val system = ActorSystem()
        implicit val executionContext = system.dispatcher

        def hasRight[R, E <: Effect with Effect.Read](userId : String, userSecret : String, journalId : String, right : String)(then : => DBIOAction[R, NoStream, E]) : DBIOAction[R, NoStream, E] = {
            users.filter(_.id === userId).filter(_.secret === userSecret).result.head.flatMap { _ =>
                accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === right).result.head.flatMap { _ =>
                    then
                }
            }
        }

        def index = readFile(directory + "/index.html")

        val internalKey = sys.env.getOrElse("INTERNAL_API_KEY", "")

        def html(s : String) = complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s))
        def plain(s : String) = complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, s))
        def redir(s : String) = redirect(s, StatusCodes.SeeOther)

        val route = cors() {
            (pathPrefix("hrf")) {
                optionalHeaderValueByName("Referer") { referer =>
                    if (referer.exists(_.startsWith(url)))
                        getFromDirectory(directory)
                    else
                        complete(StatusCodes.NotFound, "")
                }
            } ~
            (get & path("")) {
                redir("/play")
            } ~
            (get & path("play" / "")) {
                redir("/play")
            } ~
            (get & path("play")) {
                html(index
                    .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                    .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                    .replace("data-meta=\"" + "\"", "data-meta=\"" + "" + "\"")
                )
            } ~
            (get & path("play" / Segment / "")) { meta =>
                redir("/play/" + meta)
            } ~
            (get & path("play" / Segment)) { meta =>
                html(index
                    .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                    .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                    .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                )
            } ~
            (get & path("play" / Segment / Segments)) { (meta, secret) =>
                if (secret.length == 1 && secret(0).length == 16) {
                    val (user, play) = execute(plays.filter(_.secret === secret(0)).flatMap { play =>
                        users.filter(_.id === play.userId).map((_, play))
                    }.result.head)

                    html(index
                        .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                        .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                        .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                        .replace("data-user=\"" + "\"", "data-user=\"" + user.id + "\"")
                        .replace("data-secret=\"" + "\"", "data-secret=\"" + user.secret + "\"")
                        .replace("data-lobby=\"" + "\"", "data-lobby=\"" + play.journalId + "\"")
                    )
                }
                else
                    html(index
                        .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                        .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                        .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                    )
            } ~
            (post & path("new-user")) {
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(32).trim.safeplus
                        val user = User(name, newSecret(16), newSecret(16))
                        execute(users += user)
                        plain(user.id + "\n" + user.secret)
                    }
                }
            } ~
            (post & path("new-journal" / Segment / Segment)) { case (userId, userSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(128).trim.safeplus
                        val id = newSecret(16)
                        execute(users.filter(_.id === userId).filter(_.secret === userSecret).map(_.id).result.head.flatMap { userId =>
                            seq(
                                journals += Journal(name, false, "", "", id),
                                accessRights += AccessRight(id, userId, "full"),
                                accessRights += AccessRight(id, userId, "read"),
                                accessRights += AccessRight(id, userId, "append")
                            )
                        })
                        plain(id)
                    }
                }
            } ~
            (post & path("grant-read" / Segment / Segment / Segment / Segment)) { case (userId, userSecret, journalId, anotherUser) =>
                execute(hasRight(userId, userSecret, journalId, "full") {
                    users.filter(_.id === anotherUser).result.head.flatMap { _ =>
                        accessRights += AccessRight(journalId, anotherUser, "read")
                    }
                })
                plain("")
            } ~
            (post & path("grant-read-append" / Segment / Segment / Segment / Segment)) { case (userId, userSecret, journalId, anotherUser) =>
                execute(hasRight(userId, userSecret, journalId, "full") {
                    users.filter(_.id === anotherUser).result.head.flatMap { _ =>
                        accessRights ++= List(AccessRight(journalId, anotherUser, "read"), AccessRight(journalId, anotherUser, "append"))
                    }
                })
                plain("")
            } ~
            (post & path("new-play" / Segment / Segment / Segment)) { case (userId, userSecret, journalId) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(32).trim.safeplus
                        val secret = newSecret(16)
                        val user = User(name, newSecret(16), newSecret(16))
                        execute(hasRight(userId, userSecret, journalId, "full") {
                            seq(
                                users += user,
                                accessRights += AccessRight(journalId, user.id, "read"),
                                accessRights += AccessRight(journalId, user.id, "append"),
                                plays += Play(journalId, user.id, secret)
                            )
                        })
                        plain(user.id + "\n" + secret)
                    }
                }
            } ~
            (get & path("read" / Segment / Segment / Segment / IntNumber)) { (userId, userSecret, journalId, from) =>
                val log = execute(hasRight(userId, userSecret, journalId, "read") {
                    entries.filter(_.journalId === journalId).filter(_.index >= from).map(_.text).result
                })
                plain(log.mkString("\n"))
            } ~
            (post & path("append" / Segment / Segment / Segment / IntNumber)) { (userId, userSecret, journalId, from) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val ss = body.split('\n').toList.map(_.asciiplus)

                        try {
                            execute(hasRight(userId, userSecret, journalId, "append") {
                                entries ++= 0.until(ss.size).map(n => Entry(journalId, from + n, userId, ss(n)))
                            })
                            complete(StatusCodes.Accepted)
                        }
                        catch {
                            case e : java.sql.SQLIntegrityConstraintViolationException => complete(StatusCodes.Conflict)
                        }
                    }
                }
            } ~
            (post & path("register-email" / Segment / Segment)) { case (userId, userSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val email = body.trim.take(254).ascii
                        execute(
                            users.filter(_.id === userId).filter(_.secret === userSecret)
                                .map(_.email)
                                .update(if (email.nonEmpty) Some(email) else None)
                        )
                        plain("")
                    }
                }
            } ~
            (post & path("register-name" / Segment / Segment)) { case (userId, userSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.trim.take(32).safeplus
                        if (name.nonEmpty)
                            execute(
                                users.filter(_.id === userId).filter(_.secret === userSecret)
                                    .map(_.name)
                                    .update(name)
                            )
                        plain("")
                    }
                }
            } ~
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

                        val logEntries = lines.filter(_.startsWith("LOG ")).flatMap { l =>
                            val rest = l.drop(4)
                            val tab = rest.indexOf('\t')
                            if (tab > 0)
                                scala.util.Try(rest.take(tab).toInt).toOption.map(idx => idx -> rest.drop(tab + 1).take(200).asciiplus)
                            else None
                        }

                        // any client with the journal open can independently detect a wait transition and
                        // call this, so require only read access, not append
                        val journal = execute(hasRight(userId, userSecret, journalId, "read") {
                            journals.filter(_.id === journalId).result.head
                        })

                        targetFactions.foreach { case (targetUserId, factionName) =>
                            val alreadyIdx = execute(notifiedTurns.filter(n => n.journalId === journalId && n.userId === targetUserId).map(_.index).result.headOption)

                            if (alreadyIdx.forall(_ < index)) {
                                execute(
                                    if (alreadyIdx.isDefined)
                                        notifiedTurns.filter(n => n.journalId === journalId && n.userId === targetUserId).map(_.index).update(index)
                                    else
                                        notifiedTurns += NotifiedTurn(journalId, targetUserId, index)
                                )

                                val targetUser = execute(users.filter(_.id === targetUserId).result.headOption)
                                // Plays rows are keyed by the lobby journal, not the per-chapter game journal
                                val secret = execute(plays.filter(p => p.journalId === lobbyId && p.userId === targetUserId).map(_.secret).result.headOption)

                                (targetUser, secret) match {
                                    case (Some(u), Some(s)) if u.email.exists(_.nonEmpty) =>
                                        val since = alreadyIdx.getOrElse(0)
                                        val recentLog = logEntries.filter(_._1 > since).sortBy(_._1).map(_._2).takeRight(30)
                                        EmailSender.sendTurnEmail(u.email.get, u.name, factionName, journal.name, url + "/play/" + metaName + "/" + s, recentLog)
                                    case _ =>
                                }
                            }
                        }

                        complete(StatusCodes.Accepted)
                    }
                }
            } ~
            (get & path("internal" / "active-games" / Segment)) { key =>
                if (internalKey.isEmpty || key != internalKey)
                    complete(StatusCodes.Forbidden, "")
                else {
                    val lobbyIds = execute(plays.map(_.journalId).result).distinct
                    val lines = lobbyIds.flatMap { lobbyId =>
                        val entryLines = execute(entries.filter(_.journalId === lobbyId).sortBy(_.index).map(_.text).result).toList
                        val info = parseLobby(entryLines)
                        // The spectator gets its own Play row on the lobby journal alongside the
                        // named players (see new-play calls in the online-game creation flow);
                        // it's whichever Play's userId isn't one of the named players.
                        val lobbyPlays = execute(plays.filter(_.journalId === lobbyId).result)
                        val spectateSecret = lobbyPlays.find(p => !info.letterToUserId.values.toSet.contains(p.userId)).map(_.secret).getOrElse("")

                        if (info.gameJournalId.nonEmpty && spectateSecret.nonEmpty && info.meta.nonEmpty)
                            Some("GAME " + info.gameJournalId + " " + info.meta + " " + spectateSecret)
                        else
                            None
                    }
                    plain(lines.distinct.mkString("\n"))
                }
            } ~
            (post & path("internal" / "notify-wait" / Segment / Segment / Segment)) { case (key, gameJournalId, letter) =>
                if (internalKey.isEmpty || key != internalKey)
                    complete(StatusCodes.Forbidden, "")
                else decodeRequest {
                    entity(as[String]) { body =>
                    val bodyLines = body.split('\n').toList
                    val maxIndex = bodyLines.find(_.startsWith("INDEX ")).map(_.drop(6).trim.toInt).getOrElse(0)
                    val logEntries = bodyLines.filter(_.startsWith("LOG ")).flatMap { l =>
                        val rest = l.drop(4)
                        val tab = rest.indexOf('\t')
                        if (tab > 0)
                            scala.util.Try(rest.take(tab).toInt).toOption.map(idx => idx -> rest.drop(tab + 1).take(200).asciiplus)
                        else None
                    }

                    val lobbyIds = execute(plays.map(_.journalId).result).distinct
                    val found = lobbyIds.flatMap { lobbyId =>
                        val entryLines = execute(entries.filter(_.journalId === lobbyId).sortBy(_.index).map(_.text).result).toList
                        val info = parseLobby(entryLines)
                        if (info.gameJournalId == gameJournalId) Some((lobbyId, info)) else None
                    }.headOption

                    found.foreach { case (lobbyId, info) =>
                        info.letterToUserId.get(letter).foreach { targetUserId =>
                            val alreadyIdx = execute(notifiedTurns.filter(n => n.journalId === gameJournalId && n.userId === targetUserId).map(_.index).result.headOption)

                            if (alreadyIdx.forall(_ < maxIndex)) {
                                execute(
                                    if (alreadyIdx.isDefined)
                                        notifiedTurns.filter(n => n.journalId === gameJournalId && n.userId === targetUserId).map(_.index).update(maxIndex)
                                    else
                                        notifiedTurns += NotifiedTurn(gameJournalId, targetUserId, maxIndex)
                                )

                                val targetUser = execute(users.filter(_.id === targetUserId).result.headOption)
                                val secret = execute(plays.filter(p => p.journalId === lobbyId && p.userId === targetUserId).map(_.secret).result.headOption)

                                (targetUser, secret) match {
                                    case (Some(u), Some(s)) if u.email.exists(_.nonEmpty) =>
                                        val since = alreadyIdx.getOrElse(0)
                                        val recentLog = logEntries.filter(_._1 > since).sortBy(_._1).map(_._2).takeRight(30)
                                        EmailSender.sendTurnEmail(u.email.get, u.name, factionName(letter), info.title, url + "/play/" + info.meta + "/" + s, recentLog)
                                    case _ =>
                                }
                            }
                        }
                    }

                    complete(StatusCodes.Accepted)
                    }
                }
            }
        }

        val settings = ServerSettings("").withRemoteAddressAttribute(true)

        var server = Http().newServerAt("0.0.0.0", port).withSettings(settings)

        val keyFile = new java.io.File("certificate.pkcs12")

        if (keyFile.exists()) {
            val hcc = Ssl.serverHttpsContext(keyFile, "")

            server = server.enableHttps(hcc)
        }

        val bindingFuture = server.bind(route)

        println("Started server.")

        if (port != 80 && keyFile.exists()) {
            val redirroute = get {
                redirect(url, StatusCodes.MovedPermanently)
            }

            Http().newServerAt("0.0.0.0", 80).bind(redirroute)

            println("Started redirect server.")
        }

        while (true)
            Thread.sleep(1000)

        bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
    }
}
