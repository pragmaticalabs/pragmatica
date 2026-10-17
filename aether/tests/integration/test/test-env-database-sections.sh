#!/bin/bash
# test-env-database-sections.sh — static pin, no cluster. A blueprint that declares `[database.<name>]` needs a
# `[source.<src>.node_config.database.<name>]` section in EVERY cloud env file that can deploy it, because the
# connector resolves that datasource by exact section name: without it the schema migration fails at once
# ("Config section not found: database.testpersistence" -> SCHEMA_MIGRATION_FAILED) and the slice is held in LOADED
# forever. Cluster B's two env files lacked the section (13-edge-cases deploys test-persistence on B), which the
# #1763 restore gate was the first to notice. Each env section must also point at a database that run-tests.sh
# creates/resets, and cluster B's must not be cluster A's (suite 10 resets and baselines A's).
#   D1  every test-blueprint [database.<name>] has a node_config section in each cloud env file
#   D2  the database behind each such section is reset/created by run-tests.sh
#   D3  B env files point at a B-owned database, distinct from A's
#   D4  reset_cloud_pg_database accepts `<db>_testpersistence_b` and still refuses any other name (it DROPs)
#   ENV_DIR_UNDER_TEST=<dir> selects another env directory (mutation probes).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${INTEG_DIR}/../../.." && pwd)"
ENV_DIR="${ENV_DIR_UNDER_TEST:-${INTEG_DIR}/env}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# declared named datasources: `[database.<name>]` with a plain name (not pool_config and the like)
declared=$(grep -hE '^\[database\.[A-Za-z0-9_]+\][[:space:]]*$' "${REPO_ROOT}"/aether/tests/blueprints/*/src/main/resources/resources.toml 2>/dev/null \
           | sed -E 's/^\[database\.([A-Za-z0-9_]+)\].*/\1/' | sort -u)
envs=$(ls "${ENV_DIR}"/cloud-hetzner*.toml 2>/dev/null)
if [ -z "$declared" ] || [ -z "$envs" ]; then
    fail "D0 examined NOTHING: declared=[${declared}] env files=[$(echo "$envs" | tr '\n' ' ')]"
    echo "  passed: ${PASS}"; echo "  failed: ${FAIL}"; exit 1
fi
ok "D0 positive control: datasources [$(echo $declared)] declared by test blueprints; env files: $(for e in $envs; do basename "$e"; done | tr '\n' ' ')"

missing=""
for e in $envs; do
    for d in $declared; do
        grep -qE "^\[source\.[A-Za-z0-9_-]+\.node_config\.database\.${d}\][[:space:]]*$" "$e" || missing="${missing} $(basename "$e"):${d}"
    done
done
if [ -z "$missing" ]; then ok "D1 every declared datasource has a node_config section in every cloud env file"
else fail "D1 missing node_config sections (env:datasource):${missing}"; fi

unreset=""
for e in $envs; do
    for d in $declared; do
        url=$(awk -v d="$d" '$0 ~ "^\\[source\\.[A-Za-z0-9_-]+\\.node_config\\.database\\."d"\\]" {on=1; next} /^\[/ {on=0} on && /async_url/ {print; exit}' "$e")
        suffix=$(printf '%s' "$url" | sed -E 's/.*\$\{env:PG_DB\}(_[A-Za-z0-9_]+).*/\1/')
        [ -n "$suffix" ] && [ "$suffix" != "$url" ] || { unreset="${unreset} $(basename "$e"):${d}(no PG_DB suffix in '${url}')"; continue; }
        grep -qE "reset_cloud_pg_database \"\\$\\{PG_DB\\}${suffix}\"|ensure_cloud_pg_database \"\\$\\{PG_DB\\}${suffix}\"" "${INTEG_DIR}/run-tests.sh" \
            || unreset="${unreset} $(basename "$e"):${d}(\${PG_DB}${suffix} not reset/created by run-tests.sh)"
    done
done
if [ -z "$unreset" ]; then ok "D2 the database behind every env section is reset/created by run-tests.sh"
else fail "D2${unreset}"; fi

a=$(awk '/^\[source\.[A-Za-z0-9_-]+\.node_config\.database\.testpersistence\]/ {on=1; next} /^\[/ {on=0} on && /async_url/ {print; exit}' "${ENV_DIR}/cloud-hetzner.toml")
b=$(awk '/^\[source\.[A-Za-z0-9_-]+\.node_config\.database\.testpersistence\]/ {on=1; next} /^\[/ {on=0} on && /async_url/ {print; exit}' "${ENV_DIR}/cloud-hetzner-b.toml")
jb=$(awk '/^\[source\.[A-Za-z0-9_-]+\.node_config\.database\.testpersistence\]/ {on=1; next} /^\[/ {on=0} on && /async_url/ {print; exit}' "${ENV_DIR}/cloud-hetzner-jvm-b.toml")
if [ -n "$a" ] && [ -n "$b" ] && [ "$a" != "$b" ] && [ "$b" = "$jb" ] && printf '%s' "$b" | grep -q '_testpersistence_b"'; then ok "D3 cluster B's testpersistence database is B-owned (_testpersistence_b), identical in both B env files, and not A's"
else fail "D3 A=[${a}] B=[${b}] jvm-B=[${jb}]"; fi

# D4: the drop guard (stubs: no PG host is set, so an ACCEPTED name stops at "not set" and never reaches ssh)
GUARD_FN="$(mktemp)"; trap 'rm -f "$GUARD_FN"' EXIT
sed -n '/^reset_cloud_pg_database() {/,/^}/p' "${LIB_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}" > "$GUARD_FN"
guard_out() {
    ( log_warn() { echo "WARN $*"; }; log_info() { :; }
      source "$GUARD_FN"
      unset PG_HOST PG_USER PG_PASSWORD
      reset_cloud_pg_database "$1" ) 2>&1
}
g_b=$(guard_out "aether_forge_testpersistence_b"); g_a=$(guard_out "aether_forge_testpersistence"); g_x=$(guard_out "aether_forge"); g_y=$(guard_out "aether_forge_testpersistence_c")
if ! grep -q REFUSING <<<"$g_b" && grep -q 'not set' <<<"$g_b" && ! grep -q REFUSING <<<"$g_a" && grep -q REFUSING <<<"$g_x" && grep -q REFUSING <<<"$g_y"; then
    ok "D4 the drop guard accepts _testpersistence and _testpersistence_b, and refuses aether_forge and _testpersistence_c"
else fail "D4 b=[${g_b}] a=[${g_a}] x=[${g_x}] c=[${g_y}]"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
