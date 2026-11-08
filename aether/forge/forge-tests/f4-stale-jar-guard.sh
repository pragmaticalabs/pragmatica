#!/bin/bash
# F4 stale-jar guard (#2763). Usage: f4-stale-jar-guard.sh <checkout> [module-dir ...]
# Fails (exit 1, "ERROR stale-jar") unless each product jar the forge cluster loads from the checkout's own
# .m2-local was built from the checked-out sources: (1) the installed -sources.jar holds every tracked
# src/main/java file byte-identical to the working tree, (2) no tracked src/main file is newer than the main jar,
# (3) every tracked top-level source has its class in the main jar (class-presence control). Also prints HEAD.
# A forge-tests run WITHOUT -am resolves product jars from .m2-local, so an old install is invisible to the build.
co=${1:?checkout}; shift
mods=("$@"); [ ${#mods[@]} -gt 0 ] || mods=(aether/aether-deployment aether/node aether/aether-stream)
repo=$(grep -o 'maven.repo.local=[^ ]*' "$co/.mvn/maven.config" | cut -d= -f2); : "${repo:=$co/.m2-local}"
ver=$(sed -n 's:.*<version>\(1[^<]*\)</version>.*:\1:p' "$co/pom.xml" | head -1)
cd "$co" || exit 2
echo "GUARD head=$(git rev-parse --short=10 HEAD) repo=$repo version=$ver"
fail=0
for m in "${mods[@]}"; do
  art=$(sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p' "$m/pom.xml" | sed -n 2p)
  dir=$repo/org/pragmatica-lite/aether/$art/$ver
  jar=$dir/$art-$ver.jar; src=$dir/$art-$ver-sources.jar
  [ -f "$jar" ] && [ -f "$src" ] || { echo "ERROR stale-jar $m: missing $jar or sources jar"; fail=1; continue; }
  n=0; bad=0; nocls=0
  tmp=$(mktemp -d); (cd "$tmp" && unzip -q -o "$src" && unzip -Z1 "$jar" > classes.lst)
  while IFS= read -r f; do
    rel=${f#$m/src/main/java/}; n=$((n+1))
    case $rel in */package-info.java|package-info.java|module-info.java) continue;; esac
    cmp -s "$f" "$tmp/$rel" || { bad=$((bad+1)); echo "ERROR stale-jar $m: sources jar differs from tree: $rel"; }
    grep -qxF "${rel%.java}.class" "$tmp/classes.lst" || { nocls=$((nocls+1)); echo "ERROR stale-jar $m: no class in jar for $rel"; }
  done < <(git ls-files "$m/src/main/java" | grep '\.java$')
  newer=$(find "$m/src/main" -type f -newer "$jar" | head -3)
  rm -rf "$tmp"
  [ -z "$newer" ] || { echo "ERROR stale-jar $m: tracked file newer than jar: $newer"; bad=$((bad+1)); }
  echo "GUARD $m files=$n srcdiff+newer=$bad noclass=$nocls jar=$(stat -c %y "$jar" | cut -c1-19)"
  [ $n -gt 0 ] && [ $bad -eq 0 ] && [ $nocls -eq 0 ] || fail=1
done
[ $fail -eq 0 ] && echo "GUARD OK" || echo "GUARD FAILED"
exit $fail
