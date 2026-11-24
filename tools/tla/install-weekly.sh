#!/bin/bash
# Installs the weekly host-only TLC run (#3659) on the Mac: two launchd agents (start: Sunday 02:00; collect: every 10 min) and the state
# dir with its `installed` epoch, which the router's TLC-MISSING counts its 8 days from. Run by the CTO after review, from a tree that stays.
# Usage: tools/tla/install-weekly.sh [--dry-run] [--uninstall]   Env: TLA_WEEKLY_DIR (state dir), LAUNCH_DIR (default ~/Library/LaunchAgents),
# LAUNCHCTL (the launchctl command; the selftest puts a fake). --uninstall boots both agents out and removes their plists; it keeps the state
# dir (installed, verdict.tsv) and is idempotent. With --dry-run it only prints.
# Idempotent: reinstalling rewrites the plists and reloads them; it never resets `installed` or touches verdict.tsv.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dry=0; un=0
for a in "$@"; do case $a in --dry-run) dry=1 ;; --uninstall) un=1 ;; *) echo "usage: $0 [--dry-run] [--uninstall]" >&2; exit 2 ;; esac; done
state=${TLA_WEEKLY_DIR:-$HOME/IdeaProjects/oss/internal/sweep-state/tlc-weekly}
launch=${LAUNCH_DIR:-$HOME/Library/LaunchAgents}
lctl=${LAUNCHCTL:-launchctl}
do_() { if (( dry )); then echo "dry-run: $*"; else "$@"; fi; }
if (( un )); then
  for part in start collect; do
    label=com.pragmatica.tla-weekly-$part
    do_ "$lctl" bootout "gui/$(id -u)/$label" 2>/dev/null || true
    do_ rm -f "$launch/$label.plist"
    (( dry )) || echo "uninstalled $label"
  done
  exit 0
fi
do_ mkdir -p "$state/runs" "$launch" "$HOME/Library/Logs"
[[ -f $state/installed ]] || { if (( dry )); then echo "dry-run: write $state/installed"; else date +%s > "$state/installed"; fi; }
for part in start collect; do
  label=com.pragmatica.tla-weekly-$part; out=$launch/$label.plist
  if (( dry )); then echo "dry-run: render $label.plist.example -> $out (TOOLS=$here)"; continue; fi
  sed -e "s|@TOOLS@|$here|g" -e "s|@HOME@|$HOME|g" "$here/$label.plist.example" > "$out"
  plutil -lint "$out" >/dev/null
  "$lctl" bootout "gui/$(id -u)/$label" 2>/dev/null || true
  "$lctl" bootstrap "gui/$(id -u)" "$out"
  echo "installed $label"
done
