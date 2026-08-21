### HRF Boardgames App

#### The Arcs client is permanently frozen

Production serves a vendored, pre-compiled client bundle (`haunt-roll-fail/vendor/hrf-fastopt-0.8.157.js`) that the `.scala` source in this repo does not match and cannot rebuild — that source only goes up to an earlier version, and the matching 0.8.157 source is confirmed unrecoverable. **This is permanent, not a gap waiting to be filled.**

In practice: `haunt-roll-fail/*.scala` has zero effect on what Arcs players see, no matter what you change there or how you build it. The `sbt fastOptJS` step below still works (it's needed for the other nine games under `haunt-roll-fail/`, none of which are wired up server-side yet), but for Arcs specifically its output is discarded and overwritten by the vendored file at Docker build time — see the comment in `Dockerfile`. Any change to Arcs itself (game logic, layout, interaction, in-game copy, anything beyond page-level CSS/HTML) is not possible with what's in this repo. The only things that are real, editable, and actually affect the deployed Arcs client are `haunt-roll-fail/index.html` (the page shell) and `good-game/GoodGame.scala` (the server, including the HTML templates it serves).

#### Building
In **scala-js-dom-reduced** dir
```
sbt publishLocal
```

In **haunt-roll-fail** dir
```
sbt fastOptJS
```

In **good-game** dir
```
sbt "run create ../good-game-database ../haunt-roll-fail http://localhost:7070 http://localhost:7070/hrf/ 7070"
sbt "run run ../good-game-database ../haunt-roll-fail http://localhost:7070 http://localhost:7070/hrf/ 7070"
```

#### Running with Docker
Build and run the server in a container (creates the database on first run and persists it in a volume):
```
docker compose up --build
```

The server listens on port 7070. `ARCS_URL`, `ARCS_CDN`, and `ARCS_PORT` are configured in `docker-compose.yml`.

To rebuild after code changes and restart the running container (database volume is preserved):
```
docker compose up --build -d
```
