#!/bin/bash
# Selftest of weekly-host.sh (#3659) against a fake ssh: the schedule picks the least-loaded free host, skips a host that runs TLC or is
# unreachable, refuses a second run while one is pending, reports a forced red row as RED with its name, a zero-row run as NOT-GREEN,
# a green run as GREEN, a stalled run as NOT-GREEN, and a week with no usable host as NOT-GREEN. Usage: tools/tla/weekly-host.selftest.sh
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
fix=$t/fix; mkdir -p "$fix" "$t/bin"
cat > "$t/bin/ssh" <<'SH'
#!/bin/bash
# fake ssh: args are `-o k=v -o k=v <host> <command>`; every answer comes from $FIX/<host>.*
while [[ $1 == -o ]]; do shift 2; done
h=$1; c=$2
case $c in
  *loadavg*) [[ -f $FIX/$h.probe ]] && cat "$FIX/$h.probe" || exit 255 ;;
  *bb-checkout*) [[ -f $FIX/$h.probe ]] || exit 255; echo "$c" > "$FIX/launched.$h" ;;
  *weekly.done*) [[ -f $FIX/$h.done ]] && cat "$FIX/$h.done" || exit 1 ;;
  *weekly.log*) cat "$FIX/$h.log" ;;
  *weekly-results.tsv*) cat "$FIX/$h.results" ;;
  *rm\ -rf*) echo "$c" > "$FIX/removed.$h" ;;
  *) echo "fake ssh: unexpected command: $c" >&2; exit 99 ;;
