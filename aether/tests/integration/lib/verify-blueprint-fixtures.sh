#!/usr/bin/env bash
# verify-blueprint-fixtures.sh — refuse to start a run whose blueprint fixtures are absent.
#
# WHY THIS EXISTS
# ---------------
# Suites deploy blueprints by Maven coordinate. Until 2026-09-18 nothing checked that
# those artifacts existed before the run started, so a missing fixture surfaced as an
# HTTP 500 in the middle of a suite ("Artifact not found: ...:jar") — several minutes and,
# on cloud, a full provision cycle after the point where it was knowable.
#
# It stayed hidden for a further reason: the CLI used to read a hard-coded
# ~/.m2/repository (#1223), which on a long-lived developer machine accumulates
# artifacts nobody's build produces. examples/url-shortener and url-shortener-v2 are
# excluded from examples/pom.xml by design (independent versions 1.0.0/1.0.1), with a
# comment saying to build them separately — and nothing ever did. The suites passed on
# residue. Point the CLI at a clean or per-worktree repository and 06-deployment fails
# entirely.
#
# THE REPOSITORY CHECKED HERE MUST BE THE ONE THE CLI WILL READ.
# Checking a different repository than the one the deploy resolves from is the original
# defect wearing a different hat: it would report a clean preflight and then deploy
# nothing. So this mirrors MavenLocalRepoLocator's resolution order.
#
# PRESENT IS NOT CURRENT (#2048). A jar left in a long-lived repository by an older slice processor passes a
# presence check and then fails at deploy: `Slice X was compiled with a slice-processor before the route-security
# contract (routeSecurityContract=0, current N)`. Every suite that deploys it fails for a reason unrelated to what
# it tests. So each fixture's slice jars (the coordinate's own jars and the slice artifacts its META-INF/blueprint.toml
# lists) are also read for the stamp the generated factory returns from routeSecurityContract(), and a stamp below the
# tree's current contract (SliceRouterFactory.ROUTE_SECURITY_CONTRACT) is refused, naming the artifact.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITES_DIR="${SCRIPT_DIR}/../suites"
LIB_DIR="${SCRIPT_DIR}"

