### Fixed (2026-10-07 — #1973: an entity read after an owner kill answered HTTP 200 outcome:failed with an untyped refusal)
- **An entity owner-forward the transport refused at send, or whose request budget was already spent, is now a typed transient, `EntityOwnerForward.ForwardNotSent`.**
  Both provably delivered nothing, so a retry is safe. It was an untyped `Causes.cause(...)`: an app route answered 500 and the 02w fixture's reporter answered
  `200 {"outcome":"failed","failureType":"simpleCause"}`, which a client or the harness read as a final failure. A `Cause.Transient` answers 503 through the
  existing app-route mappers (#1737); the message says "safe to retry". The 02w harness allow-list gains `ForwardNotSent`.
- The owner refusing an arrived-expired command without touching the entity (`ForwardBudgetExhausted` over the wire) is retyped to the same transient instead of the terminal `ForwardRefused` carrier.
- Unchanged on purpose: a forward that was SENT and timed out has an unknown outcome (the owner may have applied it), stays non-transient and answers 500; a blind retry could double-apply.
- Not changed: owner resolution still names the COMMITTED owner, dead or not, until ownership is re-minted; dropping it would fall to the non-transient `NotCurrentOwner`.
  `[verified: EntityForwardServiceTest, EntityOwnerForwardTest, AppHttpServerLocalRouteFailureStatusTest (real service cause through the real HTTP path: 503 + body, timed-out 500)]`
