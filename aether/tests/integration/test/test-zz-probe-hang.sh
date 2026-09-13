#!/bin/bash
# PROBE (#1060, reverted next commit): background grandchildren plus a sleep past the 300s per-suite limit.
for i in 1 2 3 4 5; do bash -c 'sleep 900; :' & done
sleep 900
