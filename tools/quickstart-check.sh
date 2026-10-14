#!/bin/bash
# First-user gate for the README Quick Start (#953).
#
# Runs the Quick Start exactly as README.md writes it, in a clean container: no ~/.m2, no checkout,
# nothing installed but a JDK, Maven, Git, curl, jq and a Docker CLI. The commands are EXTRACTED from
# README.md at the ref under test, so the document cannot drift from what is tested: a step removed
# from the README is a step this gate no longer runs, and the Quick Start then fails here.
#
# Usage:
#   tools/quickstart-check.sh [--version V] [--maven-repo PATH|URL] [--ref REF] [--source tree|github]
#                             [--expect JQ_FILTER] [--timeout SECONDS] [--keep]
#   tools/quickstart-check.sh stage-repo REPO_LOCAL OUT_DIR
#
#   --version V       Run the Quick Start as if it named release V: the README's single
#                     `AETHER_VERSION=<R>` line is rewritten to V, so the installer installs V and the clone
#                     takes tag vV. For a candidate, e.g. 1.0.0-rc4-candidate. Default: R, unchanged.
#                     The override never weakens the version check below: that compares R, not V.
#   --maven-repo P|U  Extra Maven repository standing in for Maven Central until the platform version is
#                     published there. A directory is copied into the container. Omit it to resolve from Central only.
#   --ref REF         Commit whose README.md is extracted and whose tree the README's `git clone` receives
#                     (default HEAD; committed content only, never the working tree).
#   --source github   Let `git clone` reach GitHub instead of REF (tests the published repository).
#   --expect F        jq filter the second terminal's stdout must satisfy (`jq -e`; default
#                     '.totalPrice | numbers', the priced result). Exit 0 alone is not success: Forge
#                     answers a wrong path with a JSON problem body, which `jq` prints happily.
#   --timeout S       Seconds the second-terminal block may take to succeed (default 1200).
#   --keep            Leave the containers and the work directory for inspection.
#
#   stage-repo        Builds the Central stand-in: copies, from a repo.local that holds a full install of
#                     this repository, exactly the modules the publish set publishes
#                     (tools/check-publish-closure.py --table, docs/release/maven-central-publish-set.md).
#
# Version check (before anything runs): terminal 1 must assign AETHER_VERSION exactly once, and that
# value R must equal the version of the tree under test (the root pom's, minus -SNAPSHOT). A README left
# at the previous release fails here, naming both versions.
#
# What the gate substitutes, and nothing else:
#   - the AETHER_VERSION line, only when --version differs from R.
#   - git `url.insteadOf`, so the README's clone URL receives REF, as branch main and tag v<version>
#     (unless --source github, where the tag must exist on GitHub).
#   - MAVEN_ARGS="-s settings.xml" adding --maven-repo (only when given).
# The installer itself is the one the README downloads (raw.githubusercontent.com .../main/aether/install.sh).
#
# README contract: the block after `<!-- quickstart:terminal-1 -->` runs first and keeps running (its last
# step starts Forge in the foreground); the block after `<!-- quickstart:terminal-2 -->` is retried, with
# `set -eo pipefail`, until it succeeds or the timeout passes. Terminal 1 ending early is a failure.
#
# PostgreSQL: the README's ./start-postgres.sh talks to a private docker:dind daemon whose network
# namespace the gate container shares, so localhost:5432, 8070, 5150 and 8888 never touch the host.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="pragmatica-quickstart-check:1"

die() { echo "quickstart-check: $*" >&2; exit 2; }

stage_repo() {
    local repo_local="$1" out="$2" coords group artifact version src count=0
    [ -d "$repo_local" ] || die "repo.local '$repo_local' is not a directory"
    version=$(cd "$ROOT" && mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f pom.xml)
    coords=$(python3 "$ROOT/tools/check-publish-closure.py" --table | awk -F'|' '$5 ~ /publish/ {gsub(/[ `]/, "", $3); print $3}')
    [ -n "$coords" ] || die "the publish table is empty"
    mkdir -p "$out"
    while IFS=: read -r group artifact; do
        src="$repo_local/${group//.//}/$artifact/$version"
        [ -d "$src" ] || die "$group:$artifact:$version is published but absent from $repo_local (install the repository first)"
        mkdir -p "$out/${group//.//}/$artifact/$version"
        cp -a "$src"/. "$out/${group//.//}/$artifact/$version/"
        count=$((count + 1))
    done <<< "$coords"
    # A local-repo copy carries resolution bookkeeping that would make Maven distrust a remote layout.
    find "$out" \( -name '_remote.repositories' -o -name '*.lastUpdated' -o -name 'maven-metadata-local*.xml' \) -delete
    echo "staged $count published module(s) of $version into $out"
}

