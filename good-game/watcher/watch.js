// Server-side turn-wait watcher.
//
// We don't have Scala source for the client past 0.8.140, so we can't hook
// into its game logic client-side anymore to know "whose turn is it". This
// watches games the same way a human would: load the spectator view in a
// headless browser and read the "<Faction> <does something>" prompt banner
// that the client already renders for every wait-for-input state.

const { chromium } = require('playwright');

const PORT = process.env.ARCS_PORT || '7070';
const BASE = `http://localhost:${PORT}`;
// The server templates <base href> to the public ARCS_URL regardless of how
// the page itself was reached, and gates every /hrf/ static asset (JS,
// fonts, images) on the request's Referer matching that same public URL
// (see the pathPrefix("hrf") check in GoodGame.scala - this is the same
// mechanism we relied on for font access control earlier). Navigating via
// localhost would make every asset request send a non-matching Referer and
// silently 404, so use the public URL for page loads even though the API
// calls below use localhost directly.
const PUBLIC_BASE = process.env.ARCS_URL || BASE;
const KEY = process.env.INTERNAL_API_KEY || '';
const POLL_INTERVAL_MS = parseInt(process.env.WATCHER_POLL_MS || '45000', 10);

if (!KEY) {
    console.log('[watcher] INTERNAL_API_KEY not set, watcher disabled');
    process.exit(0);
}

const lastSeen = new Map(); // gameJournalId -> letter

function log(...args) {
    console.log('[watcher]', new Date().toISOString(), ...args);
}

async function fetchText(url, opts) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
        const res = await fetch(url, { ...opts, signal: controller.signal });
        if (!res.ok) throw new Error(`${url} -> ${res.status}`);
        return await res.text();
    } finally {
        clearTimeout(timer);
    }
}

async function listActiveGames() {
    const body = await fetchText(`${BASE}/internal/active-games/${KEY}`);
    return body.split('\n').filter(l => l.startsWith('GAME ')).map(l => {
        const [, gameJournalId, meta, spectateSecret] = l.split(' ');
        return { gameJournalId, meta, spectateSecret };
    });
}

