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
  otherwise) — that loader serves the package and lacks the class → the message names the loader, its jars and the
  artifact versions it has loaded, states BOTH causes that produce this state and picks NEITHER. Both probes are needed: `getDefinedPackage` is lazy, and a `[shared]`/`[infra]` jar nothing has
  loaded from yet is the DEFAULT state at factory-inspection time — with the lazy probe alone the same missing
  class read as "unserved" before the first load from that jar and "removed, rebuild" after it. Otherwise → new
  `SliceLoadingFailure.Fatal.DependencyClassNotOnClasspath(context, className, packageName, loaderChain)`: names
  the class and the referencing factory, states that no loader above the slice's serves the package (none has
  defined a class in it or holds its directory) and asserts NO cause, lists the whole loader chain with each
  loader's live URLs, and names every place a dependency jar can come from (`[slices]` → appended to the slice loader;
  `[shared]` → the shared loader, or the slice loader on a version conflict, or NOTHING when no repository has the
  artifact and it is registered runtime-provided; `[infra]` → the shared loader). Array descriptors
  (`[La/b/C;`, any depth) are unwrapped to the element class before discrimination; `a/b/C` and `Type a.b.C not
  present` are normalised as before.
- **"Served but lacking" does not identify a cause, so the message no longer asserts one** (round-3 BLOCKING). The
  same state is reached by a class REMOVED in a runtime/artifact upgrade — where a rebuild is right — and by the
  serving loader holding a DIFFERENT VERSION of the artifact than the slice was built against, where a rebuild is
  exactly wrong and would not even compile. `SharedLibraryClassLoader.addArtifact` keeps the first version loaded
  for a `groupId:artifactId` and ignores every later one, so the second shape is reachable by design
  [mechanism: `addArtifact`'s `loadedArtifacts.containsKey` guard]. Nothing observable at
  `SliceFactory.classLoadFailure` separates them, so `servedPackageLacksClass` states the evidence — which loader
  serves the package, its jars, and the versions it holds, from `SharedLibraryClassLoader.getLoadedArtifacts()` —
  names both remedies and leaves the choice to the operator, who alone holds the slice's own declaration
  [verified: `fails_namingTheLoadedVersion_whenTheSharedLoaderKeptTheFirstVersionOfTheArtifact` offers 1.0.0 then
  2.0.0, asserts in-run that only the 1.0.0 jar reached the loader's URLs, and requires the message to quote
  `org.example:provider:1.0.0`; `assertServedPackageDiagnosedWithoutChoosingACause` requires both causes and
  `doesNotContain("slice was compiled against an older runtime")`].
- **The two "class is present" arms cannot reach this diagnosis, and that is now pinned rather than assumed.** A
  class reachable from the slice's loader chain RESOLVES, so no resolution Throwable is raised: a jar appended
  after a failed attempt is picked up on the next one, and a parameter type's static initialiser does not run at
  `getDeclaredMethods` time [verified: `succeeds_whenTheReferencedClassIsInTheSliceOwnJar`, with
  `fails_namingTheUnservedPackage_whenTheSameReferencedClassIsOmittedFromTheSliceOwnJar` as its non-vacuity
  control — the same factory, the referenced class omitted, does fail]. A factory whose OWN static initialiser
  throws surfaces from `invokeFactory` carrying the real Throwable, never as a class-resolution verdict
  [verified: `fails_carryingTheRealCause_whenTheFactoryStaticInitialiserThrows` pins both attempts —
  `ExceptionInInitializerError` then `NoClassDefFoundError: Could not initialize class …`; the second is the text a
  widened `isMissingClass` would misparse into a fabricated class name, so the test is an enabled tripwire].
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
  serves → the four rebuild tests. Round 4, each run over the whole `aether/slice` module (799 `<testcase>`), the
  three sets mutually non-subset: the two-cause clause replaced by the old single-cause text → 4 RED
  (`…ServedByAJarNothingHasLoadedFrom`, `…ServedByAJarAClassWasLoadedFrom`,
  `fails_namingBothCauses_when_factory_parameter_type_missing`,
  `fails_namingTheLoadedVersion_whenTheSharedLoaderKeptTheFirstVersionOfTheArtifact`); `loadedArtifacts` forced
  empty → 4 RED (those same three less `…when_factory_parameter_type_missing`, whose serving loader tracks no
  artifacts, plus `names_theLoadedArtifactVersions_whenTheServingLoaderTracksThem`); a parent-first `getResource`
  in `serves` → `fails_namingTheOwningLoader_whenTheSharedLoaderAboveIsEmpty` ALONE, disjoint from both].
- **The owning loader is found with `findResource`, not `getResource`, and that choice is now pinned** (round-3
  SHOULD-FIX-1). `getResource` is parent-first, so it credits a child with its parent's contents: with it, a slice
  whose missing class's package is served only by the application loader is told the package is served by the
  shared loader — which holds no urls at all [verified:
  `fails_namingTheOwningLoader_whenTheSharedLoaderAboveIsEmpty` asserts in-run that the shared loader's urls are
  empty and that the application loader does hold the package, then requires the message not to name the shared
  loader].
- **The `verifyParameters` class-resolution guard is gone, with the reason recorded.** A `Method` holds its
  parameter classes already resolved (`getParameterTypes` clones a `Class<?>[]` the VM filled in
  `getDeclaredMethods0`), so an unresolvable parameter type surfaces in `getDeclaredMethods` — where both ghost
  tests fail — and can never reach that guard; it was unreachable and unpinnable.
- A jar written WITHOUT directory entries is invisible to the resource probe until a class has been loaded from it.
  Most build tools write them and not all do: this repo's own `com/h2database/h2/2.4.240/h2-2.4.240.jar` holds 1066
  class entries and zero directory entries [mechanism: counted over the jar's entry names; positive control
  `assertj-core-3.26.3.jar`, 840 class entries and 38 directory entries]. Such a jar falls to the unserved branch,
  which asserts no cause, so the gap costs detail and never a wrong verdict.
  `[unverified: a class in the default package gets the lazy probe only]`. Still
  only the FIRST unresolvable class is named:
  `getDeclaredMethods` fails once for the whole class, so the others are not enumerable from that failure.
