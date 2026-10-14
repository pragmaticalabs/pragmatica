#!/bin/bash
# First-user gate for the README Quick Start (#953).
#
# Runs the Quick Start exactly as README.md writes it, in a clean container: no ~/.m2, no checkout,
# nothing installed but a JDK, Maven, Git, curl, jq and a Docker CLI. The commands are EXTRACTED from
# README.md at the ref under test, so the document cannot drift from what is tested: a step removed
# from the README is a step this gate no longer runs, and the Quick Start then fails here.
#
# Usage:
#   tools/quickstart-check.sh --version V [--maven-repo PATH|URL] [--ref REF] [--source tree|github]
#                             [--timeout SECONDS] [--keep]
#   tools/quickstart-check.sh stage-repo REPO_LOCAL OUT_DIR
#
#   --version V       Aether release the installer installs (exported as VERSION, which aether/install.sh
#                     reads). Required: e.g. 1.0.0-rc4-candidate, which the unpinned installer skips.
#   --maven-repo P|U  Extra Maven repository standing in for Maven Central until the platform version is
#                     published there. A directory is copied into the container. Omit it to resolve from Central only.
#   --ref REF         Commit whose README.md is extracted and whose tree the README's `git clone` receives
#                     (default HEAD; committed content only, never the working tree).
#   --source github   Let `git clone` reach GitHub instead of REF (tests the published repository).
#   --timeout S       Seconds the second-terminal block may take to succeed (default 1200).
#   --keep            Leave the containers and the work directory for inspection.
#
#   stage-repo        Builds the Central stand-in: copies, from a repo.local that holds a full install of
#                     this repository, exactly the modules the publish set publishes
#                     (tools/check-publish-closure.py --table, docs/release/maven-central-publish-set.md).
#
# What the gate substitutes, and nothing else:
#   - VERSION=<V> in the environment, read by aether/install.sh (the README installs "latest").
#   - git `url.insteadOf`, so the README's clone URL receives REF (unless --source github).
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
        --timeout)    TIMEOUT="$2"; shift 2 ;;
        --keep)       KEEP=true; shift ;;
        *)            die "unknown argument: $1" ;;
    esac
done
[ -n "$VERSION_ARG" ] || die "--version is required"
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
echo "== README.md at $SHA, Quick Start terminal 1 =="; cat "$WORK/terminal-1.sh"
echo "== terminal 2 (retried until it succeeds) =="; cat "$WORK/terminal-2.sh"

# --- What `git clone` receives ---
mkdir -p "$WORK/repos"
if [ "$SOURCE" = tree ]; then
    git init -q --bare "$WORK/repos/pragmatica.git"
    # fetch, not push: a push from $ROOT would run its pre-push hooks.
    git -C "$WORK/repos/pragmatica.git" fetch -q "$ROOT" "$SHA:refs/heads/main"
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
    if (cd /work && bash -eo pipefail /gate/terminal-2.sh) > /work/terminal-2.out 2>&1 && [ -s /work/terminal-2.out ]; then
        step "terminal 1 so far"
        cat /work/terminal-1.log
        step "PASS: terminal 2 succeeded on attempt $attempt after $((SECONDS - started))s"
        cat /work/terminal-2.out
        exit 0
    fi
    if [ $((SECONDS - started)) -ge "$GATE_TIMEOUT" ]; then
        step "FAIL: terminal 2 did not succeed within ${GATE_TIMEOUT}s ($attempt attempts); last output:"
        cat /work/terminal-2.out
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

echo "== running the Quick Start: version $VERSION_ARG, commit $SHA, maven repo ${REPO_URL:-<Central only>}, source $SOURCE"
# Inputs are copied in, never bind-mounted: a bind mount depends on what the Docker host shares (Docker Desktop
# shares neither $TMPDIR nor /private/tmp by default, and mounts them EMPTY without an error).
docker create --name "$NAME" --entrypoint bash \
    --network "container:$NAME-dind" \
    -e DOCKER_HOST=tcp://localhost:2375 \
    -e VERSION="$VERSION_ARG" \
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
