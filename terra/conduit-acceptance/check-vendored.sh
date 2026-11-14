#!/usr/bin/env bash
# Drift check for the vendored upstream suite (#3236): every file in spec/hurl must match the SHA-256 recorded in spec/UPSTREAM next to the
# upstream commit it was copied from, and the file set must be identical (no extra, no missing file). Exits 1 on any drift, 2 on an unreadable
# manifest. Run by run-acceptance.sh before anything starts; also runnable alone.
#   terra/conduit-acceptance/check-vendored.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec python3 -I - "$HERE/spec" <<'PY'
import hashlib, os, sys
spec = sys.argv[1]
manifest = os.path.join(spec, "UPSTREAM")
try:
    lines = [l.split() for l in open(manifest) if l.strip() and not l.startswith("#")]
except OSError as e:
    print("VENDORED ERROR: cannot read %s: %s" % (manifest, e), file=sys.stderr); sys.exit(2)
recorded = {l[1]: l[0] for l in lines if len(l) == 2 and len(l[0]) == 64}
if not recorded:
    print("VENDORED ERROR: %s lists no files" % manifest, file=sys.stderr); sys.exit(2)
actual = sorted(f for f in os.listdir(os.path.join(spec, "hurl")))
bad = []
for name in sorted(set(recorded) | set(actual)):
    path = os.path.join(spec, "hurl", name)
    if name not in recorded: bad.append("unrecorded file " + name)
    elif not os.path.isfile(path): bad.append("missing file " + name)
    elif hashlib.sha256(open(path, "rb").read()).hexdigest() != recorded[name]: bad.append("content differs from the recorded upstream hash: " + name)
if bad:
    print("VENDORED DRIFT: spec/hurl no longer matches the upstream commit recorded in spec/UPSTREAM:", file=sys.stderr)
    for b in bad: print("  " + b, file=sys.stderr)
    sys.exit(1)
print("vendored suite matches upstream (%d files)" % len(recorded))
PY
