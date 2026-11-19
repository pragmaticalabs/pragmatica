#!/bin/bash
# test-no-unregistered-forks.sh — stubs only. Enforcement for the #3427 invariant (know 7130de88eb): every LOCAL background fork in
# aether/tests/integration goes through the process registry (lib/common.sh proc_track / proc_start) and is ended by recorded pid;
# no raw `kill "$X"` of a harness fork exists outside the registry helper.
#   F1  instrument controls on a synthetic tree (a check that cannot go red proves nothing): an unregistered fork, a raw kill,
#       setsid/nohup/coproc/disown, pkill are flagged; a tracked fork, `&&`, `2>&1`, `&>`, `<&`, comments, `kill -0`, a self
#       `kill "$$"`, a kill inside a proc_* function and a marked remote line are not; an unused marker is flagged.
#   F2  the real tree: no unregistered fork, no raw kill, no unused marker. Every marker honoured is listed.
# Detection (v3427e filter): strip `&&`, `N>&M`, `&>`, `<&M`; skip comment lines. A fork is `&` ending a command (before `;` `)` `}` `#`
# or end of line) or setsid|nohup|coproc|disown. It is registered when proc_track/proc_start appears on that line or within the next
# REG_WINDOW lines. A kill is `kill|pkill|killall` in command position; `kill -0` (probe) and `kill ... "$$"` (the script aborting
# itself) are not kills of a harness fork; the registry helper (functions proc_*, _proc_*) is the one place allowed to signal.
# Remote forks (they run on the test host, not here) are exempt ONLY by `# proc-registry: remote` on the line or on a comment line
# up to MARK_WINDOW lines above it (a multi-line quoted remote command cannot carry a comment inside).
# Stated limit: the marker is an assertion the scan cannot check. A marker put on a LOCAL fork is honoured (mutation M3, t3427n: green) and
#   listed under "markers honoured" for review; only a reader of that list, or the line staying inside remote_exec, makes it true.
# Search space: every *.sh under aether/tests/integration EXCEPT test/ (these stubs and their fixtures, which fork on purpose).
# Mutations: delete a site's proc_track -> F2 names that site; add an unmarked `sleep 1 &` to a suite -> F2 names it.
#   INTEG_DIR_UNDER_TEST selects another copy.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