# --- Resolve the local repository exactly as MavenLocalRepoLocator does ---------------
resolve_local_repo() {
    # 1. -Dmaven.repo.local, as the CLI will receive it via AETHER_JAVA_OPTS.
    if [[ "${AETHER_JAVA_OPTS:-}" =~ -Dmaven\.repo\.local=([^[:space:]]+) ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return
    fi
    # 2. <localRepository> in the user's settings.xml.
    local settings="$HOME/.m2/settings.xml"
    if [ -f "$settings" ]; then
        local from_settings
        from_settings=$(sed -n 's:.*<localRepository>\(.*\)</localRepository>.*:\1:p' "$settings" | head -1)
        from_settings="${from_settings/#\~/$HOME}"
        from_settings="${from_settings//\$\{user.home\}/$HOME}"
        if [ -n "$from_settings" ]; then
            printf '%s\n' "$from_settings"
            return
        fi
    fi
    # 3. The default.
    printf '%s\n' "$HOME/.m2/repository"
}

REPO="$(resolve_local_repo)"

# --- The contract the runtime in THIS tree requires ---------------------------------------
# Read from the source the node compiles against, never hard-coded here. An unreadable value is a broken
# check, not a clean one.
CONTRACT_SRC="${SCRIPT_DIR}/../../../http-routing-adapter/src/main/java/org/pragmatica/aether/http/adapter/SliceRouterFactory.java"
CURRENT_CONTRACT="$(sed -n 's/.*int ROUTE_SECURITY_CONTRACT = \([0-9][0-9]*\);.*/\1/p' "$CONTRACT_SRC" 2>/dev/null | head -1)"
if [ -z "$CURRENT_CONTRACT" ]; then
    echo "[FAIL]  cannot read ROUTE_SECURITY_CONTRACT from ${CONTRACT_SRC} — the currency check cannot run."
    exit 2
fi
for tool in unzip javap; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "[FAIL]  '${tool}' is required to read the route-security stamp of the fixtures; refusing to skip the check."
        exit 2
    fi
done

# jar_stamps <jar>: one "<factory class> <stamp>" line per SliceRouterFactory the jar registers. A factory
# class without routeSecurityContract() is a pre-contract class: stamp 0, which is what the runtime reads too
# (the interface default). A method whose body is not a constant yields "unreadable".
jar_stamps() {
    local jar="$1" cls stamp
    unzip -p "$jar" META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory 2>/dev/null \
        | sed 's/#.*//' | tr -d '\r' | while IFS= read -r cls; do
        cls="${cls//[[:space:]]/}"
        [ -n "$cls" ] || continue
        stamp="$(javap -p -c -cp "$jar" "$cls" 2>/dev/null | awk '
            / routeSecurityContract\(\);$/ { inm = 1; found = 1; next }
            inm && /iconst_[0-5]/ { sub(/.*iconst_/, ""); print $1; v = 1; exit }
            inm && /(bipush|sipush)/ { print $NF; v = 1; exit }
            inm && /return/ { exit }
            END { if (!found) print 0; else if (!v) print "unreadable" }')"
        printf '%s %s\n' "$cls" "${stamp:-unreadable}"
    done
}

echo "[STEP]  Blueprint fixture preflight"
echo "[INFO]    local repository in use: ${REPO}"

# --- Derive the required set FROM THE SUITES, so it cannot drift ----------------------
# A hard-coded list is what produced this class of bug; anything a suite references is
# required by construction.
# NOTE: `mapfile` is bash 4+; macOS ships bash 3.2, where it is absent and the array
# silently stays unset. Read portably instead.
COORDS=()
while IFS= read -r line; do
    [ -n "$line" ] && COORDS+=("$line")
done < <(
    grep -rhoE 'org\.pragmatica\.aether\.(example|test):[a-zA-Z0-9_-]+:[0-9]+\.[0-9]+\.[0-9]+' \
        "$SUITES_DIR" "$LIB_DIR" 2>/dev/null | sort -u
)

# A zero count is a BROKEN SCANNER, not a clean result. State the space, then judge it.
echo "[INFO]    blueprint coordinates referenced by suites: ${#COORDS[@]}"
if [ "${#COORDS[@]}" -eq 0 ]; then
    echo "[FAIL]  fixture preflight found NO coordinates at all — the scan is broken, not the tree."
    echo "        searched: ${SUITES_DIR} and ${LIB_DIR}"
    exit 2
fi

# WHAT IS CHECKED, AND WHY IT IS NOT "-blueprint.jar EXISTS".
# The failure this guards against is "the artifact was never built into THIS repository"
# — that is what produced `Artifact not found: ...:jar` and an empty .m2-local directory.
# Requiring a -blueprint.jar specifically produces FALSE POSITIVES: some referenced
# coordinates may be slice artifacts, which legitimately ship only a plain jar (02w referenced
# `test-entity-entity-slice` this way until 2026-09-24, when it moved to the `test-entity` blueprint). So: FAIL when the version directory holds no jar at all, and report
# the blueprint-jar shape separately without blocking on it.
missing=()
noblueprint=()
stale=()
present=0
checked=0
unlisted=()

# check_stamps <coord> <jar>: record a stale or unreadable stamp for every factory in the jar.
check_stamps() {
    local coord="$1" jar="$2" cls stamp
    while read -r cls stamp; do
        [ -n "$cls" ] || continue
        if [ "$stamp" = "unreadable" ]; then
            stale+=("${jar}  (${cls}: routeSecurityContract() is not a constant, stamp unreadable; fixture of ${coord})")
        elif [ "$stamp" -lt "$CURRENT_CONTRACT" ]; then
            stale+=("${jar}  (${cls}: routeSecurityContract=${stamp}, current ${CURRENT_CONTRACT}; fixture of ${coord})")
        fi
    done < <(jar_stamps "$jar")
}
for coord in "${COORDS[@]}"; do
    group="${coord%%:*}"
    rest="${coord#*:}"
    artifact="${rest%%:*}"
    version="${rest##*:}"
    dir="${REPO}/${group//.//}/${artifact}/${version}"
    if [ -d "$dir" ] && [ -n "$(find "$dir" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
        present=$((present + 1))
        [ -f "${dir}/${artifact}-${version}-blueprint.jar" ] || noblueprint+=("${coord}")
        # Currency: the coordinate's own slice jars, then the slice artifacts its blueprint lists.
        for jar in "${dir}"/*.jar; do
            case "$jar" in *-blueprint.jar) continue ;; esac
            checked=$((checked + 1))
            check_stamps "$coord" "$jar"
        done
        if [ -f "${dir}/${artifact}-${version}-blueprint.jar" ]; then
            while IFS= read -r sa; do
                [ -n "$sa" ] || continue
                sgroup="${sa%%:*}"; srest="${sa#*:}"; sartifact="${srest%%:*}"; sversion="${srest##*:}"
                sjar="${REPO}/${sgroup//.//}/${sartifact}/${sversion}/${sartifact}-${sversion}.jar"
                if [ -f "$sjar" ]; then
                    checked=$((checked + 1))
                    check_stamps "$coord" "$sjar"
                else
                    unlisted+=("${sa}  (listed by ${coord}, not in ${REPO})")
                fi
            done < <(unzip -p "${dir}/${artifact}-${version}-blueprint.jar" META-INF/blueprint.toml 2>/dev/null \
                         | sed -n 's/^[[:space:]]*artifact[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p')
        fi
    else
        missing+=("${coord}  ->  ${dir}")
    fi
done

if [ "${#noblueprint[@]}" -gt 0 ]; then
    echo "[INFO]    built, but carry no -blueprint.jar (expected for slice artifacts): ${noblueprint[*]}"
fi

echo "[INFO]    present: ${present}/${#COORDS[@]}"
echo "[INFO]    slice jars read for route-security stamp: ${checked} (current contract ${CURRENT_CONTRACT})"
if [ "${#unlisted[@]}" -gt 0 ]; then
    echo "[WARN]    blueprint-listed slice artifacts not in the repository (not stamp-checked): ${#unlisted[@]}"
    for u in "${unlisted[@]}"; do echo "          ${u}"; done
fi

if [ "${#missing[@]}" -gt 0 ]; then
    echo "[FAIL]  ${#missing[@]} fixture(s) NOT BUILT into ${REPO}:"
    for m in "${missing[@]}"; do
        echo "          ${m}"
    done
    echo ""
    echo "        Build them with:  ./build.sh        (builds test blueprints AND example fixtures)"
    echo "        Or individually:  mvn -f examples/<module>/pom.xml clean install -DskipTests"
    echo ""
    echo "        Refusing to start: on cloud this would otherwise fail as an HTTP 500 mid-suite,"
    echo "        several minutes and one paid provision cycle after it was knowable."
    exit 1
fi

if [ "${#stale[@]}" -gt 0 ]; then
    echo "[FAIL]  ${#stale[@]} fixture jar(s) in ${REPO} predate the route-security contract:"
    for m in "${stale[@]}"; do
        echo "          ${m}"
    done
    echo ""
    echo "        Present is not current: the node refuses such a slice at deploy (routeSecurityContract below ${CURRENT_CONTRACT})."
    echo "        Rebuild them with:  ./build.sh        (builds test blueprints AND example fixtures)"
    echo "        Or individually:    mvn -f examples/<module>/pom.xml clean install -DskipTests"
    exit 1
fi

echo "[PASS]  all ${present} blueprint fixtures present and current (route-security contract ${CURRENT_CONTRACT})"
exit 0
