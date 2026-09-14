### Fixed (2026-09-14 — #758: `classLoadFailure` told every unresolvable class to "rebuild against this runtime version", including a missing dependency jar)
- **A class-resolution Throwable says WHICH class, never WHY.** `SliceFactory.classLoadFailure` mapped every
  `NoClassDefFoundError`/`ClassNotFoundException`/`TypeNotPresentException` to `ParameterMismatch(... "slice was
  compiled against an older runtime (references removed class X); rebuild against this runtime version")`. For the
  far more common cause — another slice's class whose jar never reached this slice's `SliceClassLoader` — that
  diagnosis is wrong and specific enough to be trusted [mechanism: `isMissingClass` → `incompatibleRuntime`
  regardless of the class's origin].
- **The OWNING LOADER is the discriminator, never the class name.** A name prefix cannot tell a runtime class from
  an application class scaffolded under the vendor namespace — the ticket's own `BuyTicketFactory` and
  `SeatSellability` are `org.pragmatica.example.ticketing.…`, as every `ticketing/` slice is. The slice loader
  chain is observable at failure time: if a loader strictly ABOVE the slice's own (the shared/infra loader, the
  runtime loader) has defined a class in the missing class's package (`ClassLoader.getDefinedPackage`), that loader
  serves the package and lacks the class → the rebuild message stays and names the loader. Otherwise nothing in
  evidence says the runtime ever had it → new `SliceLoadingFailure.Fatal.DependencyClassNotOnClasspath(context,
  className, packageName, loaderChain)`: names the class and the referencing factory, states that no loader above
  the slice's has defined the package so a rebuild will not help, lists the whole loader chain with each loader's
  live URLs, and names every place a dependency jar can come from (`[slices]` → appended to the slice loader;
  `[shared]` → the shared loader, or the slice loader on a version conflict, or NOTHING when no repository has the
  artifact and it is registered runtime-provided; `[infra]` → the shared loader). Array descriptors
  (`[La/b/C;`, any depth) are unwrapped to the element class before discrimination; `a/b/C` and `Type a.b.C not
  present` are normalised as before
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/SliceFactoryTest.java` —
  `fails_namingTheClassloaderGap_whenAnApplicationTypeUnderTheVendorPrefixIsMissing` (fixture
  `org.pragmatica.example.probe758.SeatSellabilityProbe`, red at `310aa560f` with the ticket's exact "rebuild"
  text), `…whenAnApplicationTypeIsMissing` (`com.example.ghost`), `…whenAnApplicationArrayTypeIsMissing`,
  `fails_with_rebuild_hint_when_factory_array_parameter_type_missing` (a runtime array type was inverted to
  "not a runtime class" at `310aa560f`), `fails_listingTheLiveLoaderChain_whenTheFactoryLoadsThroughASliceClassLoader`
  (real `SliceClassLoader` over a jar, parent `SharedLibraryClassLoader`, a jar appended after construction, both
  URLs listed); the pre-existing `fails_with_rebuild_hint_when_factory_parameter_type_missing` is the runtime half.
  Mutations, each reddening a distinct named set: prefix discriminator restored → the two vendor-prefix tests;
  array unwrap removed → the two array tests; slice loader itself counted as serving → the four app-type tests;
  `[infra]` dropped from the message → the three that assert it; serving loader never found → the two runtime
  tests].
- **The `verifyParameters` class-resolution guard is gone, with the reason recorded.** A `Method` holds its
  parameter classes already resolved (`getParameterTypes` clones a `Class<?>[]` the VM filled in
  `getDeclaredMethods0`), so an unresolvable parameter type surfaces in `getDeclaredMethods` — where both ghost
  tests fail — and can never reach that guard; it was unreachable and unpinnable.
- `[unverified: a runtime package nothing has loaded yet reads as unserved — getDefinedPackage sees only packages
  a loader has defined a class from — so a class removed from such a package falls into the dependency branch,
  which says what it checked rather than asserting a cause]`. Still only the FIRST unresolvable class is named:
  `getDeclaredMethods` fails once for the whole class, so the others are not enumerable from that failure.
