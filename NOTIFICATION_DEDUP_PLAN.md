# Prompt Plan: Collapse dedup to a single server-side authority

## Objective
Delete the class of bug this session spent hours chasing (recurring prompt text silently suppressing real notifications, in-memory cache going stale over a 12-day uptime, duplicated skip-logic between two routes) by moving the *entire* "have we already told this person" decision into one server-side, index-and-prompt-aware function, shared by both `notify-wait` and `notify-turn`. `watch.js` stops pre-filtering with its own `lastSeen` cache and reports raw observed state on every poll — exactly the pattern `notify-reminder` already uses successfully.

## Non-goals
- Not touching the vendored client bundle (`haunt-roll-fail/vendor/`) or its wire contract to `notify-turn` — that stays byte-for-byte identical from the client's point of view.
- Not fixing the underlying Cloudflare/render-flakiness issue — orthogonal, already mitigated by the retry logic.
- Not migrating `RemindedTurns`/reminder logic — it's already correctly unconditional and server-gated; leave it alone.

## Current state (for whoever executes this)
- `good-game/GoodGame.scala`: `notify-turn` (~line 511) and `notify-wait` (~line 602) are near-duplicate blocks — each does its own lobby lookup, `letterToUserId` resolution, `NotifiedTurns` check (`index`-only), targetUser/secret fetch, and an identical three-case skip-logging match. `NotifiedTurns` schema: `(journalId, userId, index)`, PK `(journalId, userId)`.
- `good-game/watcher/watch.js`: `pollOnce` keeps `lastSeen: Map<gameJournalId, Map<letter, {prompt, maxIndex}>>` in memory, gates whether `notifyWait()` is even called. This is the layer that had the recurring-text bug (just patched with an OR-on-maxIndex fix — this plan supersedes that patch by deleting the client-side gate entirely).
- No test framework anywhere in the repo.

## Target design

**1. Schema change** — add `lastPrompt : String` to `NotifiedTurns` (default `""`). Given this session's incident (Slick's `createIfNotExists` emits an `ALTER TABLE ... DROP CONSTRAINT IF EXISTS` that HSQLDB's parser rejects and crash-loops the server), the migration must be plain SQL wrapped in try/catch, mirroring the pattern already fixed in `GoodGame.scala` for `RemindedTurns`:
```scala
try { execute(sqlu"""ALTER TABLE "NotifiedTurns" ADD COLUMN "lastPrompt" VARCHAR(50000) DEFAULT ''""") }
catch { case _ : Throwable => /* column already exists */ }
```
Verify the exact generated/hand-written DDL against HSQLDB syntax *before* deploying — that's the specific step that was skipped last time and caused the outage.

**2. One pure decision function** — the actual fix, fully unit-testable with no DB/Akka in the loop:
```scala
object NotifyDecision {
  case class LastNotified(index: Int, prompt: String)
  // promptOpt = None means "index-only" mode (notify-turn's case, since the
  // vendored client never sends prompt text at all).
  def shouldNotify(last: Option[LastNotified], newIndex: Int, promptOpt: Option[String]): Boolean =
    last match {
      case None                        => true
      case Some(l) if newIndex > l.index  => true
      case Some(l) if newIndex == l.index => promptOpt.exists(_ != l.prompt)
      case _                           => false // stale/out-of-order, never re-notify
    }
}
```

**3. One shared dispatch helper**, replacing both duplicated blocks — takes `(journalId, userId, lobbyId, index, promptOpt, logEntries, info)`, does the `NotifiedTurns` read/write and the three-case skip-logging match once, called from both routes with `notify-turn` passing `promptOpt = None` and `notify-wait` passing `promptOpt = Some(currentPromptText)`.

**4. `watch.js` simplification** — delete `lastSeen` entirely. `pollOnce` calls `notifyWait(gameJournalId, letter, maxIndex, prompt, logEntries)` unconditionally for every currently-waiting letter, same shape as `notifyReminder` already works. Wire format for `notify-wait`'s POST body gains a `PROMPT <text>` line.

## Prompt sequence (red-green-refactor, in this order)

Everything below is one continuous prompt chain, not a description of steps — each numbered section **is** the literal prompt text to hand to the executing agent for that step, in order. Run them one at a time, in the same repo/session, each only after the previous one's acceptance criteria are met. Each prompt is self-contained (it names the file, points back at this plan for shared context, and states its own done-condition) so it can be pasted on its own even in a fresh session.

---

### Prompt 1 — stand up the test harness — ✅ COMPLETED

