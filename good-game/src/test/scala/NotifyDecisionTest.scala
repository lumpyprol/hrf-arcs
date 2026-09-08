// Prompt 2 of NOTIFICATION_DEDUP_PLAN.md: TDD the single dedup authority.
//
// NotifyDecision.shouldNotify is the one place that answers "have we already
// told this person about this state?". promptOpt = None is "index-only" mode
// (notify-turn: the vendored client never sends prompt text); promptOpt =
// Some(text) is notify-wait, which also re-notifies on a new distinct prompt
// at the same index (multi-step turns).
import hrf.gg.NotifyDecision
import hrf.gg.NotifyDecision.LastNotified

class NotifyDecisionTest extends munit.FunSuite {

  test("no prior state -> notify") {
    assert(NotifyDecision.shouldNotify(None, 100, Some("Yellow leads")))
    assert(NotifyDecision.shouldNotify(None, 100, None))
  }

  test("same index, same prompt -> silent") {
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(340, "Yellow leads")), 340, Some("Yellow leads")))
  }

  test("same index, different prompt (multi-step turn) -> notify") {
    assert(NotifyDecision.shouldNotify(Some(LastNotified(340, "Negotiate")), 340, Some("Rearrange")))
  }

  test("higher index, same prompt -> notify (the production regression)") {
    assert(NotifyDecision.shouldNotify(Some(LastNotified(340, "Yellow leads")), 350, Some("Yellow leads")))
  }

  test("higher index, different prompt -> notify") {
    assert(NotifyDecision.shouldNotify(Some(LastNotified(340, "Yellow leads")), 350, Some("Blue leads")))
  }

  test("lower index (stale/out-of-order) -> silent, regardless of prompt") {
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(350, "Yellow leads")), 340, Some("Yellow leads")))
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(350, "Yellow leads")), 340, Some("Totally different")))
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(350, "Yellow leads")), 340, None))
  }

  test("index-only mode (promptOpt = None): higher index -> notify, same index -> silent") {
    assert(NotifyDecision.shouldNotify(Some(LastNotified(340, "")), 350, None))
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(340, "")), 340, None))
  }

  test("152 consecutive identical polls -> silent") {
    assert(!NotifyDecision.shouldNotify(Some(LastNotified(341, "Blue leads")), 341, Some("Blue leads")))
  }
}
