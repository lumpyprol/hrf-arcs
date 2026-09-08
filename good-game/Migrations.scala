package hrf.gg

import slick.jdbc.HsqldbProfile.api._

/** Schema migrations applied on every boot of an already-existing production
  * database (see the long comment by the RemindedTurns migration in
  * GoodGame.scala for why these are plain hand-written DDL wrapped in
  * try/catch rather than Slick's createIfNotExists / schema.create).
  *
  * The DDL strings live here so they can be exercised against a real HSQLDB
  * instance in NotifiedTurnsMigrationTest.
  */
object Migrations {
    /** Adds NotifiedTurns.lastPrompt (the prompt-text half of the dedup key).
      * Throws on a second run because the column already exists - callers wrap
      * this in try/catch, which is what makes the migration idempotent.
      */
    val addNotifiedTurnsLastPrompt : DBIO[Int] =
        sqlu"""ALTER TABLE "NotifiedTurns" ADD COLUMN "lastPrompt" VARCHAR(50000) DEFAULT ''"""
}
