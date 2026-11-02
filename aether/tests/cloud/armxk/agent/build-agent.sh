#!/bin/bash
# build-agent.sh <checkout-root> <aether-node.jar> <out.jar>
#
# Builds the arm X counting agent (m1probe.jar): M1ProbeAgent plus the M-1 instrument compiled from the
# checkout's OWN aether/ember/src/test/.../M1Instrument.java, so the cross-VM counts use the same source as
# the in-JVM harness (a change to the instrument reaches both). Compiled against the node jar only; nothing
# here enters the Maven reactor or any production artifact.
set -euo pipefail
ROOT=$1; NODE_JAR=$2; OUT=$3
AGENT_SRC="$(cd "$(dirname "$0")" && pwd)/org/pragmatica/aether/ember/M1ProbeAgent.java"
INSTRUMENT_SRC="$ROOT/aether/ember/src/test/java/org/pragmatica/aether/ember/M1Instrument.java"
[ -f "$INSTRUMENT_SRC" ] || { echo "build-agent: no M1Instrument.java at $INSTRUMENT_SRC" >&2; exit 2; }
[ -f "$NODE_JAR" ] || { echo "build-agent: no node jar at $NODE_JAR" >&2; exit 2; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
javac --release 25 -nowarn -cp "$NODE_JAR" -d "$WORK/classes" "$INSTRUMENT_SRC" "$AGENT_SRC"
printf 'Premain-Class: org.pragmatica.aether.ember.M1ProbeAgent\n' > "$WORK/manifest.txt"
jar cfm "$OUT" "$WORK/manifest.txt" -C "$WORK/classes" .
# Record which instrument source went in: the evidence names it.
sha256sum "$INSTRUMENT_SRC" "$AGENT_SRC" | sed "s#$ROOT/##" > "$OUT.sources.sha256"
echo "build-agent: wrote $OUT ($(wc -c < "$OUT") bytes)"
