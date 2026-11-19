#!/bin/bash
# test-fixture-contract-preflight.sh — pins lib/verify-blueprint-fixtures.sh against a fixture that is
# PRESENT but compiled before the current route-security contract (#2048).
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-fixture-contract-preflight.sh
#
# The preflight used to check only that each referenced coordinate had a jar. A slice jar built by an older
# slice processor passed that check and then failed at deploy (`routeSecurityContract=0, current N`).
#
#   C1  a repository where every fixture is current passes (rc 0) — the refusal below is not a blanket refusal;
#   C2  THE DEFECT: one fixture whose *Routes class has no routeSecurityContract() (a pre-contract jar) is
#       refused with rc 1;
#   C3  the refusal names the stale artifact, its stamp and how to rebuild it;
#   C4  a fixture stamped below the tree's current contract is refused as well (the stamp, not only the
#       absent method, is compared);
#   C5  a stamp above the tree's value is not refused (the preflight refuses < current only);
#   C6  a stale jar directly under a coordinate (not via its blueprint's slice list) is refused too.
# Fixtures are compiled here with javac from a stub source: the preflight reads the same thing the runtime's
# factory exposes, a `routeSecurityContract()` method on the class listed in META-INF/services.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PREFLIGHT="${INTEG_DIR}/lib/verify-blueprint-fixtures.sh"
FACTORY_SRC="${INTEG_DIR}/../../http-routing-adapter/src/main/java/org/pragmatica/aether/http/adapter/SliceRouterFactory.java"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

for tool in javac jar; do
    command -v "$tool" > /dev/null 2>&1 || { echo "  FAIL  '${tool}' is required to build the fixture jars"; exit 2; }
