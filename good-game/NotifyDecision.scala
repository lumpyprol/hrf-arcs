package hrf.gg

/** The single server-side authority for "have we already told this person about
  * this state?". Pure and DB-free so it can be unit-tested in isolation; see
  * NOTIFICATION_DEDUP_PLAN.md and NotifyDecisionTest.
  */
object NotifyDecision {
    case class LastNotified(index : Int, prompt : String)

    /** promptOpt = None means "index-only" mode (notify-turn's case, since the
      * vendored client never sends prompt text at all).
      */
    def shouldNotify(last : Option[LastNotified], newIndex : Int, promptOpt : Option[String]) : Boolean =
        last match {
            case None                            => true
            case Some(l) if newIndex > l.index   => true
            case Some(l) if newIndex == l.index  => promptOpt.exists(_ != l.prompt)
            case _                               => false // stale/out-of-order, never re-notify
        }
}