> **Done:** `munit 1.0.4 % Test` added to `good-game/build.sbt`; `good-game/src/test/scala/NotifyDecisionTest.scala` and `good-game/watcher/test/notify.test.js` both hold one trivial passing test. `sbt test` is green. Node 26 dropped directory-path args to `node --test`, so the watcher suite runs via `npm test` (i.e. `node --test`) from `good-game/watcher/`, or `node --test "test/*.test.js"` — not the literal `node --test good-game/watcher/test/` in this plan.

> Read `NOTIFICATION_DEDUP_PLAN.md` at the repo root for full context on this refactor; this is Prompt 1 of that plan's prompt sequence.
>
> This repo currently has zero test infrastructure on either side. Before writing any real test, add the harness and prove it runs:
> - In `good-game/build.sbt`, add `"org.scalameta" %% "munit" % "1.0.x" % Test`.
> - Create `good-game/src/test/scala/NotifyDecisionTest.scala` with a single trivial passing assertion (e.g. `assertEquals(1, 1)`) — just to prove wiring, not real coverage yet.
> - Run `sbt test` and confirm it passes.
> - In `good-game/watcher/`, create `test/notify.test.js` using Node's built-in `node:test` + `node:assert` (no new npm dependency — Node ≥18 ships both). One trivial passing test.
> - Run `node --test good-game/watcher/test/` and confirm it passes.
>
> Do not write `NotifyDecision`, touch `GoodGame.scala`'s routes, or touch `watch.js`'s polling logic in this step. Stop once both trivial tests are green and commit just the harness.

---

### Prompt 2 — TDD the core decision function — ✅ COMPLETED

> **Done:** `good-game/src/test/scala/NotifyDecisionTest.scala` covers all eight cases (incl. the higher-index-same-prompt regression and the 152-poll stuck state); confirmed red (did not compile) before implementing. `NotifyDecision` lives in a new file `good-game/NotifyDecision.scala` (pure, DB-free) exactly as the "Target design" snippet. `sbt test` green (8/8), `sbt compile` clean. Routes untouched.

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 2, following the test harness landed in Prompt 1.
>
> Write `good-game/src/test/scala/NotifyDecisionTest.scala` completely, testing a `NotifyDecision.shouldNotify` function that does not exist yet — it should fail to compile when you're done writing the tests, which is expected. Cover every one of these cases:
> - No prior state (`None`) → `true`.
> - Same index, same prompt → `false`.
> - Same index, different prompt (multi-step-turn case) → `true`.
> - Higher index, same prompt → `true`. This is the exact regression from production: `shouldNotify(Some(LastNotified(340, "Yellow leads")), 350, Some("Yellow leads"))` must be `true`.
> - Higher index, different prompt → `true`.
> - Lower index (stale/out-of-order data) → `false`, regardless of prompt.
> - `promptOpt = None`, higher index → `true`; same index → `false`. This protects `notify-turn`'s existing index-only contract from regressing.
> - The 152-consecutive-identical-poll case observed in production → `false`: `shouldNotify(Some(LastNotified(341, "Blue leads")), 341, Some("Blue leads"))`.
>
> Confirm the test file fails to compile (red). Then, in `good-game/GoodGame.scala` (or a new file if cleaner), implement exactly the `NotifyDecision` object from the plan's "Target design" section — the minimum needed to make every case above pass. Run `sbt test`, get to green, then refactor only if the implementation is uglier than the plan's version. Do not touch the routes yet. Stop once `sbt test` is fully green and commit.

---

### Prompt 3 — TDD the schema migration — ✅ COMPLETED

> **Done:** `good-game/src/test/scala/NotifiedTurnsMigrationTest.scala` boots a real in-memory HSQLDB 2.7.4 (same version prod uses), creates `NotifiedTurns` in its pre-migration shape, and asserts the column is added, existing rows backfill to `""`, and a second run doesn't throw. Confirmed red (no `hrf.gg.Migrations`) first. The DDL lives in a new `good-game/Migrations.scala` as `addNotifiedTurnsLastPrompt` (`ALTER TABLE "NotifiedTurns" ADD COLUMN "lastPrompt" VARCHAR(50000) DEFAULT ''`) — its HSQLDB validity is proven by the passing test actually executing it. Wired into `GoodGame.scala`'s startup right after the `RemindedTurns` migration, plain SQL in try/catch, **not** `schema.createIfNotExists`. Routes still untouched; the Slick table mapping still doesn't expose the column (that's Prompt 4/5). `sbt test` 10/10, `sbt compile` clean.

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 3, following the core decision function landed in Prompt 2.
>
> Write a test first, in `good-game/src/test/scala/`, that boots an HSQLDB in-memory instance, creates `NotifiedTurns` in its pre-migration shape (`journalId, userId, index` only, no `lastPrompt`), then runs a migration snippet against it and asserts: the `lastPrompt` column now exists, pre-existing rows read back with `lastPrompt = ""`, and running the exact same migration snippet a second time does not throw (idempotency).
>
> Confirm this test fails (red) since the migration doesn't exist yet. Then implement the migration in `GoodGame.scala`'s startup path, next to the existing `RemindedTurns` migration, using plain SQL wrapped in try/catch — explicitly **not** `schema.createIfNotExists`, per the incident notes in this plan's "Target design" section. Before considering this done, manually verify the exact `ALTER TABLE` statement is valid HSQLDB syntax (this is the specific step that was skipped before the `RemindedTurns` outage). Get the test green, then stop and commit — do not wire this into the live routes yet.

