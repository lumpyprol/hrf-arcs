// Prompt 6 of NOTIFICATION_DEDUP_PLAN.md: the watcher reports raw observed
// state unconditionally every poll - no local lastSeen cache - and includes
// the live prompt text so the server can make the dedup decision.
const test = require('node:test');
const assert = require('node:assert');

process.env.INTERNAL_API_KEY = 'testkey';
process.env.ARCS_PORT = '7070';

const { buildWaitBody, reportWaiting } = require('../watch.js');

function stubFetch() {
  const calls = [];
  global.fetch = async (url, opts) => {
    calls.push({ url, method: (opts && opts.method) || 'GET', body: opts && opts.body });
    return { ok: true, status: 200, text: async () => '' };
  };
  return calls;
}

const attempt = {
  letters: ['B', 'Y'],
  letterToPrompt: { B: 'Blue leads', Y: 'Yellow leads' },
  maxIndex: 42,
  logEntries: [{ num: 3, html: '<i>something</i>' }],
};

test('buildWaitBody includes an INDEX line, a PROMPT line, and LOG lines', () => {
  const body = buildWaitBody(42, 'Blue leads', [{ num: 3, html: '<i>x</i>' }]);
  assert.deepStrictEqual(body.split('\n'), [
    'INDEX 42',
    'PROMPT Blue leads',
    'LOG 3\t<i>x</i>',
  ]);
});

test('buildWaitBody collapses whitespace and tolerates an empty prompt', () => {
  assert.strictEqual(buildWaitBody(1, '  Blue   leads\n', []), 'INDEX 1\nPROMPT Blue leads');
  assert.strictEqual(buildWaitBody(1, '', []), 'INDEX 1\nPROMPT ');
  assert.strictEqual(buildWaitBody(1, undefined, []), 'INDEX 1\nPROMPT ');
});

test('reportWaiting calls notify-wait once per waiting letter, with the prompt text', async () => {
  const calls = stubFetch();
  await reportWaiting({ gameJournalId: 'g1' }, attempt);

  const waits = calls.filter(c => c.url.includes('/internal/notify-wait/'));
  assert.strictEqual(waits.length, 2);

  const b = waits.find(c => c.url.endsWith('/g1/B'));
  assert.strictEqual(b.url, 'http://localhost:7070/internal/notify-wait/testkey/g1/B');
  assert.strictEqual(b.method, 'POST');
  assert.ok(b.body.includes('PROMPT Blue leads'), b.body);
  assert.ok(b.body.includes('INDEX 42'), b.body);
  assert.ok(b.body.includes('LOG 3\t<i>something</i>'), b.body);

  const y = waits.find(c => c.url.endsWith('/g1/Y'));
  assert.ok(y.body.includes('PROMPT Yellow leads'), y.body);

  // and a reminder ping per letter
  assert.strictEqual(calls.filter(c => c.url.includes('/internal/notify-reminder/')).length, 2);
});

test('reportWaiting is unconditional: an identical second poll fires notify-wait again', async () => {
  const calls = stubFetch();
  await reportWaiting({ gameJournalId: 'g1' }, attempt);
  await reportWaiting({ gameJournalId: 'g1' }, attempt);

  assert.strictEqual(calls.filter(c => c.url.includes('/internal/notify-wait/')).length, 4);
});

test('watch.js exposes no lastSeen-style local dedup cache', () => {
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'watch.js'), 'utf8');
  assert.ok(!/lastSeen/.test(src), 'lastSeen map should be gone');
});
