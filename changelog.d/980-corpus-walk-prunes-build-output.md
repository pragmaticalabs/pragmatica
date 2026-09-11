### Fixed (2026-09-11 — #980: the SQL corpus walk no longer enters concurrently-written build output)
- **`pg-parser`'s repo-wide corpus walk pruned at the walker instead of filtering afterwards.** The
  walk enumerated the whole repository and discarded `target/` and `.git/` entries with downstream
  `Stream.filter` calls, which cannot help: the race is between enumeration and open, and the filter
  runs only after `Files.walk` has already opened and listed the directory. `.mvn/maven.config`
  carries `-T 1C`, so CI runs a module-parallel reactor in which sibling modules create and delete
  files under `*/target/surefire-reports/` while the walk runs — a file present at enumeration and
  gone at open failed the build inside `pg-parser`, naming a module the author had never touched.
  `SqlCorpus` now walks with `Files.walkFileTree` and returns `SKIP_SUBTREE` from
  `preVisitDirectory`, so those subtrees are never opened at all. `.m2-local` is pruned too: this
  workspace's `.mvn/maven.config` places the tree-local Maven repository inside the repo root and
  nothing excluded it before.
- **Measured, on this tree:** the old walk enumerated 36,893 entries, of which 29,519 (80.0%) sat
  under `target/` or `.m2-local/` only to be thrown away — 5,194 directory opens the corpus never
  needed. The corpus itself is unchanged: old and new enumerations agree element-for-element on all
  38 files, in the same order.
- `visitFileFailed` is deliberately not overridden, so an unreadable directory in the **source** space
  still fails the build loudly rather than silently shrinking the corpus.