---

### Prompt 4 — TDD the shared dispatch helper — ✅ COMPLETED

> **Done:** `good-game/TurnNotifier.scala` — `class TurnNotifier(db, baseUrl, sendEmail, log)` with `dispatch(journalId, userId, lobbyId, index, promptOpt, logEntries, info)`, dedup delegated entirely to `NotifyDecision.shouldNotify`. `sendEmail` and `log` are injected (fakes in tests). `good-game/src/test/scala/TurnNotifierTest.scala` runs it against a real in-memory HSQLDB for both `promptOpt = None` and `Some(...)`: the actual-send branch (asserts exact `Outgoing` args), and all three skip branches (no email / no secret / no user) with their exact log lines; plus dedup-delegation and the recycled-prompt regression. Confirmed red first. `NotifiedTurn` case class + Slick table now carry `lastPrompt` (defaulted, so the still-present old route blocks keep compiling). `dispatch` looks up user/secret *before* writing `NotifiedTurns` (that table FKs to Users) and marks-notified in every branch except no-user, so unconditional re-polls (Prompt 6) won't spam. Old duplicated route blocks left in place. `sbt test` 20/20, `sbt compile` clean.

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 4, following the migration landed in Prompt 3.
>
> Write tests first for a new shared helper (signature per the plan: `(journalId, userId, lobbyId, index, promptOpt, logEntries, info)`), using an injected/fake `sendEmail` callback instead of a real Resend call and an in-memory DB. Cover, for both `promptOpt = None` and `promptOpt = Some(...)`:
> - The actual-send branch (user found, secret found, email present) — fake `sendEmail` is called with the right arguments.
> - Skip branch: user found, secret found, no email — the right skip-log line fires, `sendEmail` is not called.
> - Skip branch: user found, no secret — right skip-log line, no send.
> - Skip branch: no user record — right skip-log line, no send.
>
> Confirm these fail (red) since the helper doesn't exist. Implement it using `NotifyDecision.shouldNotify` from Prompt 2 internally. Get green. Do not delete the old duplicated route blocks yet — this helper exists alongside them for now. Stop once green and commit.

---

### Prompt 5 — TDD the route contracts — ✅ COMPLETED

> **Done:** `notify-turn` and `notify-wait` extracted from `GoodGame.main` into `good-game/NotifyRoutes.scala` (`class NotifyRoutes(db, baseUrl, internalKey, sendEmail)`), both now just parse their wire format and call `TurnNotifier.dispatch` — the old duplicated `NotifiedTurns`-check / user-lookup / three-case skip-match blocks are deleted from `GoodGame.scala`. `notify-wait` now reads an optional `PROMPT <text>` line (`promptOpt = Some(text)`); `notify-turn` is unchanged on the wire (`promptOpt = None`, index-only). `main` mounts `~ notifyRoutes.route` and passes an adapter onto `EmailSender.sendTurnEmail`.
>
> **Deviation from the plan's letter:** used `akka.http.scaladsl.server.Route.toFunction` (the primitive `akka-http-testkit` itself wraps) rather than adding the `akka-http-testkit` dep + a munit/ScalaTest bridge — same route-level coverage, no new dependency, no test-framework glue. Also did the characterization + rewire in one pass rather than two commits, because the route bodies couldn't be lifted into a test harness *without* being the rewire.
>
> `good-game/src/test/scala/NotifyRoutesTest.scala` (real in-mem HSQLDB, fake `sendEmail`, prod's `NoSuchElementException`→403 handler applied): notify-turn sends once / dedups on repeat / re-sends on higher index / 403 on bad secret; notify-wait 403 on wrong key / PROMPT drives same-index multi-step re-notify / higher-index recycled prompt still sends / missing PROMPT still works. `sbt test` 25/25, `sbt compile` clean, and a real boot against a fresh file DB prints `Started server.` with the migration running clean and the routes returning 403s as expected.

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 5, following the shared helper landed in Prompt 4.
>
> Using `akka-http-testkit`, write route-level tests first for both `notify-turn` and `notify-wait` as they currently exist (before you change them), pinning down: `notify-turn`'s existing index-only behavior (same contract, no prompt line in its wire format — it's called by the vendored client and must never require one), and `notify-wait`'s current behavior. These should pass immediately (green) since they're describing current behavior — this is a characterization/safety-net step, not new red-green.
>
> Then add new tests for the target behavior: `notify-wait`'s POST body now accepts and uses a `PROMPT <text>` line, and both routes route through the Prompt-4 shared helper instead of their own inline logic. These new ones will fail until you actually rewire the routes. Rewire both routes to call the shared helper (`notify-turn` passing `promptOpt = None`, `notify-wait` passing `promptOpt = Some(promptText)`), delete the old duplicated inline blocks, get everything green, `sbt compile` clean. Stop and commit.