esac
SH
chmod +x "$t/bin/ssh"
export FIX=$fix TLA_WEEKLY_SSH=$t/bin/ssh TLA_WEEKLY_DIR=$t/state TLA_WEEKLY_SHA=0123456789abcdef0123456789abcdef01234567 TLA_WEEKLY_NOW=1800000000
fail=0
check() { if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected '$3' got '$2'"; fail=1; fi; }
last() { tail -1 "$t/state/verdict.tsv" | cut -f"$1"; }
probe() { printf '%s\n16\n%s\n' "$2" "$3" > "$fix/$1.probe"; }
HDR=$'commit\tdirty\tdate\tprofile\tmodule\tvariant\texpect\tactual\tviolated\tdistinct\ttlc\tverdict\tconstants'
res() { printf '%s\n' "$HDR"; while (( $# )); do printf 'c\tclean\td\tfull\t%s\t%s\tpass\tpass\t\t1\tt\t%s\tK=1\n' "$1" "$2" "$3"; shift 3; done; }
reset() { rm -rf "$t/state" "$fix"/*; }

# 1 the schedule fires: cloudbb-7 is the lowest load but already runs TLC, cloudbb-8 is unreachable (no probe file): cloudbb-6 wins
reset; probe cloudbb-5 3.2 free; probe cloudbb-6 0.8 free; probe cloudbb-7 0.0 busy
bash "$here/weekly-host.sh" start >/dev/null
check "start picks the least-loaded FREE host" "$(sed -n 's/^host=//p' "$t/state/run.json")" cloudbb-6
check "start launched run-host.sh full detached on it" "$(grep -c 'run-host.sh full' "$fix/launched.cloudbb-6")" 1
check "start detaches the run: setsid -f" "$(grep -c 'setsid -f bash -c' "$fix/launched.cloudbb-6")" 1
check "start detaches the run: stdin from /dev/null" "$(grep -c '</dev/null' "$fix/launched.cloudbb-6")" 1
check "start never touched the busy host" "$([[ -e $fix/launched.cloudbb-7 ]] && echo touched || echo untouched)" untouched
check "start never touched the unreachable host" "$([[ -e $fix/launched.cloudbb-8 ]] && echo touched || echo untouched)" untouched
bash "$here/weekly-host.sh" start | grep -q 'pending' ; check "a second start while one is pending is refused" "$?" 0
check "pending run writes no verdict" "$([[ -e $t/state/verdict.tsv ]] && echo some || echo none)" none
bash "$here/weekly-host.sh" collect >/dev/null
check "collect before the done marker writes no verdict" "$([[ -e $t/state/verdict.tsv ]] && echo some || echo none)" none

# 2 a forced red row is RED and named
printf '1\n' > "$fix/cloudbb-6.done"
printf 'run-host: profile=full rows=2 green=1 red=1 not-started=0 wall=9s RED\n' > "$fix/cloudbb-6.log"
res Fsm2Leader code_goals OK RabiaAdopt rjT_resync_savefail RED > "$fix/cloudbb-6.results"
bash "$here/weekly-host.sh" collect >/dev/null
check "forced red row: status" "$(last 5)" RED
check "forced red row: named" "$(last 7)" RabiaAdopt/rjT_resync_savefail
check "collect removed the remote checkout" "$([[ -e $fix/removed.cloudbb-6 ]] && echo removed || echo kept)" removed
check "collect cleared the pending run" "$([[ -e $t/state/run.json ]] && echo pending || echo clear)" clear

# 3 a zero-row run (run-host exits 0, "nothing to run") is NOT-GREEN
reset; probe cloudbb-5 0.5 free; bash "$here/weekly-host.sh" start >/dev/null
printf '0\n' > "$fix/cloudbb-5.done"; printf "run-host: profile=full: no row over the hosted limit matches '.*' / '.*'; nothing to run\n" > "$fix/cloudbb-5.log"; : > "$fix/cloudbb-5.results"
bash "$here/weekly-host.sh" collect >/dev/null
check "zero rows: status" "$(last 5)" NOT-GREEN
check "zero rows: reason" "$(last 8)" zero-rows

# 4 a green run is GREEN
reset; probe cloudbb-5 0.5 free; bash "$here/weekly-host.sh" start >/dev/null
printf '0\n' > "$fix/cloudbb-5.done"; printf 'run-host: profile=full rows=1 green=1 red=0 not-started=0 wall=9s GREEN\n' > "$fix/cloudbb-5.log"
res Fsm2Leader code_goals OK > "$fix/cloudbb-5.results"; bash "$here/weekly-host.sh" collect >/dev/null
check "green run: status" "$(last 5)" GREEN
check "green run: rows" "$(last 6)" 1

# 5 a run that never finishes is NOT-GREEN stalled
reset; probe cloudbb-5 0.5 free; bash "$here/weekly-host.sh" start >/dev/null
TLA_WEEKLY_NOW=1800200000 bash "$here/weekly-host.sh" collect >/dev/null
check "stalled run: status/reason" "$(last 5)/$(last 8)" NOT-GREEN/stalled
check "stalled run clears the pending marker" "$([[ -e $t/state/run.json ]] && echo pending || echo clear)" clear

# 6 no usable host: NOT-GREEN, not silence
reset; probe cloudbb-5 0.5 busy; bash "$here/weekly-host.sh" start >/dev/null
check "no free host: status/reason" "$(last 5)/$(last 8)" NOT-GREEN/no-free-host
check "no free host: no pending run" "$([[ -e $t/state/run.json ]] && echo pending || echo clear)" clear

# 7 install / uninstall into a scratch LaunchAgents dir with a fake launchctl (install-weekly.sh)
cat > "$t/bin/launchctl" <<'SH'
#!/bin/bash
echo "$*" >> "$FIX/launchctl.log"
SH
chmod +x "$t/bin/launchctl"; reset
inst() { LAUNCHCTL=$t/bin/launchctl LAUNCH_DIR=$t/la TLA_WEEKLY_DIR=$t/wstate bash "$here/install-weekly.sh" "$@"; }
inst >/dev/null
check "install writes both plists" "$(ls "$t/la" | wc -l | tr -d ' ')" 2
check "install bootstraps both agents" "$(grep -c '^bootstrap' "$fix/launchctl.log")" 2
inst >/dev/null; : > "$fix/launchctl.log"; inst --uninstall --dry-run >/dev/null
check "uninstall --dry-run removes nothing" "$(ls "$t/la" | wc -l | tr -d ' ')" 2
check "uninstall --dry-run boots nothing out" "$(grep -c "^bootout" "$fix/launchctl.log")" 0
: > "$fix/launchctl.log"; inst --uninstall >/dev/null
check "uninstall removes both plists" "$(ls "$t/la" | wc -l | tr -d ' ')" 0
check "uninstall boots both agents out" "$(grep -c '^bootout' "$fix/launchctl.log")" 2
check "uninstall keeps the state dir" "$([[ -f $t/wstate/installed ]] && echo kept || echo lost)" kept
inst --uninstall >/dev/null; check "uninstall is idempotent" "$?" 0
exit $fail