if [ "${1:-}" = stage-repo ]; then
    [ $# -eq 3 ] || die "usage: $0 stage-repo REPO_LOCAL OUT_DIR"
    stage_repo "$2" "$3"
    exit 0
fi

VERSION_ARG=""
EXPECT='.totalPrice | numbers'
MAVEN_REPO=""
REF=HEAD
SOURCE=tree
TIMEOUT=1200
KEEP=false
while [ $# -gt 0 ]; do
    case "$1" in
        --version)    VERSION_ARG="$2"; shift 2 ;;
        --maven-repo) MAVEN_REPO="$2"; shift 2 ;;
        --ref)        REF="$2"; shift 2 ;;
        --source)     SOURCE="$2"; shift 2 ;;
        --expect)     EXPECT="$2"; shift 2 ;;
        --timeout)    TIMEOUT="$2"; shift 2 ;;
        --keep)       KEEP=true; shift ;;
        *)            die "unknown argument: $1" ;;
    esac
done
case "$SOURCE" in tree|github) ;; *) die "--source must be tree or github" ;; esac
command -v docker >/dev/null || die "docker not found"

SHA=$(git -C "$ROOT" rev-parse --verify "$REF^{commit}") || die "ref '$REF' does not resolve"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/quickstart-check.XXXXXX")
NAME="quickstart-check-$$"

