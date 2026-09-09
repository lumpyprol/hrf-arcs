// Prompt 4 of NOTIFICATION_DEDUP_PLAN.md: TDD the shared dispatch helper that
// replaces the near-duplicate inline blocks in notify-turn / notify-wait.
// Real in-memory HSQLDB, injected fake sendEmail + log.
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import slick.jdbc.HsqldbProfile.api._

import hrf.gg.GoodGame
import hrf.gg.GoodGame.{Journal, NotifiedTurn, Play, User}
import hrf.gg.{Migrations, TurnNotifier}
import hrf.gg.TurnNotifier.{Info, Outgoing}

class TurnNotifierTest extends munit.FunSuite {

    val info = Info(
        factionName = "Sky Barons",
        factionLetter = "B",
        gameTitle = "Test Game",
        meta = "meta123",
        preferredName = Some("Alice"),
    )

    case class Rig(
        notifier : TurnNotifier,
        sent : ListBuffer[Outgoing],
        logs : ListBuffer[String],
        db : Database,
    )

    val rig = FunFixture[Rig](
        setup = { test =>
            val db = Database.forURL(
                "jdbc:hsqldb:mem:" + test.name.replaceAll("\\W", "_"),
                driver = "org.hsqldb.jdbcDriver",
            )
            Await.result(db.run(DBIO.seq(
                GoodGame.users.schema.create,
                GoodGame.journals.schema.create,
                GoodGame.entries.schema.create,
                GoodGame.accessRights.schema.create,
                GoodGame.plays.schema.create,
                GoodGame.notifiedTurns.schema.create,
                GoodGame.remindedTurns.schema.create,
            )), Duration.Inf)
            // FK parents for Plays / NotifiedTurns
            Await.result(db.run(GoodGame.journals ++= List(
                Journal("Lobby", false, "", "", "lobby1"),
                Journal("Test Game", false, "", "", "game1"),
            )), Duration.Inf)
            val sent = ListBuffer.empty[Outgoing]
            val logs = ListBuffer.empty[String]
            val notifier = new TurnNotifier(db, "https://example.test", sent += _, logs += _)
            Rig(notifier, sent, logs, db)
        },
        teardown = { rig => rig.db.close() },
    )

    def seedUser(db : Database, id : String, name : String, email : Option[String]) : Unit =
        Await.result(db.run(GoodGame.users += User(name, "sekret-" + id, id, email)), Duration.Inf)

    def seedPlay(db : Database, lobbyId : String, userId : String, secret : String) : Unit =
        Await.result(db.run(GoodGame.plays += Play(lobbyId, userId, secret)), Duration.Inf)

    def notified(db : Database, journalId : String, userId : String) : Option[NotifiedTurn] =
        Await.result(db.run(
            GoodGame.notifiedTurns.filter(n => n.journalId === journalId && n.userId === userId).result.headOption
        ), Duration.Inf)

    // ---- send branch --------------------------------------------------------

