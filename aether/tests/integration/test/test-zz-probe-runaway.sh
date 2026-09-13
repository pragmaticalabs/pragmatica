#!/bin/bash
# PROBE (#1060, reverted next commit): recursion that must trip the runner's 300-process ceiling.
# Bounded at depth 400 (~800 processes) so a broken guard still cannot exhaust the runner.
depth=${1:-0}
[ "$depth" -lt 400 ] && bash "$0" $((depth + 1)) &
sleep 900
:
