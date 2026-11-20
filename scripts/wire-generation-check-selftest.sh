#!/usr/bin/env bash
# Self-test of scripts/wire-generation-check.sh and of the workflow arm that runs it on the merge queue (#3303).
#
#   scripts/wire-generation-check-selftest.sh
#
# Builds scratch cases in a temp git repo, runs the REAL check script (and the command the changelog workflow's
# merge_group step runs, extracted from the workflow file) and asserts each exit code. Exits non-zero on any mismatch.
# Why it exists: a mutant that drops the merge_group arm, or stops counting TAG lines, passes every Java test; only a
# case run here goes red (a renumbered TAG with an unchanged generation is accepted if TAG is not counted; the queue
# case is accepted if the arm is absent, because no command runs).
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/.." && pwd)"
check="$here/wire-generation-check.sh"
workflow="$repo_root/.github/workflows/changelog.yml"
baseline="aether/node/src/test/resources/wire-assignment-baseline.txt"
guard="integrations/consensus/src/main/java/org/pragmatica/consensus/net/JoinGenerationGuard.java"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=selftest GIT_AUTHOR_EMAIL=selftest@example.invalid GIT_COMMITTER_NAME=selftest GIT_COMMITTER_EMAIL=selftest@example.invalid

failures=0
cases=0

# write_baseline <generation|-> <line>...   ("-" writes no GENERATION line)
write_baseline() {
    local generation="$1"
    shift
    mkdir -p "$(dirname "$baseline")"
    {
        echo "# scratch baseline"
        [[ "$generation" == "-" ]] || echo "GENERATION $generation"
        printf '%s\n' "$@"
    } >"$baseline"
}

write_guard() {
    mkdir -p "$(dirname "$guard")"
    printf 'interface JoinGenerationGuard {\n    int WIRE_GENERATION = %s;\n}\n' "$1" >"$guard"
}

commit_all() {
    git add -A >/dev/null
    git commit -q --allow-empty -m "$1"
}

new_repo() {
    rm -rf "$work/r"
    mkdir "$work/r"
    cd "$work/r"
    git init -q -b main .
    git config commit.gpgsign false
}

BASE_LINES=("TAG a.A 1" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1")

# expect <case> <expected rc> <command...>
expect() {
    local name="$1" want="$2"
    shift 2
    local out rc
    out="$("$@" 2>&1)"
    rc=$?
    cases=$((cases + 1))
    if [[ "$rc" -eq "$want" ]]; then
        echo "ok   $name rc=$rc"
    else
        failures=$((failures + 1))
        echo "FAIL $name: wanted rc=$want, got rc=$rc"
        sed 's/^/     | /' <<<"$out"
    fi
}

# Two commits: base (generation $1, lines BASE_LINES) then head (generation $2, lines "${@:3}"); the check runs on HEAD against main~1.
two_commit_case() {
    local name="$1" want="$2" base_gen="$3" head_gen="$4"
    shift 4
    new_repo
    write_baseline "$base_gen" "${BASE_LINES[@]}"
    write_guard 3
    commit_all base
    write_baseline "$head_gen" "$@"
    commit_all head
    expect "$name" "$want" "$check" "HEAD~1"
}

# C0: no change, generation unchanged
two_commit_case "C0 no change" 0 3 3 "${BASE_LINES[@]}"
# C1: the generation moves only with a wire break
two_commit_case "C1a added TAG only, same generation" 0 3 3 "${BASE_LINES[@]}" "TAG a.C 3"
two_commit_case "C1b added TAG only, generation bumped" 1 3 4 "${BASE_LINES[@]}" "TAG a.C 3"
two_commit_case "C1c SHAPE retype, same generation" 1 3 3 "TAG a.A 1" "TAG a.B 2" "SHAPE a.A (x:long)" "ENUM a.E X=0,Y=1"
two_commit_case "C1d SHAPE retype, generation + 1" 0 3 4 "TAG a.A 1" "TAG a.B 2" "SHAPE a.A (x:long)" "ENUM a.E X=0,Y=1"
# C2: removal is a break; ENUM reorder is a break
two_commit_case "C2a removed TAG, generation + 1" 0 3 4 "TAG a.A 1" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
two_commit_case "C2b removed ENUM, same generation" 1 3 3 "TAG a.A 1" "TAG a.B 2" "SHAPE a.A (x:int)"
# C4: exactly +1, and the base generation read from the guard when the base baseline has no GENERATION line
two_commit_case "C4a break, generation + 2" 1 3 5 "TAG a.A 9" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
two_commit_case "C4b base without GENERATION line (guard says 3), break, generation 4" 0 - 4 "TAG a.A 9" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
two_commit_case "C4c base without GENERATION line (guard says 3), no break, generation 4" 1 - 4 "${BASE_LINES[@]}"
# C6: a renumbered TAG is a wire break (an old peer routes the bytes to the wrong codec)
two_commit_case "C6a TAG renumber, same generation" 1 3 3 "TAG a.A 7" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
two_commit_case "C6b TAG renumber, generation + 1" 0 3 4 "TAG a.A 7" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"

# The command the workflow's merge_group step runs, with the repo's script path replaced by the real script.
# Absent step => empty command => the queue case below finds no refusal and goes red.
merge_group_command() {
    awk '
        /^      - name:/ { if (step_has_merge_group && command != "" && !done) { print command; done = 1 } step_has_merge_group = 0; command = "" }
        /if: github.event_name == .merge_group./ { step_has_merge_group = 1 }
        /^        run: scripts\/wire-generation-check.sh/ { sub(/^        run: /, ""); command = $0 }
        END { if (step_has_merge_group && command != "" && !done) print command }
    ' "$workflow"
}

# C7: queue squash. Two PRs each bump 3 -> 4 against the same base; each passes alone (pull_request arm, against base).
# The queue commit is PR-B squashed on top of PR-A: against HEAD^1 (PR-A) it must be refused, since base + 1 is 4 and the
# break would repeat generation 4 for a different wire.
new_repo
write_baseline 3 "${BASE_LINES[@]}"
write_guard 3
commit_all base
base_sha="$(git rev-parse HEAD)"
write_baseline 4 "TAG a.A 7" "TAG a.B 2" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
commit_all "PR-A"
write_baseline 4 "TAG a.A 7" "TAG a.B 8" "SHAPE a.A (x:int)" "ENUM a.E X=0,Y=1"
commit_all "queue squash of PR-B"
expect "C7a queue squash vs original base (pull_request arm)" 0 "$check" "$base_sha"
cmd="$(merge_group_command)"
cases=$((cases + 1))
if [[ -z "$cmd" ]]; then
    failures=$((failures + 1))
    echo "FAIL C7b workflow has no merge_group step running scripts/wire-generation-check.sh in $workflow"
else
    echo "ok   C7b workflow merge_group step found: $cmd"
    expect "C7c merge_group step command vs HEAD^1 refuses the double bump" 1 bash -c "${cmd//scripts\/wire-generation-check.sh/$check}"
fi

if [[ "$failures" -ne 0 ]]; then
    echo "wire-generation-check-selftest: $failures of $cases case(s) FAILED" >&2
    exit 1
fi
echo "wire-generation-check-selftest: all $cases cases passed"
