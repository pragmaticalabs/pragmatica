### Changed (2026-11-20 — #3458: a forwarded cause with no kind answers 503)
- A forwarded read cause that carries no kind used to answer 500 on the asking node; it now takes the row of `UNKNOWN` (503), the same as the node that raised it. [verified: StreamErrorStatusTest.AnswerStatus.aKindlessCause_answersTheSameStatusLocallyAndOnceForwarded]
