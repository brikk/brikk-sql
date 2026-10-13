#!/usr/bin/env bash
# Starts (up) or removes (down) the ClickHouse server brikk-sql-verify-clickhouse's server tests use:
# clickhouse/clickhouse-server on a task-only docker network, published on 127.0.0.1 only.
#   tools/clickhouse-verify-server.sh up && BRIKK_CLICKHOUSE_URL=jdbc:clickhouse://127.0.0.1:18123/default ./kotlin test -m brikk-sql-verify-clickhouse
#   BRIKK_CLICKHOUSE_URL=... BRIKK_CLICKHOUSE_DRIVER=$PWD/build/clickhouse-driver/clickhouse-jdbc-0.9.8-all.jar  (IDE end-to-end test)
#   tools/clickhouse-verify-server.sh down
set -euo pipefail
IMAGE="${BRIKK_CLICKHOUSE_IMAGE:-clickhouse/clickhouse-server:26.9}"
NAME=brikk-ch-verify
NETWORK=brikk-ch-verify-task
DRIVER_DIR=build/clickhouse-driver
DRIVER=clickhouse-jdbc-0.9.8-all.jar
case "${1:-}" in
  up)
    # The ClickHouse plugin's driver with its dependencies, for the IDE end-to-end test's data source.
    mkdir -p "$DRIVER_DIR"
    [ -f "$DRIVER_DIR/$DRIVER" ] || curl -sfo "$DRIVER_DIR/$DRIVER" "https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/0.9.8/$DRIVER"
    # The one dependency the all-in-one jar leaves out.
    [ -f "$DRIVER_DIR/slf4j-api-2.0.7.jar" ] || curl -sfo "$DRIVER_DIR/slf4j-api-2.0.7.jar" "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar"
    docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
    docker inspect "$NAME" >/dev/null 2>&1 || docker run -d --rm --name "$NAME" --network "$NETWORK" -p 127.0.0.1:18123:8123 \
      -e CLICKHOUSE_SKIP_USER_SETUP=1 "$IMAGE" >/dev/null
    for _ in $(seq 60); do
      curl -sf 'http://127.0.0.1:18123/?query=SELECT%201' >/dev/null && exit 0
      sleep 1
    done
    echo "ClickHouse did not start" >&2
    exit 1
    ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    ;;
  *)
    echo "usage: $0 up|down" >&2
    exit 2
    ;;
esac
