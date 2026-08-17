#!/bin/bash
set -e

cd /app/good-game

if [ ! -f /data/good-game-database.script ]; then
    echo "No database found, creating..."
    sbt "run create /data/good-game-database ../haunt-roll-fail $ARCS_URL $ARCS_CDN $ARCS_PORT"
fi

exec sbt "run run /data/good-game-database ../haunt-roll-fail $ARCS_URL $ARCS_CDN $ARCS_PORT"