---

### Prompt 6 — TDD `watch.js`'s unconditional reporting

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 6, following the server-side rewiring landed in Prompt 5.
>
> Write tests first, in `good-game/watcher/test/`, using a stubbed `fetch` that records call arguments. Assert that for every currently-waiting letter on every poll, `notifyWait` is called unconditionally (not gated on any local cache) with the current prompt text included in the request body — extract the per-letter loop body out of `pollOnce` into a small testable function first if that's what unconditional calling requires to test cleanly. These should fail against the current `lastSeen`-gated implementation.
>
> Then delete the `lastSeen` map and its gating condition entirely from `watch.js`, make `notifyWait` fire unconditionally every poll for every currently-waiting letter (same shape as `notifyReminder` already works), and update its POST body to include the `PROMPT <text>` line the Prompt-5 server change expects. Get the tests green, run `node --check watch.js`. Stop and commit.

---

### Prompt 7 — final integration pass and deploy

> Read `NOTIFICATION_DEDUP_PLAN.md` for context; this is Prompt 7, the last step, following Prompts 1-6 all landed and green.
>
> Run the full suite (`sbt test` and `node --test good-game/watcher/test/`) and confirm everything is green together, not just step-by-step. Run through the "Regression test matrix" table in this plan and confirm each row has a corresponding passing test — add any that are missing. Then follow the plan's "Deploy checklist" section exactly: verify the migration DDL against HSQLDB one more time, deploy off-peak, confirm `docker logs hrf-arcs` shows `Started server.` and stays stable for several minutes, and confirm via production logs that a genuinely new transition with recycled prompt text fires `notifying` while a stable repeated-poll wait stays silent. Only then is this refactor done.

## Regression test matrix (explicit bug → test)

| Bug hit this session | Test |
|---|---|
| Recurring prompt text ("Yellow leads") suppresses real notifications | `NotifyDecisionTest`: higher-index-same-prompt case |
| 152-poll stuck-state correctly silent | `NotifyDecisionTest`: same-index-same-prompt case |
| Multi-step turn (negotiate → rearrange) | `NotifyDecisionTest`: same-index-different-prompt case |
| Silent skip on missing email/secret | Shared-dispatch-helper test, all three skip branches |
| `createIfNotExists` crash-loop | Migration test run twice for idempotency |
| `notify-turn`'s existing contract | `promptOpt = None` cases in `NotifyDecisionTest` + route test |

Two bugs from earlier in the session are outside this refactor's direct scope but share the same new test harness cheaply — worth a follow-up, not blocking: `parseLobby` first-vs-last-line (`good-game/GoodGame.scala:201`) and the `AccessRights` 500→403 handler.

## Deploy checklist (lessons already paid for this session)
- Verify the exact `ALTER TABLE` DDL against HSQLDB manually before it ever reaches prod — the specific step skipped last time.
- Deploy off-peak if possible; confirm `docker logs hrf-arcs` shows `Started server.` and stays stable for several minutes before considering it done, same as every fix this session.
- After deploy, confirm via logs: a genuinely new transition fires `notifying`, and repeated polls of the same state don't.

## Definition of done
- `sbt test` and `node --test` both green in CI-equivalent local run.
- Both duplicated route blocks replaced by one shared helper.
- `watch.js`'s `lastSeen` map is gone.
- All regression-matrix cases above pass and are checked into the repo, not just verified manually.
- Production log evidence: a new-round transition with recycled prompt text correctly notifies; a stable multi-poll wait correctly doesn't.
