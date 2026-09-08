#!/usr/bin/env bash
# Generates the Statistics table in the Aether feature catalog from the feature rows themselves.
#
#   scripts/catalog-stats.sh --check   # exit 1 when the generated block disagrees with the rows
#   scripts/catalog-stats.sh --write   # regenerate the block in place
#
# Why this is generated (#928). The table was hand-maintained and never agreed with the body: at its
# last hand edit (a43c04c70, 2026-07-16) it claimed 207 rows while the body already carried 221, and
# at 78960de46 the body carried 227 against the same frozen 207. Correcting the numbers once would
# leave the mechanism intact, so the numbers are derived instead.
#
# The counting rule is stated in the generated block, in the document, so a reader can reproduce the
# figure rather than trust it. The set of allowed statuses is read from the document's own status
# legend: a row whose status is not defined there is a hard failure, never a silent bucket. That is
# the #931 defect class -- row 58 carried `Critical`, a priority rather than a completion level, and
# a summary that silently dropped it would have under-counted with no remainder to notice.
#
# EVERY number and example in the emitted prose is interpolated from the parsed rows. A literal would
# be a hand-maintained figure inside a block stamped "generated", which is #928's own defect one level
# down -- and the check could never catch it, because generate() emits both sides of its comparison,
# so a literal agrees with itself by construction (PR #941 adversarial review, 2026-09-08).
set -euo pipefail

catalog="aether/docs/reference/feature-catalog.md"
begin="<!-- BEGIN GENERATED STATISTICS -->"
end="<!-- END GENERATED STATISTICS -->"

# The worked overclaim example quoted in the generated prose. Its name and status are read from the
# row; generation FAILS if the row disappears or stops carrying this status, so the illustration
# cannot go quietly stale. The code-side half of the claim (that `AlertForwarder` is never constructed
# in production) is a statement about the codebase, not the catalog, and is cited to #926.
example_row_id="39"
example_row_status="Complete"

mode="${1:---check}"
case "$mode" in
    --check|--write) ;;
    *) echo "usage: catalog-stats.sh [--check|--write]" >&2; exit 2 ;;
esac

root="$(git rev-parse --show-toplevel)"
cd "$root"
[[ -f "$catalog" ]] || { echo "catalog-stats: $catalog not found" >&2; exit 2; }

grep -qF "$begin" "$catalog" || { echo "catalog-stats: missing marker $begin in $catalog" >&2; exit 2; }
grep -qF "$end"   "$catalog" || { echo "catalog-stats: missing marker $end in $catalog" >&2; exit 2; }

