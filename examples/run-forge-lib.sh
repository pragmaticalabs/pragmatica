#!/bin/bash
# Shared Forge lookup for examples/*/run-forge.sh. Sourced, never executed (#953).
#
# Forge comes from the GitHub release through the installer, never from a source build, unless a
# contributor asks for one. Lookup order, first hit wins:
#   1. $AETHER_FORGE                    explicit override: a .jar or an executable launcher. If it is
#                                       set and unusable the script FAILS; it never falls through.
#   2. aether-forge on PATH
#   3. $AETHER_HOME/bin/aether-forge    where aether/install.sh puts the launcher (archive install: a
#                                       symlink into aether-forge-<v>/bin; jar install: a wrapper)
#   4. $AETHER_HOME/lib/aether-forge.jar  the jar-only install (aether/install.sh jar_install)
#   ($AETHER_HOME defaults to ~/.aether, as in aether/install.sh.)
# The CONTRIBUTOR path, a Forge built from this checkout, runs only with --forge-from-source. It is
# opt-in because a Quick Start user also stands in a checkout: an automatic fallback would turn a
# missing install into a silent source build, which is the defect #953 removes.

FORGE_INSTALL_COMMAND='curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh'

# forge_candidate PATH SOURCE: accept PATH as Forge if it is a readable .jar or an executable launcher.
forge_candidate() {
    case "$1" in
        *.jar) [ -f "$1" ] && [ -r "$1" ] || return 1; FORGE_KIND=jar ;;
        *)     [ -f "$1" ] && [ -x "$1" ] || return 1; FORGE_KIND=launcher ;;
    esac
    FORGE_PATH="$1"
    FORGE_SOURCE="$2"
}

forge_not_found() {
    echo "ERROR: Aether Forge not found." >&2
    echo "       Looked at: \$AETHER_FORGE (unset), aether-forge on PATH, $1/bin/aether-forge, $1/lib/aether-forge.jar" >&2
    echo "       Install it from the GitHub release:" >&2
    echo "         $FORGE_INSTALL_COMMAND" >&2
    echo "       or point AETHER_FORGE at a Forge jar or launcher." >&2
    echo "       Contributors building Forge from this checkout: ./run-forge.sh --forge-from-source" >&2
    return 1
}

# forge_resolve: set FORGE_PATH, FORGE_KIND (jar|launcher) and FORGE_SOURCE from an installed Forge.
forge_resolve() {
    local home="${AETHER_HOME:-$HOME/.aether}" on_path

    if [ -n "${AETHER_FORGE:-}" ]; then
        forge_candidate "$AETHER_FORGE" "AETHER_FORGE" && return 0
        echo "ERROR: AETHER_FORGE=$AETHER_FORGE is neither a readable .jar nor an executable launcher." >&2
        echo "       Unset it to use the installed Forge, or fix the path." >&2
        return 1
    fi

    on_path=$(command -v aether-forge 2>/dev/null || true)
    if [ -n "$on_path" ] && forge_candidate "$on_path" "PATH"; then
        return 0
    fi
    forge_candidate "$home/bin/aether-forge" "installer launcher" && return 0
    forge_candidate "$home/lib/aether-forge.jar" "installer jar" && return 0
    forge_not_found "$home"
}

# forge_from_source PROJECT_ROOT SKIP_BUILD: the contributor path. Builds aether-forge.jar from the
# checkout when it is missing (never when SKIP_BUILD=true) and selects it.
forge_from_source() {
    local jar="$1/aether/forge/forge-core/target/aether-forge.jar"

    if [ -n "${AETHER_FORGE:-}" ]; then
        echo "ERROR: --forge-from-source and AETHER_FORGE both choose a Forge; use one." >&2
        return 1
    fi
    if [ ! -f "$1/aether/forge/forge-core/pom.xml" ]; then
        echo "ERROR: --forge-from-source needs the pragmatica monorepo checkout; $1 is not one." >&2
        return 1
    fi
    if [ ! -f "$jar" ] && [ "$2" = false ]; then
        echo "Building Forge from source (contributor path)..."
        mvn -f "$1/pom.xml" package -pl aether/forge/forge-core -am -DskipTests -q || return 1
    fi
    forge_candidate "$jar" "source build (contributor path)" && return 0
    echo "ERROR: Forge JAR not found at $jar. Run without --skip-build to build it." >&2
    return 1
}

# forge_exec ARGS...: replace this shell with Forge.
# FORGE_JVM_OPTS: extra JVM flags (e.g. JDWP remote debug -- see docs/slice-developers/forge-guide.md#debugging).
# For a jar it is split with read -rd into an array: flags word-split on whitespace INCLUDING newlines, no
# glob expansion (a literal * in JDWP address= survives), empty value yields zero argv words (#664).
# A launcher reads JVM flags from AETHER_JAVA_OPTS (both installer launchers expand it unquoted), so the
# flags are appended there; that expansion is the launcher's, and it is not glob-safe the way the array is.
forge_exec() {
    echo "Forge: $FORGE_PATH ($FORGE_SOURCE)"
    if [ "$FORGE_KIND" = jar ]; then
        IFS=$' \t\n' read -rd '' -a FORGE_JVM_OPTS_ARR <<< "${FORGE_JVM_OPTS:-}" || true
        exec java "${FORGE_JVM_OPTS_ARR[@]}" -jar "$FORGE_PATH" "$@"
    fi
    export AETHER_JAVA_OPTS="${AETHER_JAVA_OPTS:+$AETHER_JAVA_OPTS }${FORGE_JVM_OPTS:-}"
    exec "$FORGE_PATH" "$@"
}