done
CURRENT=$(sed -n 's/.*int ROUTE_SECURITY_CONTRACT = \([0-9][0-9]*\);.*/\1/p' "$FACTORY_SRC" | head -1)
[ -n "$CURRENT" ] || { echo "  FAIL  cannot read the current contract from ${FACTORY_SRC}"; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# make_jar <jarfile> <stamp|none>: a slice jar whose registered factory class stamps <stamp>, or has no stamp method.
make_jar() {
    local jarfile="$1" stamp="$2" b
    b="$(mktemp -d "${WORK}/jar.XXXXXX")"
    mkdir -p "$b/src/fx" "$b/out/META-INF/services"
    if [ "$stamp" = "none" ]; then
        echo 'package fx; public final class FxRoutes { }' > "$b/src/fx/FxRoutes.java"
    else
        echo "package fx; public final class FxRoutes { public int routeSecurityContract() { return ${stamp}; } }" > "$b/src/fx/FxRoutes.java"
    fi
    javac -d "$b/out" "$b/src/fx/FxRoutes.java" || { echo "javac failed"; exit 2; }
    echo "fx.FxRoutes" > "$b/out/META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory"
    mkdir -p "$(dirname "$jarfile")"
    jar cf "$jarfile" -C "$b/out" . || { echo "jar failed"; exit 2; }
}

# make_blueprint <jarfile> <slice artifact coordinate>: a blueprint jar listing one slice, as jbct:generate-blueprint writes it.
make_blueprint() {
    local jarfile="$1" b
    b="$(mktemp -d "${WORK}/bp.XXXXXX")"
    mkdir -p "$b/META-INF"
    printf '[[slices]]\nartifact = "%s"\ninstances = 1\n' "$2" > "$b/META-INF/blueprint.toml"
    jar cf "$jarfile" -C "$b" META-INF || { echo "jar failed"; exit 2; }
}

# build_repo <dir> <stamp>: every coordinate the suites reference, as the real build leaves it: a plain jar and
# a blueprint jar under the coordinate, and the slice artifact <artifact>-fx-slice the blueprint lists; all stamped <stamp>.
build_repo() {
    local repo="$1" stamp="$2" coord group rest artifact version dir sdir
    while IFS= read -r coord; do
        [ -n "$coord" ] || continue
        group="${coord%%:*}"; rest="${coord#*:}"; artifact="${rest%%:*}"; version="${rest##*:}"
        dir="${repo}/${group//.//}/${artifact}/${version}"
        make_jar "${dir}/${artifact}-${version}.jar" "$stamp"
        sdir="${repo}/${group//.//}/${artifact}-fx-slice/${version}"
        make_jar "${sdir}/${artifact}-fx-slice-${version}.jar" "$stamp"
        make_blueprint "${dir}/${artifact}-${version}-blueprint.jar" "${group}:${artifact}-fx-slice:${version}"
    done < <(grep -rhoE 'org\.pragmatica\.aether\.(example|test):[a-zA-Z0-9_-]+:[0-9]+\.[0-9]+\.[0-9]+' \
                 "${INTEG_DIR}/suites" "${INTEG_DIR}/lib" 2> /dev/null | sort -u)
}

# run_preflight <repo>: output in $OUT, rc in $RC.
run_preflight() {
    OUT="$(AETHER_JAVA_OPTS="-Dmaven.repo.local=$1" bash "$PREFLIGHT" 2>&1)"; RC=$?
}

REPO="${WORK}/repo"
build_repo "$REPO" "$CURRENT"
# The stale fixture is a slice artifact reached only through a blueprint's slice list: the shape of the observed defect.
FIRST="$(find "$REPO" -name '*-fx-slice-*.jar' | sort | head -1)"
[ -n "$FIRST" ] || { echo "  FAIL  no fixture jars were built, the test examined nothing"; exit 2; }
STALE_ARTIFACT="$(basename "$(dirname "$(dirname "$FIRST")")")"

run_preflight "$REPO"
if [ "$RC" -eq 0 ]; then ok "C1 all-current repository passes"; else fail "C1 all-current repository refused (rc ${RC}): ${OUT}"; fi

make_jar "$FIRST" none
run_preflight "$REPO"
if [ "$RC" -eq 1 ]; then ok "C2 pre-contract fixture refused (rc 1)"; else fail "C2 pre-contract fixture NOT refused (rc ${RC}); the preflight checks presence only"; fi
if echo "$OUT" | grep -q "$STALE_ARTIFACT" && echo "$OUT" | grep -q "routeSecurityContract=0" && echo "$OUT" | grep -q "current ${CURRENT}" && echo "$OUT" | grep -q "clean install"; then
    ok "C3 refusal names ${STALE_ARTIFACT}, the stamp, the current contract and the rebuild command"
else
    fail "C3 refusal lacks artifact/stamp/current/rebuild: ${OUT}"
fi

if [ "$CURRENT" -gt 0 ]; then
    make_jar "$FIRST" "$((CURRENT - 1))"
    run_preflight "$REPO"
    if [ "$RC" -eq 1 ] && echo "$OUT" | grep -q "routeSecurityContract=$((CURRENT - 1))"; then ok "C4 fixture stamped below current refused"; else fail "C4 below-current stamp not refused (rc ${RC}): ${OUT}"; fi
fi

make_jar "$FIRST" "$((CURRENT + 1))"
run_preflight "$REPO"
if [ "$RC" -eq 0 ]; then ok "C5 fixture stamped above current accepted"; else fail "C5 above-current stamp refused (rc ${RC}): ${OUT}"; fi

# C6: the coordinate's own plain jar is read too (the blueprint module's jar carries a factory in the real build).
build_repo "${WORK}/repo6" "$CURRENT"
OWN6="$(find "${WORK}/repo6" -name '*[0-9].jar' ! -name '*-fx-slice-*' ! -name '*-blueprint.jar' | sort | head -1)"
make_jar "$OWN6" none
run_preflight "${WORK}/repo6"
if [ "$RC" -eq 1 ] && echo "$OUT" | grep -q "$(basename "$OWN6")"; then ok "C6 stale coordinate jar refused, named"; else fail "C6 stale coordinate jar not refused (rc ${RC}): ${OUT}"; fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
