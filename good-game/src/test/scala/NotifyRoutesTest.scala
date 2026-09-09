// Prompt 5 of NOTIFICATION_DEDUP_PLAN.md: route-level tests for notify-turn and
// notify-wait. Pins the wire contract (notify-turn stays index-only with no
// PROMPT line; notify-wait is internal-key-gated and now reads a PROMPT line)
// and that both go through the one shared TurnNotifier path.
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}

import slick.jdbc.HsqldbProfile.api._

import hrf.gg.GoodGame
import hrf.gg.GoodGame.{Entry, Journal, Play, User}
import hrf.gg.{NotifyRoutes, TurnNotifier}
import hrf.gg.TurnNotifier.Outgoing

class NotifyRoutesTest extends munit.FunSuite {

    // for the RequestBuilding Post(uri, stringBody) marshaller
    implicit val ec : scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    // Mirror of GoodGame.main's authExceptionHandler, which wraps these routes
    // in production (an empty .result.head -> 403, not 500).
    val sealHandler = ExceptionHandler { case _ : NoSuchElementException => complete(StatusCodes.Forbidden, "") }

    case class Rig(run : akka.http.scaladsl.model.HttpRequest => HttpResponse, sent : ListBuffer[Outgoing], db : Database, system : ActorSystem)

    val rig = FunFixture[Rig](
        setup = { test =>
            implicit val system = ActorSystem("t" + test.name.replaceAll("\\W", ""))
            val db = Database.forURL("jdbc:hsqldb:mem:" + test.name.replaceAll("\\W", "_"), driver = "org.hsqldb.jdbcDriver")
            Await.result(db.run(DBIO.seq(
                GoodGame.users.schema.create,
                GoodGame.journals.schema.create,
                GoodGame.entries.schema.create,
                GoodGame.accessRights.schema.create,
                GoodGame.plays.schema.create,
                GoodGame.notifiedTurns.schema.create,
                GoodGame.remindedTurns.schema.create,
            )), 10.seconds)
            Await.result(db.run(GoodGame.journals ++= List(
                Journal("Lobby", false, "", "", "lobby1"),
                Journal("Chapter Two", false, "", "", "game1"),
            )), 10.seconds)
            val sent = ListBuffer.empty[Outgoing]
            val routes = new NotifyRoutes(db, "https://example.test", "the-key", sent += _)
            val sealed_ = Route.seal(handleExceptions(sealHandler)(routes.route))
            val f = Route.toFunction(sealed_)
            val run = (req : akka.http.scaladsl.model.HttpRequest) => Await.result(f(req), 10.seconds)
            Rig(run, sent, db, system)
        },
        teardown = { rig =>
            rig.db.close()
            Await.result(rig.system.terminate(), 10.seconds)
        },
    )

    def seedUser(db : Database, id : String, name : String, email : Option[String]) =
        Await.result(db.run(GoodGame.users += User(name, "sekret" + id, id, email)), 10.seconds)
    def seedPlay(db : Database, lobbyId : String, userId : String, secret : String) =
        Await.result(db.run(GoodGame.plays += Play(lobbyId, userId, secret)), 10.seconds)
    def seedRight(db : Database, journalId : String, userId : String, right : String) =
        Await.result(db.run(GoodGame.accessRights += GoodGame.AccessRight(journalId, userId, right)), 10.seconds)
    def seedLobbyEntries(db : Database) =
        Await.result(db.run(GoodGame.entries ++= List(
            Entry("lobby1", 0, "u1", "server game1"),
            Entry("lobby1", 1, "u1", "title Chapter Two"),
            Entry("lobby1", 2, "u1", "meta metatoken"),
            Entry("lobby1", 3, "u1", "user B u1"),
            Entry("lobby1", 4, "u1", "name u1 Alice"),
        )), 10.seconds)

    // ---- notify-turn -------------------------------------------------------

    rig.test("notify-turn: no PROMPT line, sends once, dedups on repeat, re-sends on higher index") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedUser(r.db, "caller", "Caller", None)
        seedPlay(r.db, "lobby1", "u1", "playsecret")
        seedRight(r.db, "game1", "caller", "read")

        val body = "META metatoken\nTARGET u1 Blue\nLOG 5\tsomething happened"
        assertEquals(r.run(Post("/notify-turn/caller/sekretcaller/game1/lobby1/40", body)).status, StatusCodes.Accepted)
        assertEquals(r.run(Post("/notify-turn/caller/sekretcaller/game1/lobby1/40", body)).status, StatusCodes.Accepted)
        assertEquals(r.run(Post("/notify-turn/caller/sekretcaller/game1/lobby1/50", body)).status, StatusCodes.Accepted)

        assertEquals(r.sent.map(o => (o.to, o.playerName, o.factionName, o.gameTitle, o.link)).toList, List(
            ("bob@example.test", "Bob", "Blue", "Chapter Two", "https://example.test/play/metatoken/playsecret"),
            ("bob@example.test", "Bob", "Blue", "Chapter Two", "https://example.test/play/metatoken/playsecret"),
        ))
    }

    rig.test("notify-turn: bad secret -> 403, no send") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedUser(r.db, "caller", "Caller", None)
        seedRight(r.db, "game1", "caller", "read")

        assertEquals(r.run(Post("/notify-turn/caller/WRONG/game1/lobby1/40", "TARGET u1 Blue")).status, StatusCodes.Forbidden)
        assertEquals(r.sent.toList, Nil)
    }

    // ---- notify-wait ------------------------------------------------------

    rig.test("notify-wait: wrong internal key -> 403") { r =>
        assertEquals(r.run(Post("/internal/notify-wait/WRONG/game1/B", "INDEX 10")).status, StatusCodes.Forbidden)
        assertEquals(r.sent.toList, Nil)
    }

    rig.test("notify-wait: PROMPT line drives multi-step re-notify at the same index") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedPlay(r.db, "lobby1", "u1", "playsecret")
        seedLobbyEntries(r.db)

        def call(index : Int, prompt : String) =
            r.run(Post(s"/internal/notify-wait/the-key/game1/B", s"INDEX $index\nPROMPT $prompt\nLOG 3\tlog line")).status

        assertEquals(call(100, "Yellow leads"), StatusCodes.Accepted) // first -> send
        assertEquals(call(100, "Yellow leads"), StatusCodes.Accepted) // same index+prompt -> silent
        assertEquals(call(100, "Rearrange your ships"), StatusCodes.Accepted) // same index, new prompt -> send
        assertEquals(call(120, "Yellow leads"), StatusCodes.Accepted) // higher index, recycled prompt -> send

        assertEquals(r.sent.size, 3)
        assertEquals(r.sent.map(_.playerName).toList, List("Alice", "Alice", "Alice")) // lobby name, not Users.name
        assertEquals(r.sent.head.link, "https://example.test/play/metatoken/playsecret")
        assertEquals(r.sent.head.factionName, "Blue") // factionName("B")
    }

    rig.test("notify-wait: missing PROMPT line still notifies once, then dedups on index") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedPlay(r.db, "lobby1", "u1", "playsecret")
        seedLobbyEntries(r.db)

        assertEquals(r.run(Post("/internal/notify-wait/the-key/game1/B", "INDEX 10")).status, StatusCodes.Accepted)
        assertEquals(r.run(Post("/internal/notify-wait/the-key/game1/B", "INDEX 10")).status, StatusCodes.Accepted)
        assertEquals(r.sent.size, 1)
    }
}
