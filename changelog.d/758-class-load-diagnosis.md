### Fixed (2026-09-14 — #758: `classLoadFailure` told every unresolvable class to "rebuild against this runtime version", including a missing dependency jar)
- **A class-resolution Throwable says WHICH class, never WHY.** `SliceFactory.classLoadFailure` mapped every
  `NoClassDefFoundError`/`ClassNotFoundException`/`TypeNotPresentException` to `ParameterMismatch(... "slice was
  compiled against an older runtime (references removed class X); rebuild against this runtime version")`. For the
  far more common cause — another slice's class whose jar never reached this slice's `SliceClassLoader` — that
  diagnosis is wrong and specific enough to be trusted: seven investigations followed it to a rebuild that could
  not help [mechanism: `isMissingClass` → `incompatibleRuntime` regardless of the class's origin].
- **The origin is the discriminator.** A missing class in the runtime namespace (`org.pragmatica.`) is unresolvable
  from a loader whose parent IS the runtime only because the runtime no longer has it → the rebuild message stays,
  unchanged. Any other class is an application type that never reached the slice's loader → new
  `SliceLoadingFailure.Fatal.DependencyClassNotOnClasspath(context, className, classpath)`: names the class and the
  referencing factory, states it is not a runtime class and a rebuild will not help, lists the slice loader's URLs,
  and points at the `[slices]` section of `META-INF/dependencies/<FactoryClass>`. The `verifyParameters` arm now
  goes through the same discriminator instead of asserting "rebuild" directly. Class names are normalised to
  binary form (`a/b/C` from `NoClassDefFoundError`, `Type a.b.C not present` from `TypeNotPresentException`)
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/SliceFactoryTest.java`
  `fails_namingTheClassloaderGap_whenAnApplicationTypeIsMissing` — a `com.example.ghost` factory whose parameter
  type is hidden from its loader; red at the base with the exact "references removed class
  com/example/ghost/GhostProviderType … rebuild" text; the pre-existing
  `fails_with_rebuild_hint_when_factory_parameter_type_missing` (runtime-namespaced `GhostAspect`) is the other
  half. Reverting the hunk reddens the new test only; inverting the discriminator reddens both].
- Still only the FIRST unresolvable class is named: `getDeclaredMethods` fails once for the whole class, so the
  others are not enumerable from that failure — stated in the code rather than left implied.