cleanup() {
    if [ "$KEEP" = true ]; then
        echo "kept: containers $NAME, $NAME-dind; work dir $WORK"
        return
    fi
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker rm -f -v "$NAME-dind" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# --- The commands, from README.md at the ref under test ---
extract_block() {
    git -C "$ROOT" show "$SHA:README.md" | awk -v marker="<!-- quickstart:$1" '
        index($0, marker) == 1 { armed = 1; next }
        armed && /^```/ { if (inside) exit; inside = 1; next }
        inside { print }'
}
extract_block terminal-1 > "$WORK/terminal-1.sh"
extract_block terminal-2 > "$WORK/terminal-2.sh"
for block in terminal-1 terminal-2; do
    grep -q '[^[:space:]]' "$WORK/$block.sh" || die "README.md at $SHA has no fenced block after <!-- quickstart:$block -->"
done
# --- The version: named once in the README, and the tree's own ---
assignments=$(grep -cE '^AETHER_VERSION=' "$WORK/terminal-1.sh" || true)
[ "$assignments" = 1 ] || die "README.md at $SHA: terminal 1 must assign AETHER_VERSION exactly once (found $assignments)"
README_VERSION=$(sed -n 's/^AETHER_VERSION=//p' "$WORK/terminal-1.sh" | tr -d "\"' ")
TREE_VERSION=$(git -C "$ROOT" show "$SHA:pom.xml" | grep -o '<version>[^<]*</version>' | head -1 | sed 's#<version>\(.*\)</version>#\1#')
TREE_VERSION="${TREE_VERSION%-SNAPSHOT}"
[ "$README_VERSION" = "$TREE_VERSION" ] \
    || die "README Quick Start names AETHER_VERSION=$README_VERSION but the tree at $SHA is version $TREE_VERSION (root pom.xml); bump the Quick Start version"
echo "== version: README $README_VERSION = tree $TREE_VERSION"
RUN_VERSION="${VERSION_ARG:-$README_VERSION}"
if [ "$RUN_VERSION" != "$README_VERSION" ]; then
    sed -i.orig "s/^AETHER_VERSION=.*/AETHER_VERSION=$RUN_VERSION/" "$WORK/terminal-1.sh" && rm -f "$WORK/terminal-1.sh.orig"
    echo "== --version $RUN_VERSION: substituted for AETHER_VERSION=$README_VERSION (installer and clone tag)"
fi

echo "== README.md at $SHA, Quick Start terminal 1 =="; cat "$WORK/terminal-1.sh"
echo "== terminal 2 (retried until it succeeds) =="; cat "$WORK/terminal-2.sh"

# --- What `git clone` receives ---
mkdir -p "$WORK/repos"
if [ "$SOURCE" = tree ]; then
    git init -q --bare "$WORK/repos/pragmatica.git"
    # fetch, not push: a push from $ROOT would run its pre-push hooks.
    git -C "$WORK/repos/pragmatica.git" fetch -q "$ROOT" "$SHA:refs/heads/main"
    git -C "$WORK/repos/pragmatica.git" tag "v$RUN_VERSION" "$SHA"
    git -C "$WORK/repos/pragmatica.git" symbolic-ref HEAD refs/heads/main
    ln -s pragmatica.git "$WORK/repos/pragmatica"
fi

# --- The Central stand-in ---
REPO_DIR=""
REPO_URL=""
if [ -n "$MAVEN_REPO" ]; then
    case "$MAVEN_REPO" in
        http://*|https://*|file://*) REPO_URL="$MAVEN_REPO" ;;
        *)  [ -d "$MAVEN_REPO" ] || die "--maven-repo '$MAVEN_REPO' is neither a URL nor a directory"
            REPO_DIR="$MAVEN_REPO"
            REPO_URL="file:///gate-maven-repo" ;;
    esac
    cat > "$WORK/settings.xml" <<EOF
<settings>
  <profiles>
    <profile>
      <id>quickstart-staging</id>
      <repositories><repository><id>quickstart-staging</id><url>$REPO_URL</url></repository></repositories>
      <pluginRepositories><pluginRepository><id>quickstart-staging</id><url>$REPO_URL</url></pluginRepository></pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>quickstart-staging</activeProfile></activeProfiles>
</settings>
EOF
fi

# --- Runs inside the container ---
cat > "$WORK/run.sh" <<'INNER'
#!/bin/bash
set -uo pipefail
step() { echo ""; echo "== [$(date -u +%H:%M:%SZ)] $*"; }

step "clean machine: no ~/.m2, no checkout, no Aether"
for path in "$HOME/.m2" "$HOME/.aether" /work/pragmatica; do
    if [ -e "$path" ]; then echo "FAIL: $path exists before the Quick Start ran"; exit 1; fi
    echo "  absent: $path"
done
java -version 2>&1 | head -1; mvn -v | head -1; git --version; docker version --format '  docker server {{.Server.Version}}'

if [ -d /gate/repos/pragmatica.git ]; then
    git config --global url."file:///gate/repos/".insteadOf "https://github.com/pragmaticalabs/"
    # docker cp keeps the host's uid on the copied repository; git refuses a repository owned by another user.
    git config --global --add safe.directory /gate/repos/pragmatica.git
    git config --global --add safe.directory /gate/repos/pragmatica
    echo "  git clone of github.com/pragmaticalabs/* receives the commit under test"
fi
[ -f /gate/settings.xml ] && export MAVEN_ARGS="-s /gate/settings.xml" && echo "  Maven: Central + $(grep -o '<url>[^<]*' /gate/settings.xml | head -1 | cut -c6-)"

mkdir -p /work && cd /work
step "terminal 1 (README, verbatim)"
bash -ex /gate/terminal-1.sh > /work/terminal-1.log 2>&1 &
T1=$!

started=$SECONDS
attempt=0
while true; do
    if ! kill -0 "$T1" 2>/dev/null; then
        wait "$T1"; rc=$?
        step "FAIL: terminal 1 ended (exit $rc) before terminal 2 succeeded"
        cat /work/terminal-1.log
        exit 1
    fi
    attempt=$((attempt + 1))
    if (cd /work && bash -eo pipefail /gate/terminal-2.sh) > /work/terminal-2.out 2> /work/terminal-2.err \
        && jq -e "$GATE_EXPECT" /work/terminal-2.out > /dev/null 2>&1; then
        step "terminal 1 so far"
        cat /work/terminal-1.log
        step "PASS: terminal 2 succeeded on attempt $attempt after $((SECONDS - started))s; jq -e '$GATE_EXPECT' holds"
        cat /work/terminal-2.out
        exit 0
    fi
    if [ $((SECONDS - started)) -ge "$GATE_TIMEOUT" ]; then
        step "FAIL: terminal 2 did not succeed, with jq -e '$GATE_EXPECT' holding, within ${GATE_TIMEOUT}s ($attempt attempts); last output:"
        cat /work/terminal-2.out /work/terminal-2.err
        step "terminal 1"
        cat /work/terminal-1.log
        exit 1
    fi
    sleep 10
done
INNER

# --- The image: a JDK, Maven, Git, curl, jq and a Docker CLI; ~/.m2 removed ---
docker build -q -t "$IMAGE" - >/dev/null <<'DOCKERFILE'
FROM maven:3.9-eclipse-temurin-25
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git curl jq ca-certificates >/dev/null \
    && rm -rf /var/lib/apt/lists/* /root/.m2 /usr/share/maven/ref/*
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker
DOCKERFILE

# --- A private Docker daemon for ./start-postgres.sh ---
docker run -d --privileged --name "$NAME-dind" -e DOCKER_TLS_CERTDIR= docker:dind >/dev/null
for _ in $(seq 1 60); do
    docker exec "$NAME-dind" docker info >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$NAME-dind" docker info >/dev/null 2>&1 || die "the private Docker daemon did not start"

echo "== running the Quick Start: version $RUN_VERSION, commit $SHA, maven repo ${REPO_URL:-<Central only>}, source $SOURCE"
# Inputs are copied in, never bind-mounted: a bind mount depends on what the Docker host shares (Docker Desktop
# shares neither $TMPDIR nor /private/tmp by default, and mounts them EMPTY without an error).
docker create --name "$NAME" --entrypoint bash \
    --network "container:$NAME-dind" \
    -e DOCKER_HOST=tcp://localhost:2375 \
    -e GATE_EXPECT="$EXPECT" \
    -e GATE_TIMEOUT="$TIMEOUT" \
    "$IMAGE" /gate/run.sh >/dev/null
docker cp -q "$WORK/." "$NAME:/gate"
[ -z "$REPO_DIR" ] || docker cp -q "$REPO_DIR/." "$NAME:/gate-maven-repo"
set +e
docker start -a "$NAME"
rc=$?
set -e
if [ $rc -eq 0 ]; then echo "quickstart-check: PASS"; else echo "quickstart-check: FAIL (exit $rc)"; fi
exit $rc
