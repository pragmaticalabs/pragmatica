### Changed (2026-11-27 — #3674: pg-parser DDL error positions)
- DDL parse errors report the line and column of the furthest failing token; the position is now pinned exactly by `ParseErrorPositionTest` [verified: aether/pg-tools/pg-parser/src/test/java/org/pragmatica/aether/pg/parser/ParseErrorPositionTest.java].
