### Fixed (2026-09-14 — #669: `Verify.Is` sign predicates compared via `doubleValue()`, so `nonNegative(-1E-400)` was true)
- **`Verify.Is.positive`/`negative`/`nonNegative`/`nonPositive` converted every operand through
  `doubleValue()` before comparing with zero.** A `BigDecimal` below double precision underflows to
  `±0.0` in that conversion, so `nonNegative(new BigDecimal("-1E-400"))` returned true for a strictly
  negative number and `positive(new BigDecimal("1E-400"))` returned false for a strictly positive one.
  [verified: `core/src/test/java/org/pragmatica/lang/VerifyTest.java` —
  `signPredicatesSeeBigDecimalBelowDoublePrecision`, red at `2005ea7d2`]
- The four predicates now take the sign from the operand's own domain: `BigDecimal` reports its own
  `signum()`; every other JDK `Number` keeps the primitive comparison, which is sign-exact for it — an
  integral value (`BigInteger` included) never rounds to zero or changes sign under `doubleValue()`,
  and float/double convert losslessly, so `-0.0` stays zero and NaN stays unordered.
  [mechanism: `Verify.Is.signum(Number)`]
  [verified: `signPredicatesHandleBigValuesBeyondDoubleRange`, `signPredicatesAreExactForIntegralExtremes`,
  `signPredicatesKeepFloatingPointEdgeSemantics` in the same test — regression pins, green before and after]
- No caller in this repository passes a `BigDecimal` to these predicates (repo-wide grep at
  `2005ea7d2`), so no in-tree behaviour changes; the fix is for downstream value objects such as the
  ticketing `Money`/`Percent`/`SeatLocation` sites named in the ticket. [unverified: downstream callers]
