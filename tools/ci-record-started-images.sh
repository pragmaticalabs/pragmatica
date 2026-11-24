#!/usr/bin/env bash
# Record every container image the Docker daemon is asked to create (#3646), so CI compares what the tests START with
# tools/ci-test-images.txt instead of trusting the source shapes the inventory gate greps.
#
#   ci-record-started-images.sh start FILE   begin recording (one image name per line, as the create request named it)
#   ci-record-started-images.sh stop FILE    stop recording; FILE then holds the images started since `start`
#
# Why the daemon: Testcontainers applies the mirror prefix from the environment (TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX), not from a
# per-module file, so a recorder inside the tests would need a test dependency in every pom; the daemon sees every create.
# `docker events` shows only events after it attached, so `start` returns once the stream is live (a control container
# create is not possible without an image: liveness is proven by the check, which fails on an empty record).
set -u
DOCKER="${CI_RECORD_DOCKER:-docker}"
mode="${1:-}"
file="${2:-}"
[ -n "$file" ] || { echo "usage: $0 start|stop FILE" >&2; exit 2; }

case "$mode" in
  start)
    : > "$file"
    nohup "$DOCKER" events --filter type=container --filter event=create --format '{{.Actor.Attributes.image}}' \
      >> "$file" 2> "$file.err" < /dev/null &
    echo $! > "$file.pid"
    sleep "${CI_RECORD_SETTLE:-2}"
    if ! kill -0 "$(cat "$file.pid")" 2>/dev/null; then
      echo "::error::the docker events recorder exited at start: $(cat "$file.err")"
      exit 1
    fi
    echo "record: recording container creates to $file (pid $(cat "$file.pid"))"
    ;;
  stop)
    [ -r "$file.pid" ] || { echo "::error::no recorder was started for $file"; exit 1; }
    pid="$(cat "$file.pid")"
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "::error::the docker events recorder (pid $pid) died during the run: $(cat "$file.err")"
      exit 1
    fi
    kill "$pid"
    echo "record: stopped; $(sort -u "$file" | grep -c .) distinct image(s) recorded"
    ;;
  *) echo "usage: $0 start|stop FILE" >&2; exit 2 ;;
esac