    for (promptOpt <- List(Option.empty[String], Some("Blue leads"))) {
        val tag = if (promptOpt.isDefined) "notify-wait (Some prompt)" else "notify-turn (None prompt)"

        rig.test(s"$tag: user + secret + email -> sendEmail called with the right args") { r =>
            seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
            seedPlay(r.db, "lobby1", "u1", "playsecret")

            r.notifier.dispatch("game1", "u1", "lobby1", 10, promptOpt,
                List(1 -> "old entry", 5 -> "recent entry"), info)

            assertEquals(r.sent.toList, List(Outgoing(
                to = "bob@example.test",
                playerName = "Alice", // info.preferredName wins over Users.name
                factionName = "Sky Barons",
                factionLetter = "B",
                gameTitle = "Test Game",
                link = "https://example.test/play/meta123/playsecret",
                recentLog = List("old entry", "recent entry"), // all entries with _._1 > since (0)
            )))
            assertEquals(r.logs.toList, Nil)
            assertEquals(notified(r.db, "game1", "u1").map(_.index), Some(10))
        }

        rig.test(s"$tag: user + secret, no email -> 'no email address registered', no send") { r =>
            seedUser(r.db, "u1", "Bob", None)
            seedPlay(r.db, "lobby1", "u1", "playsecret")

            r.notifier.dispatch("game1", "u1", "lobby1", 10, promptOpt, Nil, info)

            assertEquals(r.sent.toList, Nil)
            assertEquals(r.logs.toList, List("Skipping turn email for u1 (game1): no email address registered"))
        }

        rig.test(s"$tag: user, no secret -> 'no play/secret found on lobby', no send") { r =>
            seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
            // no Play row

            r.notifier.dispatch("game1", "u1", "lobby1", 10, promptOpt, Nil, info)

            assertEquals(r.sent.toList, Nil)
            assertEquals(r.logs.toList, List("Skipping turn email for u1 (game1): no play/secret found on lobby lobby1"))
        }

        rig.test(s"$tag: no user record -> 'user record not found', no send") { r =>
            // no User row, and (FK: Plays -> Users) therefore no Play row either

            r.notifier.dispatch("game1", "u1", "lobby1", 10, promptOpt, Nil, info)

            assertEquals(r.sent.toList, Nil)
            assertEquals(r.logs.toList, List("Skipping turn email for u1 (game1): user record not found"))
        }
    }

    // ---- dedup delegation --------------------------------------------------

    rig.test("second identical dispatch is silent (delegates to NotifyDecision)") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedPlay(r.db, "lobby1", "u1", "playsecret")

        r.notifier.dispatch("game1", "u1", "lobby1", 10, Some("Blue leads"), Nil, info)
        r.notifier.dispatch("game1", "u1", "lobby1", 10, Some("Blue leads"), Nil, info)

        assertEquals(r.sent.size, 1)
        assert(r.logs.exists(_.contains("already notified at index 10")))
    }

    rig.test("higher index with the same recycled prompt still notifies (the regression)") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedPlay(r.db, "lobby1", "u1", "playsecret")

        r.notifier.dispatch("game1", "u1", "lobby1", 340, Some("Yellow leads"), Nil, info)
        r.notifier.dispatch("game1", "u1", "lobby1", 350, Some("Yellow leads"), Nil, info)

        assertEquals(r.sent.size, 2)
    }

    // ---- reminder-clock reset --------------------------------------------

    rig.test("sending a turn email clears the RemindedTurns row (no double-fire with notify-reminder)") { r =>
        seedUser(r.db, "u1", "Bob", Some("bob@example.test"))
        seedPlay(r.db, "lobby1", "u1", "playsecret")
        // stale reminder clock from a previous turn, > 24h old
        Await.result(r.db.run(GoodGame.remindedTurns += GoodGame.RemindedTurn("game1", "u1", 1L)), Duration.Inf)

        r.notifier.dispatch("game1", "u1", "lobby1", 10, Some("Blue leads"), Nil, info)

        assertEquals(r.sent.size, 1)
        val remaining = Await.result(r.db.run(
            GoodGame.remindedTurns.filter(n => n.journalId === "game1" && n.userId === "u1").result
        ), Duration.Inf)
        assertEquals(remaining, Vector.empty)
    }

    rig.test("a dispatch that does not send (no email) leaves the RemindedTurns row alone") { r =>
        seedUser(r.db, "u1", "Bob", None) // no email -> skip branch
        seedPlay(r.db, "lobby1", "u1", "playsecret")
        Await.result(r.db.run(GoodGame.remindedTurns += GoodGame.RemindedTurn("game1", "u1", 42L)), Duration.Inf)

        r.notifier.dispatch("game1", "u1", "lobby1", 10, Some("Blue leads"), Nil, info)

        assertEquals(r.sent.size, 0)
        val remaining = Await.result(r.db.run(
            GoodGame.remindedTurns.filter(n => n.journalId === "game1" && n.userId === "u1").map(_.lastSentAt).result
        ), Duration.Inf)
        assertEquals(remaining, Vector(42L))
    }
}
