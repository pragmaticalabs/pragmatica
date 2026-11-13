#!/usr/bin/env bash
# Self-test of the extractor on a copy of a source root: each broken fragment must be refused with a clear message,
# and a fragment edit must appear in the extracted module. Usage: tools/tla/selftest.sh <Module> <root> [<java-file>]
# The java file (default: the first, by path, that is not package-info.java and carries a fragment of <Module>) must
# hold at least two fragments of <Module>; the probes edit its second one.
set -uo pipefail
module=$1; root=$2
victim=${3:-$(grep -rc --include='*.java' "/// \`\`\`tla $module:" "$root" | grep -v '/package-info\.java:' | awk -F: '$NF >= 2 { print $1 }' | sort | head -1)}
[[ -n $victim && $(grep -c "/// \`\`\`tla $module:" "$victim") -ge 2 ]] || { echo "selftest: ERROR no source file with two $module fragments under $root" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
jar=${TLA_JAR:-$HOME/tools/tla/tla2tools.jar}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
fail=0
fresh() { rm -rf "$work/src"; mkdir -p "$work/src"; cp -R "$root/." "$work/src/"; }
extract() { python3 "$here/extract.py" --module "$module" --out "$work/out" --base "$work/src" "$work/src" 2>&1; }
expect() { # expect <name> <exit> <pattern> <output>
  if [[ $2 == "$5" ]] && grep -qE "$3" <<<"$4"; then echo "PASS $1: $(grep -E "$3" <<<"$4" | head -1)"
  else echo "FAIL $1 (exit $5): $4"; fail=1; fi; }
v="$work/src/${victim#"$root"/}"

fresh; out=$(extract); expect control 0 "^extract: $module.tla" "$out" $?

fresh; first=$(grep -n '/// ```tla '"$module"':' "$v" | sed -n 2p | cut -d: -f1)
close=$(awk -v s="$first" 'NR > s && /\/\/\/ ```$/ { print NR; exit }' "$v")
sed -i.bak "${close}d" "$v"; out=$(extract); expect unclosed 2 'is not closed' "$out" $?

fresh; sed -i.bak "${first}s/:\([A-Za-z_]*\)\$/ \1/" "$v"; out=$(extract); expect badtag 2 'is not <Module>:<Part>' "$out" $?

fresh; name=$(sed -n "${first}p" "$v" | sed -E 's/.*:([A-Za-z_]+)$/\1/')
sed -i.bak "${first}s/:${name}\$/:${name}Orphan/" "$v"; out=$(extract); expect undefined 2 'does not define an operator' "$out" $?

fresh; sed -i.bak "s/^\( *\)\/\/\/ ${name}\([ (=]\)/\1\/\/\/ ${name}Orphan\2/" "$v"
sed -i.bak "${first}s/:${name}\$/:${name}Orphan/" "$v"; out=$(extract); expect orphan 2 'never referenced' "$out" $?

fresh; sed -i.bak "$((first + 1)),\$s/^\( *\)\/\/\/ ${name}\([ (=].*\)==/\1\/\/\/ ${name}\2== UndefinedProbe \\/\\\\ /" "$v"
extract >/dev/null; out=$( (cd "$work/out" && java -cp "$jar" tla2sany.SANY "$module.tla") 2>&1); rc=$?
pos=$(grep -oE 'line [0-9]+, col [0-9]+' <<<"$out" | head -1 | grep -oE '[0-9]+' | head -1)
src=$(python3 "$here/extract.py" --module "$module" --out "$work/out" --base "$work/src" --locate "${pos:-1}" "$work/src")
want=$(grep -n 'UndefinedProbe' "$v" | cut -d: -f1)
grep -q 'Unknown operator' <<<"$out" && [[ $src == *"${victim##*/}:$want" ]] \
  && echo "PASS semantic: SANY rejects it at generated line $pos = $src (the edited line)" || { echo "FAIL semantic: src=$src want=$want $out"; fail=1; }

fresh; extract >/dev/null; cp "$work/out/$module.tla" "$work/before.tla"
sed -i.bak "$((first + 1))s|\$| \\\\* EDIT-MARKER|" "$v"; extract >/dev/null
d=$(diff "$work/before.tla" "$work/out/$module.tla")
[[ $(grep -c '^>' <<<"$d") == 1 && $d == *EDIT-MARKER* ]] && echo "PASS edit: one generated line changed: $(grep '^>' <<<"$d")" \
  || { echo "FAIL edit: $d"; fail=1; }

echo "selftest: $([[ $fail == 0 ]] && echo PASS || echo FAIL)"; exit $fail
