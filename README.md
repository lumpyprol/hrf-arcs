### HRF Boardgames App

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
