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

# C8: a SEMANTIC line declares a semantic wire break (how replicas JUDGE an existing value); no TAG, SHAPE or ENUM line moves.
# semantic_case <name> <rc> <base_gen> <head_gen> <base SEMANTIC lines, "|"-separated or empty> <head SEMANTIC lines, same>
semantic_case() {
    local name="$1" want="$2" base_gen="$3" head_gen="$4" base_sem="$5" head_sem="$6"
    local -a base_extra=() head_extra=()
    [[ -z "$base_sem" ]] || IFS='|' read -r -a base_extra <<<"$base_sem"
    [[ -z "$head_sem" ]] || IFS='|' read -r -a head_extra <<<"$head_sem"
    new_repo
    write_baseline "$base_gen" "${BASE_LINES[@]}" ${base_extra[@]+"${base_extra[@]}"}
    write_guard 3
    commit_all base
    write_baseline "$head_gen" "${BASE_LINES[@]}" ${head_extra[@]+"${head_extra[@]}"}
    commit_all head
    expect "$name" "$want" "$check" "HEAD~1"
}

S1="SEMANTIC a.A.epoch stale-if-below -> stale-if-below-or-equal"
S2="SEMANTIC a.B.mode accept-if-any -> accept-if-all"
semantic_case "C8a added SEMANTIC, generation + 1" 0 3 4 "" "$S1"
semantic_case "C8b added SEMANTIC, same generation" 1 3 3 "" "$S1"
semantic_case "C8c added SEMANTIC, generation + 2" 1 3 5 "" "$S1"
semantic_case "C8d SEMANTIC removed, same generation" 1 3 3 "$S1" ""
semantic_case "C8e SEMANTIC edited, same generation" 1 3 3 "$S1" "SEMANTIC a.A.epoch stale-if-below -> never-stale"
semantic_case "C8f existing SEMANTIC only, generation + 1 (no re-trigger)" 1 3 4 "$S1" "$S1"
semantic_case "C8g existing SEMANTIC only, same generation" 0 3 3 "$S1" "$S1"
semantic_case "C8h existing SEMANTIC kept, second one added, generation + 1" 0 3 4 "$S1" "$S1|$S2"
semantic_case "C8i malformed SEMANTIC (no judgement), same generation" 1 3 3 "" "SEMANTIC a.A.epoch"
semantic_case "C8j malformed SEMANTIC (no arrow), same generation" 1 3 3 "" "SEMANTIC a.A.epoch changed the rule"
semantic_case "C8k SEMANTIC removed, generation + 1" 1 3 4 "$S1" ""
# C8m-C8s: a malformed SEMANTIC line WITH the correct +1 bump, so only the form rule can refuse it (a refusal through the
# generation rule would also hold with the form rule deleted). One case per loosening of the form regex.
semantic_case "C8m malformed (no arrow), generation + 1" 1 3 4 "" "SEMANTIC a.A.epoch changed the rule"
semantic_case "C8n malformed (value token missing), generation + 1" 1 3 4 "" "SEMANTIC a.A.epoch -> stale"
semantic_case "C8o malformed (trailing whitespace), generation + 1" 1 3 4 "" "$S1 "
semantic_case "C8p malformed (judgement start missing), generation + 1" 1 3 4 "" "SEMANTIC a.A.epoch  x -> y"
semantic_case "C8q malformed (no judgement), generation + 1" 1 3 4 "" "SEMANTIC a.A.epoch"
semantic_case "C8r malformed (bare SEMANTIC), generation + 1" 1 3 4 "" "SEMANTIC"
semantic_case "C8s malformed (lowercase), generation + 1" 1 3 4 "" "semantic a.A.epoch x -> y"
# C8t: a malformed line KEPT from the base is still malformed at HEAD (the form is checked on every HEAD line, not only added ones).
semantic_case "C8t malformed SEMANTIC kept from the base, same generation" 1 3 3 "SEMANTIC a.A.epoch changed the rule" "SEMANTIC a.A.epoch changed the rule"

# C9: the form rule shared with WireAssignmentTripwireTest. Each row of the case table is added to HEAD with the +1 bump and the
# verdict is read from the script: rc 0 = valid declaration; rc 1 naming "malformed SEMANTIC" = refused; any other rc 1 = ignored
# (not a declaration, so the bump is refused as no break). The Java test runs the same rows through its reader.
cases_table="$repo_root/aether/node/src/test/resources/wire-semantic-line-cases.tsv"
script_verdict() {
    local line="$1" out rc
    new_repo
    write_baseline 3 "${BASE_LINES[@]}"
    write_guard 3
    commit_all base
    write_baseline 4 "${BASE_LINES[@]}" "$line"
    commit_all head
    out="$("$check" "HEAD~1" 2>&1)"
    rc=$?
    case "$rc:$out" in
        0:*) echo valid ;;
        1:*"malformed SEMANTIC"*) echo refused ;;
        1:*) echo ignored ;;
        *) echo "rc$rc" ;;
    esac
}

table_rows=0
if [[ -f "$cases_table" ]]; then
    while IFS=$'\t' read -r want line; do
        [[ -z "$want" || "$want" == \#* ]] && continue
        line="${line//\\r/$'\r'}"
        line="${line//\\t/$'\t'}"
        line="${line//\\s/ }"
        table_rows=$((table_rows + 1))
        cases=$((cases + 1))
        got="$(script_verdict "$line")"
        if [[ "$got" == "$want" ]]; then
            echo "ok   C9 row $table_rows ($want) $(printf '%q' "$line")"
        else
            failures=$((failures + 1))
            echo "FAIL C9 row $table_rows: wanted $want, the script said $got for $(printf '%q' "$line")"
        fi
    done <"$cases_table"
fi
cases=$((cases + 1))
# The row count is pinned EXACTLY (data rows only, comments and blanks excluded), so deleting a row goes red; adding a row
# means raising this number here and in WireAssignmentTripwireTest.SEMANTIC_CASE_ROWS.
expected_table_rows=34
if [[ "$table_rows" -eq "$expected_table_rows" ]]; then
    echo "ok   C9 case table read: $table_rows rows"
else
    failures=$((failures + 1))
    echo "FAIL C9 case table $cases_table gave $table_rows rows ($expected_table_rows wanted); the shared form rule was NOT fully examined"
fi

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