# Emits the generated block (markers included) on stdout and "<rows> <statuses>" into $2, or exits
# non-zero naming what is wrong.
generate() {
    awk -v exampleId="$example_row_id" \
        -v exampleStatus="$example_row_status" \
        -v summaryfile="$1" '
    BEGIN { inLegend = 0; nLeg = 0; nRow = 0 }

    # --- the status legend is the authority for which statuses may appear ---
    /^\*\*Status legend:\*\*/ { inLegend = 1; next }
    inLegend && /^---/        { inLegend = 0 }
    inLegend && /^- \*\*/ {
        name = $0
        sub(/^- \*\*/, "", name)
        sub(/\*\*.*$/, "", name)
        legendOrder[++nLeg] = name
        isLegend[name] = 1
        next
    }

    # --- a feature row: pipe, an id of digits optionally followed by letters, pipe ---
    /^\|[ \t]*[0-9]+[a-zA-Z]*[ \t]*\|/ {
        nf = split($0, cell, "|")
        if (nf < 5) { bad[++nBad] = NR; next }
        id = cell[2]; gsub(/^[ \t]+|[ \t]+$/, "", id)
        nm = cell[3]; gsub(/^[ \t]+|[ \t]+$/, "", nm)
        s = cell[4]                      # third cell: the status
        gsub(/\*/, "", s)                # bold markers
        sub(/\(.*/, "", s)               # parenthetical qualifier
        gsub(/^[ \t]+|[ \t]+$/, "", s)   # surrounding space
        nRow++
        rowStatus[nRow] = s
        rowId[nRow] = id
        rowName[nRow] = nm
        rowLine[nRow] = NR
    }

    END {
        if (nLeg < 2) {
            print "catalog-stats: parsed " nLeg " status legend entries; the legend is the allowed-status authority and cannot be empty" > "/dev/stderr"
            exit 1
        }
        if (nRow == 0) {
            print "catalog-stats: parsed 0 feature rows; the counting rule matched nothing" > "/dev/stderr"
            exit 1
        }
        for (i = 1; i <= nBad; i++) {
            print "catalog-stats: malformed feature row at line " bad[i] > "/dev/stderr"
            fail = 1
        }
        for (i = 1; i <= nRow; i++) {
            if (!(rowStatus[i] in isLegend)) {
                print "catalog-stats: row " rowId[i] " (line " rowLine[i] ") has status \"" rowStatus[i] "\", which the status legend does not define" > "/dev/stderr"
                fail = 1
                continue
            }
            count[rowStatus[i]]++
            categorised++
        }
        if (fail) { exit 1 }
        if (categorised != nRow) {
            print "catalog-stats: " nRow " rows but " categorised " categorised" > "/dev/stderr"
            exit 1
        }

        # Tally and total BEFORE emitting, because the prose quotes the total.
        total = 0
        for (i = 1; i <= nLeg; i++) {
            n = (legendOrder[i] in count) ? count[legendOrder[i]] : 0
            tally[i] = n
            total += n
        }

        # Pin the worked example: refuse to publish an illustration the rows no longer support.
        for (i = 1; i <= nRow; i++) {
            if (rowId[i] == exampleId) { exFound = 1; exName = rowName[i]; exStatus = rowStatus[i]; break }
        }
        if (!exFound) {
            print "catalog-stats: the cited overclaim example, row " exampleId ", no longer exists; update example_row_id in scripts/catalog-stats.sh" > "/dev/stderr"
            exit 1
        }
        if (exStatus != exampleStatus) {
            print "catalog-stats: the cited overclaim example, row " exampleId ", now reads \"" exStatus "\" not \"" exampleStatus "\"; it no longer illustrates an overclaim -- update example_row_status in scripts/catalog-stats.sh" > "/dev/stderr"
            exit 1
        }

        print "<!-- BEGIN GENERATED STATISTICS -->"
        print "<!-- Generated by scripts/catalog-stats.sh -- edit the feature rows, not this block. -->"
        print ""
        print "**Counting rule.** A feature row is a line beginning with `|`, then an id of digits"
        print "optionally followed by letters, then `|`. Its status is the third cell with bold markers"
        print "and any parenthetical qualifier stripped, so `**Complete** (runtime engine)` counts as"
        print "`Complete`. The permitted statuses are exactly those defined in the status legend at the"
        print "top of this file; a row using any other status fails the check rather than being dropped."
        print "Regenerate with `scripts/catalog-stats.sh --write`; `--check` fails when this block and"
        print "the rows disagree."
        print ""
        print "**These counts are claimed, not verified.** Each one counts what a row in this catalog"
        print "*claims* about itself. Nothing here has been checked against the code, and the catalog is"
        printf "known to overclaim at row level -- row %s reads `%s | %s` while `AlertForwarder` is\n", exampleId, exName, exStatus
        printf "never constructed in production (#926). Read the total as \"%d rows asserting a\n", total
        printf "capability\", not as %d working capabilities.\n", total
        print ""
        print "| Status | Count |"
        print "|--------|-------|"
        for (i = 1; i <= nLeg; i++) { printf "| %s | %d |\n", legendOrder[i], tally[i] }
        printf "| Total | %d |\n", total
        print "<!-- END GENERATED STATISTICS -->"

        if (summaryfile != "") { printf "%d %d\n", total, nLeg > summaryfile }
    }
    ' "$catalog"
}

# Explicit XXXXXX template: GNU mktemp rejects `-t <prefix>` ("too few X's"), BSD mktemp rejects a
# bare `mktemp` with no template. This form is accepted by both.
blockfile="$(mktemp "${TMPDIR:-/tmp}/catalog-stats.XXXXXX")"
summaryfile="$(mktemp "${TMPDIR:-/tmp}/catalog-stats-sum.XXXXXX")"
trap 'rm -f "$blockfile" "$summaryfile"' EXIT
generate "$summaryfile" > "$blockfile"

# A green result must state the size of the set it examined, not just its exit status (#740, and the
# workspace rule it established). These come from the run that just produced the block.
read -r row_count status_count < "$summaryfile"
[[ "${row_count:-0}" -gt 0 ]] || { echo "catalog-stats: refusing to report success over 0 feature rows" >&2; exit 1; }

# Rebuild the file with the freshly generated block in place of the current one. The block is passed
# as a file, not with -v: BSD awk rejects a newline inside a -v assignment.
rebuilt="$(awk -v blkfile="$blockfile" -v b="$begin" -v e="$end" '
    index($0, b) { while ((getline line < blkfile) > 0) print line; skip = 1; next }
    index($0, e) { skip = 0; next }
    !skip        { print }
' "$catalog")"

if [[ "$mode" == "--write" ]]; then
    printf '%s\n' "$rebuilt" > "$catalog"
    echo "catalog-stats: wrote $catalog -- $row_count feature rows across $status_count statuses"
    exit 0
fi

if printf '%s\n' "$rebuilt" | diff -u "$catalog" - > /dev/null; then
    echo "catalog-stats: ok -- $row_count feature rows across $status_count statuses match the generated block"
    exit 0
fi

echo "catalog-stats: the Statistics block disagrees with the feature rows in $catalog" >&2
echo "catalog-stats: run 'scripts/catalog-stats.sh --write' and commit the result" >&2
printf '%s\n' "$rebuilt" | diff -u "$catalog" - >&2 || true
exit 1
