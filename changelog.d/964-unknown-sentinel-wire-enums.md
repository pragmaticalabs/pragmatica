### Fixed (2026-09-08 — #964: adding a constant to any `@Codec` enum made un-upgraded nodes silently and permanently drop every message carrying it)
- **The generated read was `Type.values()[SliceCodec.readCompact(buf)]`, an unchecked array index.**
  An ordinal from a peer whose copy of the enum has more constants threw
  `ArrayIndexOutOfBoundsException`, and BOTH boundaries that see it contain the throw —
  `QuicLaneDataHandler.channelRead0` catches `Exception` and drops the message,
  `RabiaEngine.safeExecute` catches `RuntimeException` and abandons the round. The node stayed up,
  logged a generic "failed to deserialize", and discarded every message carrying the constant,
  forever. **Silence was the defect, not the throw.**
- **Every framework `@Codec` enum now declares `UNKNOWN` as its LAST constant, and `CodecProcessor`
  refuses to generate a codec for one that does not.** An unrecognised ordinal decodes to the
  sentinel and the rest of the message survives. Last is load-bearing rather than stylistic: a
  constant appended after `UNKNOWN`, or inserted before it, is read as `UNKNOWN` by an older node
  either way, whereas a sentinel in the middle silently remaps every constant after it onto other
  legitimate values.
- **Fail-closed handling at every consumer on an authorization or condemnation path**, because a
  robustness fix that lets an unreadable value read as a legitimate one is a security regression.
  `AuthorizationRole.hasAccess` refuses `UNKNOWN` on BOTH sides — as the *requirement* its highest
  ordinal would otherwise have satisfied every role and opened the route to everyone;
  `SecurityOverrideApplier` rejects the override; `RetentionPolicy.shouldEvict` never evicts;
  `SchemaRoutes` no longer starts a migration from a `default` arm; `HttpForwardMessage.Pipeline`
  routes `UNKNOWN` to NEITHER pipeline instead of defaulting onto the app one;
  `DeploymentMap.higherState` and `SwimProtocol.statePriority` make the sentinel LOSE their ordinal
  comparisons; `ClusterQuiescenceEvaluator` counts it as suspected/degraded, never healthy.
- **An unknown type TAG is still dropped — correctly — but is no longer silent.**
  `UnknownTypeTagException` separates version skew from a corrupt frame at the lane boundary, which
  logs it as skew and counts it as `quic_unknown_type_tag_drops_total`. Unknown ordinals are counted
  too (`SliceCodec.unknownEnumOrdinalCount()`) with a throttled WARN naming the enum.
- **Slice (application) enums are opt-in**: the sentinel when declared, otherwise a bounds-checked
  read failing with `UnknownEnumOrdinalException` naming the enum and ordinal, plus a compiler
  warning at the declaration. Forcing a wire concern onto every application enum is an API tax the
  ruling did not ask for.
- **`WireAssignmentTripwireTest` pins all 26 enum ordinal assignments and every registered type's
  tag** against a checked-in baseline, deriving the set from the live registries rather than a hand
  list. It is a TRIPWIRE while pre-GA freedom to add and remove is wanted; the class docstring names
  the one word that turns it into a hard gate at GA.
