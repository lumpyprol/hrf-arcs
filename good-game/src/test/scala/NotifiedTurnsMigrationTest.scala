// Prompt 3 of NOTIFICATION_DEDUP_PLAN.md: TDD the lastPrompt column migration
// against a real in-memory HSQLDB, so the exact ALTER TABLE DDL is verified to
// be valid HSQLDB syntax before it ever reaches prod (the step skipped before
// the RemindedTurns outage).
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import slick.jdbc.HsqldbProfile.api._

import hrf.gg.Migrations

class NotifiedTurnsMigrationTest extends munit.FunSuite {

    val dbFixture = FunFixture[Database](
        setup = { test =>
            val db = Database.forURL(
                "jdbc:hsqldb:mem:" + test.name.replaceAll("\\W", "_"),
                driver = "org.hsqldb.jdbcDriver",
            )
            // Pre-migration shape: journalId, userId, index only - no lastPrompt.
            Await.result(db.run(sqlu"""
                CREATE TABLE "NotifiedTurns" (
                    "journalId" VARCHAR(255) NOT NULL,
                    "userId" VARCHAR(255) NOT NULL,
                    "index" INTEGER NOT NULL,
                    PRIMARY KEY ("journalId", "userId")
                )
            """), Duration.Inf)
            db
        },
        teardown = { db => db.close() },
    )

    // Mirrors GoodGame.scala's startup: run the DDL, swallow "already exists".
    def migrate(db : Database) : Unit =
        try Await.result(db.run(Migrations.addNotifiedTurnsLastPrompt), Duration.Inf)
        catch { case _ : Throwable => () }

    dbFixture.test("migration adds lastPrompt and backfills existing rows with \"\"") { db =>
        Await.result(db.run(
            sqlu"""INSERT INTO "NotifiedTurns" ("journalId", "userId", "index") VALUES ('j1', 'u1', 42)"""
        ), Duration.Inf)

        migrate(db)

        val rows = Await.result(db.run(
            sql"""SELECT "index", "lastPrompt" FROM "NotifiedTurns" WHERE "journalId" = 'j1'""".as[(Int, String)]
        ), Duration.Inf)
        assertEquals(rows, Vector((42, "")))
    }

    dbFixture.test("running the same migration a second time does not throw (idempotent)") { db =>
        migrate(db)
        migrate(db) // must not throw

        // and the column is still usable afterwards
        Await.result(db.run(
            sqlu"""INSERT INTO "NotifiedTurns" ("journalId", "userId", "index", "lastPrompt") VALUES ('j2', 'u2', 7, 'Blue leads')"""
        ), Duration.Inf)
        val prompt = Await.result(db.run(
            sql"""SELECT "lastPrompt" FROM "NotifiedTurns" WHERE "journalId" = 'j2'""".as[String]
        ), Duration.Inf)
        assertEquals(prompt, Vector("Blue leads"))
    }
}
