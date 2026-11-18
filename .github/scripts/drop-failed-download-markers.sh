#!/usr/bin/env bash
# #3338: Maven Resolver records a failed download in the local repository and then refuses to retry it ("was not found in ...
# during a previous attempt. This failure was cached in the local repository"). Two files carry the record:
#   <artifact>.<ext>.lastUpdated      one per failed artifact or pom (holds an `<repo>.error=` line)
#   resolver-status.properties        per group directory; a failed metadata check is an `<name>.error` entry
# A marker restored from a cache, or saved into one, turns one 429 into a failure of every later job, so both are removed
# before a build uses the repository and before the repository is saved. Entries recording a SUCCESSFUL metadata check
# (a timestamp only) are not markers and stay.
set -euo pipefail
repo="${1:-$HOME/.m2/repository}"
[ -d "$repo" ] || { echo "no repository at $repo"; exit 0; }
n=$(find "$repo" -name '*.lastUpdated' -type f -print -delete | wc -l)
s=0
while IFS= read -r f; do
  rm -f "$f"; s=$((s + 1))
done < <(find "$repo" -name 'resolver-status.properties' -type f -exec grep -l '\.error=' {} +  2>/dev/null || true)
echo "dropped $n *.lastUpdated and $s resolver-status.properties failure marker file(s) from $repo"