# scan_file <path> <label>: prints FORK/KILL/MARK/UNUSED-MARK lines for one file.
scan_file() {
    awk -v LABEL="$2" -v REG_WINDOW=12 -v MARK_WINDOW=6 '
    { L[NR] = $0 }
    function is_comment(s) { return s ~ /^[ \t]*#/ }
    function strip(s) { gsub(/&&/, "", s); gsub(/[0-9]*>&[0-9-]*/, "", s); gsub(/&>>?/, "", s); gsub(/<&[0-9-]*/, "", s); return s }
    # marker covering line i: on the line itself or on a comment line up to MARK_WINDOW above; returns its line number or 0
    function marker_for(i,   j) {
        if (L[i] ~ /# proc-registry: remote/) return i
        for (j = i - 1; j >= 1 && j >= i - MARK_WINDOW; j--) if (L[j] ~ /^[ \t]*# proc-registry: remote/) return j
        return 0
    }
    function registered(i,   j) {
        for (j = i; j <= NR && j <= i + REG_WINDOW; j++) if (!is_comment(L[j]) && L[j] ~ /proc_track|proc_start/) return 1
        return 0
    }
    function report(kind, i,   m, t) {
        m = marker_for(i)
        t = L[i]; sub(/^[ \t]+/, "", t)
        if (m) { used[m] = 1; print "MARK " LABEL ":" m " covers " kind " at " LABEL ":" i ": " substr(t, 1, 90) }
        else print kind " " LABEL ":" i ": " substr(t, 1, 110)
    }
    END {
        fn = ""
        for (i = 1; i <= NR; i++) {
            raw = L[i]
            if (is_comment(raw)) continue
            if (raw ~ /^[A-Za-z_][A-Za-z0-9_]*\(\)[ \t]*\{/) { fn = raw; sub(/\(\).*/, "", fn) }
            else if (raw ~ /^\}/) fn = ""
            s = strip(raw)
            q = s; gsub(/"[^"]*"/, "", q); gsub(/\047[^\047]*\047/, "", q)   # quoted text cannot fork
            if (q ~ /&[ \t]*(;|\)|\}|#|$)/ || q ~ /&[ \t]+[A-Za-z_$(]/ || s ~ /(^|[^A-Za-z0-9_.\/-])(setsid|nohup|coproc|disown)([ \t]|$)/) {
                if (!registered(i)) report("FORK", i)
            }
            # kill in command position
            t = raw; gsub(/&&|\|\|/, ";", t)   # && and || separate commands: a kill after them is in command position
            while (match(t, /(^|[;&|(){]|(^|[ \t])(then|do|else|if|elif|while|until|!))[ \t]*(kill|pkill|killall)[ \t]/)) {
                cmd = substr(t, RSTART, RLENGTH); isp = (cmd ~ /pkill|killall/)
                a = substr(t, RSTART + RLENGTH); t = a
                probe = 0; self = 0
                if (!isp) {
                    while (a ~ /^(-[A-Za-z0-9]+|--)([ \t]|$)/) { if (a ~ /^-0([ \t]|$)/) probe = 1; sub(/^[^ \t]+[ \t]*/, "", a) }
                    if (a ~ /^"?\$\$"?([ \t]|$)/) self = 1
                    if (a !~ /^"?\$/) probe = 1      # not a variable operand: a literal pid or job, not a harness fork held in a variable
                }
                if (probe || self) continue
                if (fn ~ /^_?proc_/) continue
                report("KILL", i)
            }
        }
        for (j = 1; j <= NR; j++) if (L[j] ~ /# proc-registry: remote/ && !(j in used)) print "UNUSED-MARK " LABEL ":" j ": " substr(L[j], 1, 90)
    }' "$1"
}

# scan_tree <dir>: scans every *.sh outside test/, label = path relative to dir. Prints the findings and a final "SCANNED n".
scan_tree() {
    local dir="$1" f n=0
    while IFS= read -r f; do
        n=$((n + 1))
        scan_file "$f" "${f#"$dir"/}"
    done < <(find "$dir" -name '*.sh' -not -path '*/test/*' | LC_ALL=C sort)
    echo "SCANNED $n"
}

# ---- F1: instrument controls --------------------------------------------------------------------------------------------------
FX="$WORK/fx"; mkdir -p "$FX/lib" "$FX/suites" "$FX/test"
cat > "$FX/suites/bad.sh" <<'EOF'
true &
setsid foo
nohup bar >/dev/null
coproc baz { :; }
qux & disown
kill "$PID" 2>/dev/null
kill -TERM $other
pkill -f thing
( run ) &
true & echo $!
echo "Tom & Jerry"
if kill "$A" 2>/dev/null; then :; fi
case $x in a) kill "$C" ;; esac
ok_x && kill "$B"
EOF
cat > "$FX/suites/good.sh" <<'EOF'
# sleep 1 &  a comment is not a fork
a && b
cmd 2>&1
cmd >/dev/null 2>&1
cmd &> /dev/null
cat <&3
foo & bar_pid=$!; proc_track "$bar_pid"
kill -0 "$PID" 2>/dev/null
kill -TERM "$$"
docker kill ${name}
echo "a=1&b=2"
# proc-registry: remote
remote_exec "x; \
 nohup /tmp/thing >/dev/null 2>&1 & echo \$! > /tmp/p" || true
EOF
cat > "$FX/lib/helper.sh" <<'EOF'
proc_reap_fixture() { :; }
_proc_thing() {
    kill -TERM "$1" 2>/dev/null
}
other() {
    kill "$1"
}
x & # trailing comment
true & echo $!
proc_track "$!"
EOF
cat > "$FX/suites/unused.sh" <<'EOF'
# proc-registry: remote
echo nothing forks here
EOF
cat > "$FX/test/fixture.sh" <<'EOF'
true &
EOF
OUT=$(scan_tree "$FX")
want() { echo "$OUT" | grep -Eq "$1" && ok "F1 $2" || fail "F1 $2 (pattern [$1] absent from: $(echo "$OUT" | tr '\n' '|'))"; }
unwant() { echo "$OUT" | grep -Eq "$1" && fail "F1 $2 (pattern [$1] present: $(echo "$OUT" | grep -E "$1" | tr '\n' '|'))" || ok "F1 $2"; }
want 'FORK suites/bad.sh:1:' "unregistered fork (sleep 1 &) flagged"
want 'FORK suites/bad.sh:2:' "setsid flagged"
want 'FORK suites/bad.sh:3:' "nohup flagged"
want 'FORK suites/bad.sh:4:' "coproc flagged"
want 'FORK suites/bad.sh:5:' "disown flagged"
want 'KILL suites/bad.sh:6:' 'raw kill "$PID" flagged'
want 'KILL suites/bad.sh:7:' "raw kill -TERM \$other flagged"
want 'KILL suites/bad.sh:8:' "pkill flagged"
want 'FORK suites/bad.sh:9:' "( run ) & flagged"
want 'FORK suites/bad.sh:10:' "fork followed by more commands on the line (sleep 3 & echo) flagged"
want 'KILL suites/bad.sh:12:' "kill after if flagged"
want 'KILL suites/bad.sh:13:' "kill as a case-arm command flagged"
want 'KILL suites/bad.sh:14:' "kill after && flagged"
unwant 'suites/bad.sh:11:' "an & inside a quoted string is not a fork"
unwant '^[A-Z-]* suites/good.sh:([1-9]|1[01]):' "comment, &&, 2>&1, &>, <&3, tracked fork, kill -0, kill \"\$\$\", docker kill, a=1&b=2 not flagged"
want 'MARK suites/good.sh:12 covers FORK at suites/good.sh:14' "marker on a comment line above a continued remote command is honoured and listed"
unwant '^FORK suites/good.sh' "marked remote fork not reported as a finding"
want '^KILL lib/helper.sh:6:' "raw kill outside proc_* flagged"
unwant '^KILL lib/helper.sh:3:' "kill inside _proc_* helper not flagged"
unwant '^FORK lib/helper.sh:[78]:' "fork with a trailing comment, proc_track within the window, not flagged"
want 'UNUSED-MARK suites/unused.sh:1:' "a marker that covers nothing is flagged"
unwant 'test/fixture' "test/ is outside the search space"
want 'SCANNED 4' "scanned exactly the 4 non-test files"
# the window is a bound, not a licence: a proc_track more than REG_WINDOW lines later does not register the fork
{ echo 'sleep 9 &'; for _ in $(seq 1 13); do echo ':'; done; echo 'proc_track "$!"'; } > "$FX/suites/far.sh"
OUT=$(scan_tree "$FX"); want 'FORK suites/far.sh:1:' "a proc_track 14 lines after the fork does not register it"
{ echo 'sleep 9 &'; for _ in $(seq 1 11); do echo ':'; done; echo 'proc_track "$!"'; } > "$FX/suites/far.sh"
OUT=$(scan_tree "$FX"); unwant 'FORK suites/far.sh' "a proc_track 12 lines after the fork registers it"

# ---- F2: the real tree --------------------------------------------------------------------------------------------------------
OUT=$(scan_tree "$INTEG_DIR")
N=$(echo "$OUT" | sed -n 's/^SCANNED \([0-9]*\)$/\1/p')
[ "${N:-0}" -ge 40 ] && ok "F2 scanned ${N} files (search space: every *.sh under ${INTEG_DIR#"$(dirname "$INTEG_DIR")"/} except test/)" || fail "F2 scanned only ${N:-0} files, the scan reached too little"
for must in lib/common.sh lib/load.sh run-tests.sh; do
    [ -f "$INTEG_DIR/$must" ] && ok "F2 positive control: $must exists in the search space" || fail "F2 $must missing"
done
BAD=$(echo "$OUT" | grep -E '^(FORK|KILL|UNUSED-MARK) ' || true)
if [ -z "$BAD" ]; then ok "F2 no unregistered fork, no raw kill, no unused marker"
else fail "F2 $(echo "$BAD" | wc -l | tr -d ' ') finding(s):"; echo "$BAD" | sed 's/^/        /'; fi
MARKS=$(echo "$OUT" | grep '^MARK ' || true)
echo "  markers honoured: $(echo "$MARKS" | grep -c . || true)"
[ -n "$MARKS" ] && echo "$MARKS" | sed 's/^/        /'

echo "  passed: $PASS"; echo "  failed: $FAIL"
[ "$FAIL" -eq 0 ]
