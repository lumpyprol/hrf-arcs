# HRF Arcs

## Architecture

- `good-game/GoodGame.scala` — the Akka HTTP/Slick/HSQLDB server. This is the actively-developed Scala source; safe to edit.
- `good-game/watcher/watch.js` — Playwright headless-browser watcher. Polls live games, reads the client's own rendered "whose turn" banner and log text (there's no server-side hook into game state for this — see comments at the top of the file), and calls the server's internal `/internal/notify-wait` route to trigger turn-notification emails.
- `haunt-roll-fail/` — the Scala.js game client source tree. See below — **do not treat this as buildable or editable in a way that affects production.**
- `scala-js-dom-reduced/` — a vendored dependency, published locally via `sbt publishLocal` per the README before the client can build at all.

## The client bundle is vendored — do not try to recompile it

Production serves `haunt-roll-fail/vendor/hrf-fastopt-0.8.157.js`, copied in verbatim. It is **not** built from `haunt-roll-fail/*.scala` — that local source tree only goes up to version 0.8.140 and does not match what's live. The Dockerfile spells this out and enforces it:

```dockerfile
# The haunt-roll-fail Scala source we have only goes up to 0.8.140; the
# client bundle actually served is vendored from hrf.im's 0.8.157 build
# (see haunt-roll-fail/vendor/) since we don't have source for it.
RUN mkdir -p /app/haunt-roll-fail/target/scala-2.13 \
    && cp /app/haunt-roll-fail/vendor/hrf-fastopt-0.8.157.js /app/haunt-roll-fail/target/scala-2.13/hrf-fastopt.js
```

This runs unconditionally, after `sbt compile`, and clobbers whatever `sbt fastOptJS` would have produced.

**Consequences — please follow these:**

- **Never run `sbt fastOptJS`** (or any other attempt to recompile the client) expecting it to change what's served. Even a successful compile is discarded by the Docker build in favor of the vendored 0.8.157 bundle.
- **Editing `.scala` files under `haunt-roll-fail/`** (styles, layout, CSS-in-Scala, components, etc.) **has zero effect on production.** If you find yourself about to change something in there to fix a visual/CSS issue, stop — that's not the right file.
- For CSS/visual/UI changes to the client: look at `haunt-roll-fail/index.html` first — it's real and unvendored, served as-is (inline `<style>`/`<script>` blocks live there), or at HTML templates in `good-game/GoodGame.scala`. If the requested change genuinely requires touching the client's own rendering logic (not just page-level HTML/CSS), say so explicitly and check with the user before attempting anything — the source needed to do that safely may not exist in this repo.

## Deploying

- `docker compose build && docker compose up -d` rebuilds and restarts the `hrf-arcs` container — this targets the real production server (`arcs.lumpy-arcs.com`), not a sandbox. Treat it accordingly for anything beyond a trivial/low-risk change.
- Verify a deploy with `docker logs hrf-arcs` (watch for `sbt` loading cleanly and `Started server.`).

## Git workflow

- Prefer a feature branch + merge over committing straight to `main`. Direct-to-`main` commits are acceptable for live-incident fixes, but call out that deviation rather than treating it as the norm.

## Local clutter

- `good-game-database-test.*` files at the repo root (from local test runs) aren't gitignored — don't stage them by accident (`git add -A`/`git add .` will pick them up).