function withTimeout(promise, ms, label) {
    let timer;
    const timeout = new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out after ${ms}ms: ${label}`)), ms);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function inspectGame(page, game) {
    // Headless Chromium reports pages as backgrounded, which throttles
    // timers - we hit this exact class of stall earlier with real browser
    // tabs too. Not confirmed load-bearing here on its own (the actual
    // fixes were the public-URL navigation and GPU flags below), but
    // cheap enough to keep as extra insurance.
    await page.addInitScript(() => {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        document.addEventListener('DOMContentLoaded', () => document.dispatchEvent(new Event('visibilitychange')));
    });

    page.on('console', (msg) => log('console:', msg.type(), msg.text().slice(0, 300)));
    page.on('pageerror', (err) => log('pageerror:', String(err).slice(0, 300)));

    const url = `${PUBLIC_BASE}/play/${game.meta}/${game.spectateSecret}`;
    log('goto', url);
    await withTimeout(page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 }), 20000, 'goto');
    log('goto done, waiting for render');

    // Cold loads (a game nobody's ever visited yet) can take a while longer
    // than a warm one to actually paint, so wait for real content instead
    // of guessing a fixed delay - but don't fail the poll if it never shows
    // up, just proceed and report whatever's there (possibly nothing). The
    // wait-prompt banner in particular can render well after the log pane
    // does on more complex/further-along games, so wait for that
    // specifically rather than treating "log has entries" as good enough.
    try {
        await page.waitForFunction(
            () => Array.from(document.querySelectorAll('.xlo-fullwidth')).some(b => b.textContent.trim()),
            { timeout: 25000 }
        );
    } catch (e) {
        log('waitForFunction gave up:', e.message.slice(0, 150));
    }

    const diag = await page.evaluate(() => ({
        fontsStatus: document.fonts ? document.fonts.status : 'no document.fonts',
        scriptTag: !!document.getElementById('script'),
        rootLen: document.getElementById('root-attachment-point') ? document.getElementById('root-attachment-point').innerHTML.length : -1,
        bodyLen: document.body.innerHTML.length,
        title: document.title,
    })).catch((e) => ({ error: String(e) }));
    log('diag', JSON.stringify(diag));
    log('evaluating');

    const result = await withTimeout(page.evaluate(() => {
        // Usually one faction is waiting ("Yellow chooses Fate"), but
        // sometimes several are at once ("Waiting for Yellow, White") - grab
        // every faction-colored span in the prompt banner(s), not just the
        // first, so nobody who's actually waiting gets skipped.
        const factions = new Set();
        const banners = Array.from(document.querySelectorAll('.xlo-fullwidth'));
        const bannerDebug = [];
        for (const banner of banners) {
            const colorSpans = Array.from(banner.querySelectorAll('[class^="arcs-"], [class*=" arcs-"]'));
            bannerDebug.push({ text: banner.textContent.trim(), spans: colorSpans.map(s => s.className) });
            for (const colorSpan of colorSpans) {
                if (/^arcs-(red|white|blue|yellow)$/.test(colorSpan.className.trim())) {
                    const text = colorSpan.textContent.trim();
                    if (text) factions.add(text[0].toUpperCase());
                }
            }
        }
        window.__bannerDebug = bannerDebug;

        // The client already renders each journal entry into readable prose
        // in the visible log pane (e.g. "Yellow randomly took initiative"),
        // grouped under a title="Action #N" span. That's much better output
        // than we could get re-formatting the raw serialized actions
        // ourselves, so just read what's already there.
        const container = document.querySelector('.hrf-inner---hrf-log');
        const logEntries = container
            ? Array.from(container.querySelectorAll('span[title^="Action #"]')).map(span => {
                const m = span.getAttribute('title').match(/Action #(\d+)/);
                return {
                    num: m ? parseInt(m[1], 10) : -1,
                    text: span.textContent.replace(/\s+/g, ' ').trim(),
                };
            }).filter(e => e.num >= 0 && e.text && !/^\.+$/.test(e.text))
            : [];

        return { letters: Array.from(factions), logEntries, bannerDebug: window.__bannerDebug };
    }), 15000, 'evaluate');
    log('evaluate done, letters =', result.letters, 'log entries =', result.logEntries.length);
    if (result.letters.length === 0 && result.bannerDebug.some(b => b.text))
        log('banner debug', JSON.stringify(result.bannerDebug.filter(b => b.text)));

    return {
        letters: result.letters,
        maxIndex: result.logEntries.reduce((m, e) => Math.max(m, e.num), 0),
        logEntries: result.logEntries,
    };
}

async function notifyWait(gameJournalId, letter, maxIndex, logEntries) {
    log('notifying', gameJournalId, letter, 'up to', maxIndex);
    // The server independently dedupes by (gameJournalId, targetUser, index)
    // via NotifiedTurns, so re-notifying the same actual state is harmless -
    // this is not the only thing standing between a player and a duplicate
    // email.
    const body = [`INDEX ${maxIndex}`]
        .concat(logEntries.map(e => `LOG ${e.num}\t${e.text}`))
        .join('\n');
    await fetchText(`${BASE}/internal/notify-wait/${KEY}/${gameJournalId}/${letter}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body,
    });
}

async function pollOnce(context) {
    let games;
    try {
        games = await listActiveGames();
    } catch (e) {
        log('failed to list active games:', e.message);
        return;
    }

    log('polling', games.length, 'game(s)');

    for (const game of games) {
        log('opening page for', game.gameJournalId);
        const page = await context.newPage();
        try {
            const { letters, maxIndex, logEntries } = await inspectGame(page, game);
            const previous = lastSeen.get(game.gameJournalId) || new Set();
            for (const letter of letters) {
                if (!previous.has(letter))
                    await notifyWait(game.gameJournalId, letter, maxIndex, logEntries);
            }
            // Only update our local view of "who's waiting" on a real read -
            // an empty result usually just means the page hadn't finished
            // rendering yet, not that nobody's waiting anymore.
            if (letters.length > 0) lastSeen.set(game.gameJournalId, new Set(letters));
        } catch (e) {
            log('error polling game', game.gameJournalId, e.message);
        } finally {
            try {
                await withTimeout(page.close(), 5000, 'page.close');
            } catch (e) {
                log('error closing page', e.message);
            }
        }
    }

    log('poll complete');
}

async function main() {
    log('starting, polling every', POLL_INTERVAL_MS, 'ms');
    // This game likely leans on canvas/WebGL for the map, which headless
    // Chromium can silently fail to initialize without these flags.
    const browser = await chromium.launch({
        headless: true,
        args: ['--no-sandbox', '--disable-gpu', '--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'],
    });
    // Not confirmed load-bearing either, but a normal-looking desktop UA
    // instead of the default "HeadlessChrome" one is cheap insurance
    // against anything that behaves differently for automated browsers.
    const context = await browser.newContext({
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    });

    while (true) {
        await pollOnce(context);
        await new Promise(r => setTimeout(r, POLL_INTERVAL_MS));
    }
}

main().catch(e => {
    console.error('[watcher] fatal:', e);
    process.exit(1);
});
