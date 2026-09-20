### Fixed (2026-09-20 — #1336, #1181: one invalid stream binding silently dropped every binding in the blueprint)
- **`BlueprintService.streamBindings` folded `StreamResourceValidator`'s all-or-nothing result with
  `.or(List.of())`**, so ANY failing stream rule emptied the blueprint's whole `BlueprintStreamBindingsKey`
  entry — valid declarations included — and every stream slice then failed at load with a generic
  `UnboundStreamAlias` that named neither the offending section nor the rule. The refusal message was
  built and discarded (#1181 is the same fold). Found by the #1299 review probe.
- **Per-section rules now cost only their own alias, and are reported on the deploy response.** The
  validator gains a deploy-path pass, `StreamResourceValidator.partition`, over a new
  `StreamConfigParser.parseResourcesPartitioned` that keeps the per-section outcomes `parseResourcesAggregating`
  folds. `BlueprintService.publish*` answer a `PublishedBlueprint` — the stored blueprint plus
  `rejectedStreamBindings` (`field`/`rule`/`message` each) — and `POST /api/v1/blueprints`,
  `/blueprints/deploy` and `/blueprints/publish` carry that list as `BlueprintResponse.rejectedStreamBindings`
  [verified: `BlueprintPublishOwnershipTest.StreamBindings.publish_bindsTheValidStream_whenAnotherStreamIsInvalid`
  and its `publishFromArtifact_` twin — RED on the base with `Expecting actual: []`;
  `BlueprintDeployStatusTest.deployRoute_namesEveryRejectedStreamBinding_byFieldAndRule` for the route half].
- **The rule table.** GATING (the publish is refused with `422` and every failure found, nothing is
  applied): `resources-toml-parse` — the document does not parse, so no section has an outcome to keep;
  `blueprint-namespace-invalid`/`namespace-reserved` raised on the blueprint artifact while at least one
  stream is declared — the namespace prefixes every owned address, so no per-alias subset survives it
  (with no stream declared it is reported and gates nothing, so a stream-less blueprint that deployed
  before still deploys). PER-SECTION (that alias is not bound, the rest are, the failure is reported):
  the parser's `version-and-source-mutually-exclusive`, `producer-version-must-be-exact`,
  `version-or-source-required`, `stream-name-invalid`, `version-format-invalid`, `namespace-invalid`/
  `namespace-reserved` on an External source, `stream-resource-invalid` (partition ceiling, replication
  knobs, unparseable address), and #576's `inert-stream-config-key`/`inert-consumer-config-key`
  [verified: `StreamResourceValidatorPartitionTest`, 13 cases]. **#1282's `source-reserved-kind` is
  GATING** (ruled 2026-09-20): the management API refuses a reserved stream kind with a typed 4xx on
  every mint path, so a deploy refuses the same declaration the same way, naming the rule, rather than
  dropping the alias and letting the slice fail at load [verified:
  `BlueprintPublishOwnershipTest.StreamBindings.publish_isRefused_whenASourceNamesAReservedStreamKind`]. A slice using
  a rejected alias still fails to load naming that alias; the difference is that the operator was told
  which section and which rule at deploy time. **This supersedes the #547 entry's description of stream
  validation as "different, non-gating semantics"**: since #576 the rules refused, since #1181/#1336
  the refusal reaches someone, and the table above says which rules refuse the deploy.
- `StreamValidationFailures` now implements `HttpStatusAware` (`422`). It was not status-aware — moot
  while the deploy path swallowed it, load-bearing now that a gating rule refuses the publish
  [mechanism: `ProblemResponses.java` — `cause instanceof HttpStatusAware` decides the status, `500`
  otherwise].
- **Side effect, approved:** the body-path `conflicting-stream-declaration` refusal (#1066, one alias bound
  to two addresses by different slice jars) is the same `StreamValidationFailures` cause, so
  `POST /api/v1/blueprints` now answers it with `422` instead of `500`. The message is unchanged.
- The enabled #1181 tripwire `publish_withDescopedStreamKey_stillSucceedsWithEmptyBindings_KNOWN_DEFECT`
  is replaced by `publish_withDescopedStreamKey_dropsThatBinding_andNamesTheRuleOnTheAnswer`: an inert
  key is a per-section rule, so the publish proceeds without that alias and the answer names the key.
  `StreamResourceValidator.validate` (all-or-nothing) is kept for its tests and the in-flight #1299/#1301
  changes; it has no production caller after this change.
