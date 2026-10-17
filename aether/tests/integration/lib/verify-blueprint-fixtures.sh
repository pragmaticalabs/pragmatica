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
present=0
for coord in "${COORDS[@]}"; do
    group="${coord%%:*}"
    rest="${coord#*:}"
    artifact="${rest%%:*}"
    version="${rest##*:}"
    dir="${REPO}/${group//.//}/${artifact}/${version}"
    if [ -d "$dir" ] && [ -n "$(find "$dir" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
        present=$((present + 1))
        [ -f "${dir}/${artifact}-${version}-blueprint.jar" ] || noblueprint+=("${coord}")
    else
        missing+=("${coord}  ->  ${dir}")
    fi
done

if [ "${#noblueprint[@]}" -gt 0 ]; then
    echo "[INFO]    built, but carry no -blueprint.jar (expected for slice artifacts): ${noblueprint[*]}"
fi

echo "[INFO]    present: ${present}/${#COORDS[@]}"

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

echo "[PASS]  all ${present} blueprint fixtures present"
exit 0
