// Prompt 1 of NOTIFICATION_DEDUP_PLAN.md: prove the node:test harness runs.
// Real coverage of the watcher's unconditional reporting lands in Prompt 6.
const test = require('node:test');
const assert = require('node:assert');

test('test harness is wired', () => {
  assert.strictEqual(1, 1);
});
