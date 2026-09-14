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
  runtime loader) SERVES the missing class's package — it has defined a class in it (`getDefinedPackage`) OR holds
  the package directory as a resource (`findResource` on its own URLs for a `URLClassLoader`, `getResource`
  otherwise) — that loader serves the package and lacks the class → the rebuild message stays and names the loader
  and its jars. Both probes are needed: `getDefinedPackage` is lazy, and a `[shared]`/`[infra]` jar nothing has
  loaded from yet is the DEFAULT state at factory-inspection time — with the lazy probe alone the same missing
  class read as "unserved" before the first load from that jar and "removed, rebuild" after it. Otherwise → new
  `SliceLoadingFailure.Fatal.DependencyClassNotOnClasspath(context, className, packageName, loaderChain)`: names
  the class and the referencing factory, states that no loader above the slice's serves the package (none has
  defined a class in it or holds its directory) and asserts NO cause, lists the whole loader chain with each
  loader's live URLs, and names every place a dependency jar can come from (`[slices]` → appended to the slice loader;
  `[shared]` → the shared loader, or the slice loader on a version conflict, or NOTHING when no repository has the
  artifact and it is registered runtime-provided; `[infra]` → the shared loader). Array descriptors
  (`[La/b/C;`, any depth) are unwrapped to the element class before discrimination; `a/b/C` and `Type a.b.C not
  present` are normalised as before
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/SliceFactoryTest.java` — the
  application fixtures are compiled at test time into OUT-OF-TREE jars (on the test classpath the application
  loader would serve their packages and the fixture would read as a runtime class) and loaded through a real
  `SliceClassLoader` over the consumer jar with a `SharedLibraryClassLoader` parent, the missing type genuinely
  absent from every loader. `fails_namingTheUnservedPackage_whenAnApplicationTypeUnderTheVendorPrefixIsMissing`
  (`org.pragmatica.example.probe758.SeatSellabilityProbe`, red at `310aa560f` with the ticket's exact "rebuild"
  text), `…whenAnApplicationTypeIsMissing` (`com.example.ghost`), `…whenAnApplicationArrayTypeIsMissing`,
  `fails_listingTheLiveLoaderChain_whenAJarWasAppendedAfterConstruction` (both URLs listed);
  `fails_with_rebuild_hint_whenTheMissingClassPackageIsServedByAJarNothingHasLoadedFrom` (provider jar in the
  shared loader holding the package but not the class, `getDefinedPackage` asserted null in-run — red at
  `6c03bde88` with the unserved text) and `…AJarAClassWasLoadedFrom` (same, after one class loaded — green at
  `6c03bde88`: the load-order flip); `fails_with_rebuild_hint_when_factory_array_parameter_type_missing` (a
  runtime array type was inverted to "not a runtime class" at `310aa560f`); the pre-existing
  `fails_with_rebuild_hint_when_factory_parameter_type_missing` is the runtime half. Mutations, each reddening a
  distinct named set: resource probe removed → exactly `…NothingHasLoadedFrom`; prefix discriminator restored →
  the two vendor-prefix unserved tests; array unwrap removed → the two array tests; slice loader itself counted as
  serving → all six jar-based tests; `[infra]` dropped from the message → the four unserved tests; no loader ever
  serves → the four rebuild tests].
- **The `verifyParameters` class-resolution guard is gone, with the reason recorded.** A `Method` holds its
  parameter classes already resolved (`getParameterTypes` clones a `Class<?>[]` the VM filled in
  `getDeclaredMethods0`), so an unresolvable parameter type surfaces in `getDeclaredMethods` — where both ghost
  tests fail — and can never reach that guard; it was unreachable and unpinnable.
- `[unverified: a jar written WITHOUT directory entries (Maven always writes them) is invisible to the resource
  probe until a class has been loaded from it; a class in the default package gets the lazy probe only]`. Still
  only the FIRST unresolvable class is named:
  `getDeclaredMethods` fails once for the whole class, so the others are not enumerable from that failure.
