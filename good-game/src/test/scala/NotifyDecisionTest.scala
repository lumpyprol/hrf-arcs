// Prompt 1 of NOTIFICATION_DEDUP_PLAN.md: prove the munit test harness is wired.
// Real coverage of NotifyDecision.shouldNotify lands in Prompt 2.
class NotifyDecisionTest extends munit.FunSuite {
  test("test harness is wired") {
    assertEquals(1, 1)
  }
}
