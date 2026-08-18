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

async function detectWaitingFaction(page, game) {
    const url = `${BASE}/play/${game.meta}/${game.spectateSecret}`;
    log('goto', url);
    await withTimeout(page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 }), 20000, 'goto');
    log('goto done, waiting for render');
    await withTimeout(page.waitForTimeout(4000), 10000, 'waitForTimeout');
    log('evaluating');

    const faction = await withTimeout(page.evaluate(() => {
        const banners = Array.from(document.querySelectorAll('.xlo-fullwidth'));
        for (const banner of banners) {
            const colorSpan = banner.querySelector('[class^="arcs-"], [class*=" arcs-"]');
            if (colorSpan && /^arcs-(red|white|blue|yellow)$/.test(colorSpan.className.trim())) {
                const text = colorSpan.textContent.trim();
                if (text) return text;
            }
        }
        return null;
    }), 15000, 'evaluate');
    log('evaluate done, faction =', faction);

    return faction ? faction[0].toUpperCase() : null;
}

async function notifyWait(gameJournalId, letter) {
    log('notifying', gameJournalId, letter);
    await fetchText(`${BASE}/internal/notify-wait/${KEY}/${gameJournalId}/${letter}`, { method: 'POST' });
}

async function pollOnce(browser) {
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
        const page = await browser.newPage();
        try {
            const letter = await detectWaitingFaction(page, game);
            if (letter && lastSeen.get(game.gameJournalId) !== letter) {
                lastSeen.set(game.gameJournalId, letter);
                await notifyWait(game.gameJournalId, letter);
            }
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
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });

    while (true) {
        await pollOnce(browser);
        await new Promise(r => setTimeout(r, POLL_INTERVAL_MS));
    }
}

main().catch(e => {
    console.error('[watcher] fatal:', e);
    process.exit(1);
});
