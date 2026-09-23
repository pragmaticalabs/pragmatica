### Fixed (2026-09-23 — #1450: the wire-assignment tripwire is no longer blind to record component changes)

- **The tripwire recorded tags and enum ordinals only, so a change to the SHAPE of an already-shipped
  record passed green.** `WireAssignmentTripwireTest` caught the two things its author thought of — a
  reassigned tag, a reordered enum — and reported success for a third that is equally breaking. On
  PR #1390's branch `CommunityMetricsSnapshot` gains two components under **shipped tag 86** and the
  gate passed. A green result meant "no tag or ordinal moved", never "the wire is compatible", and
  nothing in its output said so.
- **A third line kind, `SHAPE`, now pins every registered record's component names and types in
  declaration order** — derived from `getRecordComponents()`, the same order
  `CodecClassGenerator` walks when it emits the body. 271 SHAPE lines added to
  `wire-assignment-baseline.txt`; **no TAG and no ENUM line changed**, so the extension is strictly
  additive to the pins that already existed. The set stays DERIVED from the live registries, never
  listed, including the five hand-written `FrameworkCodecs` records whose shape is a proxy rather
  than the layout (said so in the docstring instead of excluding them).
- **Which shape changes fail was read off the generator, not assumed from what usually holds for
  record serialization.** The emitted body is a bare positional concatenation with **no per-field
  length anywhere** — the property that decides the rest, since without a length an unrecognised
  field cannot be skipped even in principle. So **add, remove, reorder and retype are all wire
  breaks**: an old reader stops N components early and, nested inside a list element or an enclosing
  record, desynchronises the stream rather than raising, while the reverse direction reads past the
  body. **Addition is the case worth naming** — it is tolerable under a tag-length-value or
  field-numbered codec, and this codec is neither.
- **A rename with type and position unchanged is byte-neutral and still fails the tripwire.**
  Component names never reach the wire, but swapping the names of two **same-typed** components
  inverts their meaning behind an identical byte layout, which a types-only column cannot see. The
  failure message labels the two kinds so a rename is not misread as a break.
- **The blind spot is now a mechanism rather than a caveat.** 16 registered types are neither records
  nor enums (14 JDK types, `Unit`, `TimeSpan`); their codec bodies are hand-written and reflection
  cannot state their shape. `theStructuralBlindSpot_didNotGrow` asserts that set **by name**, so a new
  unpinnable type reddens by name instead of joining the gap quietly behind a gate that still reports
  success. It pins the gap; it does not close it, and is not coverage of those 16 types.
- **Two instrument checks guard the new column**: a SHAPE-count floor (>= 265 of 271, space stated),
  and an empty-shape ceiling — if `getRecordComponents()` ever returned nothing, 271 lines would read
  `()`, compare equal to a baseline recorded the same way, and pin no layout at all. 5 registered
  records are genuinely component-less, so the ceiling is the discriminating assertion.
  [mechanism: extended `WireAssignmentTripwireTest`, 4 tests, `aether/node` — validated against the
  motivating case and against the pre-fix instrument, see below]
- **Validated by reproducing the defect, not only by firing.** Applying #1390's exact two components
  (copied verbatim from `74830210b`) takes the extended gate **RED** — one failure, in
  `wireAssignment_matchesTheRecordedBaseline`, with expected and actual differing *only* in the SHAPE
  line while **`TAG ... 86` stays byte-identical in both halves**, which is precisely why the tag-only
  gate had nothing to compare. The identical mutation against the **pre-fix instrument** restored to
  `c9b8b8406` passes **3/3 green, exit 0**. The existing checks still fire: a `SystemTags` pin swap
  (108 `Url` / 109 `Uuid`) reddens with the move shown, and an `EVENTUAL`/`STRONG` reorder in
  `ConsistencyMode` reddens with the ordinals shown.
- **Not claimed:** no cross-version decode was induced. The ruling above is read off the generator's
  emitted source and `SliceCodec.write`/`read`, so it states what the codec *cannot* do, not a
  measured runtime failure. Whether #1390's reshape is intended — and whether it needs a new tag
  rather than a reshape of 86 — is a design call for the hierarchy batch and is deliberately untouched
  here.
