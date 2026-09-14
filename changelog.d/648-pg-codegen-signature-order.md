### Fixed (2026-09-14 — #648: pg-codegen reordered the generated method's SIGNATURE to SQL placeholder order, surfacing as "does not override" — or as a silent argument swap)
- **The cause was in the generator, not the contract.** `QueryAnnotationProcessor.analyzeQueryMethod` built the
  generated implementation's signature as `reorderedParams(originalParams, rewritten.parameterOrder())` — the
  placeholder order — while the interface declares the method in declaration order. For a differently typed pair
  (`rename(long id, String name)` against `SET name = :name WHERE id = :id`) javac reports the generated class
  *"is not abstract and does not override abstract method rename(long,java.lang.String)"*, three steps from the
  cause. For a SAME-typed pair (`reassign(String name, String email)` against `SET email = :email WHERE name =
  :name`) it compiled — with the parameter names swapped, so the caller's first argument landed in the variable
  named `email` and was bound to `$1`: the wrong value in the wrong placeholder, silently. The bind list was never
  the problem: it is emitted by parameter NAME in placeholder order [mechanism: `FactoryGenerator.
  appendMethodImplementations` emits `signatureParams`; the body binds `params` by `accessor()`].
- The generated signature is now the interface's declaration order, unconditionally; only the bind list follows
  the placeholders. Declaration order and placeholder order are therefore independent — the ticket's "bind by
  name" resolution, which the body already did — so no diagnostic is needed: there is no longer a contract to
  violate
  [verified: `aether/pg-tools/pg-codegen/src/test/java/org/pragmatica/aether/pg/codegen/processor/ParameterOrderIndependenceTest.java`
  — compiles the generated factory to bytecode against an interface whose two `@Query` methods declare parameters
  in the reverse of their placeholder order (one differently typed, one same-typed); asserts the build succeeds,
  both signatures keep declaration order, and both bind lists are `name, id` / `email, name`. Red at the base with
  the ticket's exact "does not override" diagnostic; reverting the hunk reddens it again].
- `examples/banking/account`'s `AccountPersistence` compiles unchanged (root build); its `updateStatus` was already
  written placeholder-first, which is why it never tripped this — it no longer needs to be.
