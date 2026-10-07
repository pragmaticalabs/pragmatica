### Fixed (2026-10-07 — #1973: an entity read after an owner kill answered HTTP 200 outcome:failed with an untyped refusal)
- **An entity owner-forward the transport refused at send, or whose request budget was already spent, is now a typed transient, `EntityOwnerForward.ForwardNotSent`.**
  Both provably delivered nothing, so a retry is safe. It was an untyped `Causes.cause(...)`: an app route answered 500 and the 02w fixture's reporter answered
  `200 {"outcome":"failed","failureType":"simpleCause"}`, which a client or the harness read as a final failure. A `Cause.Transient` answers 503 through the
  existing app-route mappers (#1737); the message says "safe to retry". The 02w harness allow-list gains `ForwardNotSent`.
- The owner refusing an arrived-expired command without touching the entity (`ForwardBudgetExhausted` over the wire) is retyped to the same transient instead of the terminal `ForwardRefused` carrier.
- A forward that was SENT and timed out is now typed too, `EntityOwnerForward.ForwardTimedOut`, `Cause.Transient` and therefore 503 like every app-route timeout (owner ruling 2026-09-30, bcfb04232: 503 is "transient, retry later", not "not executed").
  Its body says the outcome is UNKNOWN and the command may have been applied, in different words from `ForwardNotSent`'s "safe to retry", so the two 503s are told apart by the body. The 02w harness allow-lists `ForwardNotSent` only, with a producer tripwire (`test-entity-create-retry.sh` T1-T3).
- Not changed: owner resolution still names the COMMITTED owner, dead or not, until ownership is re-minted; dropping it would fall to the non-transient `NotCurrentOwner`.
  `[verified: EntityForwardServiceTest, EntityOwnerForwardTest, AppHttpServerLocalRouteFailureStatusTest (real service cause through the real HTTP path: 503 + body, timed-out 500)]`
