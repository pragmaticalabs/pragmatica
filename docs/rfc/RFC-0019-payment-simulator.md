---
RFC: 0019
Title: Payment simulator — a Stripe-compatible external-service imitator built as a slice
Status: Draft
Superseded-By:
Author: Sergiy Yevtushenko
Created: 2026-10-09
Updated: 2026-10-10
Affects: [aether, jbct, examples]
---

## Summary

A payment-service simulator that implements a subset of Stripe's public HTTP API and reproduces its *behaviour*: the PaymentIntent state machine, idempotent retries, signed asynchronous webhooks, declines, 3-D Secure, rate limits and latency. It is the first of a family of external-service imitators for functional, chaos and performance testing. It is written as an ordinary slice, so the same artifact runs on a single Terra instance, on its own Aether cluster, or inside Forge. It always runs as a deployment separate from the system under test.

## Motivation

Realistic testing of systems that take payments needs a counterparty that behaves like a real payment provider under load and under failure. None of the available options does:

| Option | Gap |
|---|---|
| The provider's own test mode | Rate-limited to tens of requests per second; latency includes the public internet and cannot be shaped. Unusable for load tests. |
| `stripe-mock` (official, OpenAPI-driven) | Stateless: returns fixtures. No state machine, no webhooks, no latency model, no failures. |
| Generic HTTP stubs (WireMock, Mockoon, …) | Every payment behaviour would have to be rebuilt on top of them. |

The defects that matter in payment integrations live in the failure paths. A charge succeeds, but the response is lost and the client retries. A webhook arrives twice, out of order, or before the API response. A 3-D Secure challenge never completes while the merchant holds inventory. A client retries a hard decline in a loop. A simulator that cannot produce these on demand, reproducibly, does not test what breaks.

Field data shapes the defaults. One issuer-side source gave these figures, per attempt, over a 10-day window (2026-10):
- 94% of attempts succeed.
- 4% are declined for card reasons (expired card, limits).
- About 2% stall in 3-D Secure.
- 80% of failures come from a single merchant.

Failures are therefore concentrated, not uniform. Because the figures count attempts, a merchant that retries declines inflates both its own failure count and the concentration. A uniform-random mock reproduces neither effect.

## Design

### Boundaries

- **Simulator slice.** It lives in a dedicated private repository under the Apache license, created when work on #2082 starts and made public later. It is a test tool first: no compatibility promise until it is promoted. Owns the API subset, the state machine, the outcome and fault model, idempotency, webhook delivery and the control API. It contains no runtime-specific code.
- **Runtimes.** Terra (single instance), an Aether cluster, and Forge run the same slice unchanged. The slice reaches storage and timers only through resource interfaces that every runtime supplies. A slice provides no storage or timing guarantee by itself: each resource states its guarantee per runtime, the guarantees differ between runtimes, and that is intended. The slice declares the minimum it requires of each resource (for example, an atomic per-key insert), and a runtime that cannot meet that floor refuses the slice at load time. See Guarantees.
- **Load generator / scenario** (k6 or Forge scenarios). Owns *client* behaviour: request mix, merchant population, and retry policy, including deliberately misbehaving clients. The simulator does not fake client behaviour. It *detects* it (hard-decline retry flagging).
- **System under test.** Configured with the simulator's base URL and webhook secret, exactly as it would be configured for the real provider.

### API subset (compatibility target: one pinned `Stripe-Version`)

Requests use `application/x-www-form-urlencoded` bodies with bracket-nested parameters (`metadata[order]=42`, `expand[]=latest_charge`). Responses and errors use the provider's JSON object shapes, including `object`, `id` prefixes, `livemode: false`, and the error object `{error: {type, code, decline_code, message, param}}`.

| Endpoint | Behaviour |
|---|---|
| `POST /v1/payment_intents` | Create, optionally `confirm=true`. Supports `capture_method` automatic or manual. |
| `GET /v1/payment_intents/{id}` | Read. |
| `POST /v1/payment_intents/{id}/confirm` | Draws the outcome (see the outcome model). |
| `POST /v1/payment_intents/{id}/capture` | For manual capture. |
| `POST /v1/payment_intents/{id}/cancel` | |
| `POST /v1/refunds`, `GET /v1/refunds/{id}` | Full and partial refunds. |
| `POST /v1/webhook_endpoints` (and GET, DELETE) | Register endpoints and return the signing secret. |

State machine: `requires_payment_method → requires_confirmation → requires_action → processing → succeeded | canceled`, with `requires_capture` for manual capture.

Events emitted: `payment_intent.created`, `.requires_action`, `.processing`, `.succeeded`, `.payment_failed`, `.canceled`, `charge.refunded`.

Non-goals: Customers beyond what PaymentIntents need, Subscriptions, Connect, Checkout and hosted pages, client-side libraries, Radar, real card data. The simulator holds no card data and is not in PCI scope.

### Outcome model

- **Merchant = API key.** A key `sk_test_<merchant>_<suffix>` selects the merchant. Each merchant is assigned an outcome profile. The default population is a healthy majority plus configurable outlier merchants.
- **Per-attempt draw.** Each confirm draws an outcome from the merchant's profile: success, soft decline (`insufficient_funds`, `card_velocity_exceeded`: a retry may succeed), hard decline (`expired_card`, `do_not_honor`, `lost_card`: a retry is a client defect), or 3-D Secure required. A 3-D Secure attempt then resolves as completed, failed, or **abandoned**: it stays in `requires_action` until the merchant cancels it or it expires.
- **Default profile "field-2026-10":** 94% success, 4% decline, ~2% 3-D Secure that is abandoned. The source is per attempt, over 10 days, from one issuer-side source in **Ukraine** (outside the EU's PSD2 strong-customer-authentication regime; local central-bank rules apply), so other markets need their own 3-D Secure share. The soft/hard split matters little in practice, because cardholders retry even expired cards. What matters is the **success rate of a retry**, which is a configurable parameter per decline class. Every value is configurable, and the profile carries its provenance so nobody mistakes it for a universal truth.
- **Deterministic overrides.** The provider's documented test card numbers and `pm_card_*` tokens force their documented outcome, whatever the profile says. This keeps existing client test suites deterministic.
- **Hard-decline retry detection.** A repeated attempt with the same payment method after a hard decline, inside a configurable window, is counted per merchant. Optionally it raises a simulator event naming the merchant. This turns the retry-inflated concentration in the field data into a checkable property of the client.
- **Reproducibility.** All draws come from a seeded PRNG keyed by (seed, merchant, intent id, attempt number), so a scenario replays the same outcomes whatever the request interleaving.

### Fault and latency model

All faults are configurable per merchant and per endpoint, and can be changed at runtime through the control API.

- **Latency:** a distribution, not a constant: percentile points (p50, p90, p99, max) or log-normal parameters. Delays are scheduled without blocking, never by sleeping a worker thread, so a slow profile cannot saturate the simulator itself.
- **Lost response after commit:** the state change commits, and then the connection is dropped, or the response is delayed past a configured client timeout. This is the double-charge scenario.
- **Failure before commit:** a 5xx `api_error` is returned and no state changes.
- **Rate limiting:** a per-merchant token bucket answering `429` with the provider's error shape. Header details follow the pinned API version [unverified until implementation: exact headers].
- **Webhooks:** each event carries `Stripe-Signature: t=<ts>,v1=<HMAC-SHA256(secret, "<ts>.<payload>")>`. Faults can be injected per event:
  - delivery delay as a distribution;
  - duplicate delivery probability;
  - reordering window;
  - delivery before the API response;
  - endpoint black-hole windows.
  Retries follow the provider's documented schedule (exponential backoff for up to 3 days).
- **Clock control:** a speed factor compresses every simulator timer, including webhook backoff, 3-D Secure expiry and idempotency-key retention. Multi-day behaviour can then run in minutes.

### Idempotency

- `Idempotency-Key` is scoped per merchant. It is retained 24 h in simulator time.
- The same key with the same parameters replays the stored response, including a stored error once the request had started executing.
- The same key with different parameters gives `400 idempotency_error`.
- A concurrent request with a key still in flight gives `409`.
- Validation failures and `429` responses are not stored, matching the provider's documented behaviour.

### Guarantees (per operation, with the mechanism)

Guarantees are stated at two levels. Each **resource** states what it guarantees on each runtime. The simulator's operations state what they **derive** from that: given that the resource provides X, the operation provides Y. The table below is the derived level.

| Operation | Single Terra instance | Own Aether cluster |
|---|---|---|
| Same idempotency key creates at most one PaymentIntent | A per-key atomic insert in local state. **Lost on restart** unless local persistence is enabled. | The key record is committed through the consensus-backed KV store with compare-and-set **before** the response is sent. A retry on any node sees it. |
| GET reflects a completed POST | Same process, same state. | The read path must give read-your-writes across nodes. The implementation PR states the mechanism, and a test pins it. |
| Webhook delivery | At-least-once until the retry horizon. Pending deliveries are lost on restart unless persisted. | At-least-once. Pending deliveries are stored durably and resumed by another node after a node loss, so duplicates are possible, as with the real provider. Never exactly-once. |
| Outcome reproducibility | Same seed, same outcomes. | Same, because draws are keyed by intent and attempt, not by node or arrival order. |

If the simulator itself violated idempotency, it would manufacture double charges and blame the system under test. These guarantees are acceptance criteria, not aspirations.

### Control API (simulator-only, under `/sim/`)

- Seed.
- Clock factor.
- Profiles: assign them, and change their values live.
- Fault injection: enable and disable.
- 3-D Secure resolution for a given intent: complete, fail or abandon.
- Counters: outcomes per merchant, hard-decline retries, webhook attempts and failures.
- Reset.

The control API is authenticated separately from the merchant API keys.

### Performance contract

The simulator must not be the bottleneck. Before it is used in any measurement, a stand-alone benchmark must show it sustains at least 3× the target request rate. Its own overhead above the configured latency must stay below 1 ms at p99. Both numbers are **guesses**, to be settled by the first benchmark. Performance runs place the simulator on separate hosts from the system under test.

### Milestones

1. **M1, failure behaviour on a single Terra instance:** the API subset, the state machine, idempotency, signed webhooks with faults, the outcome model with test cards, the control API and clock control. The first consumer is the ticketing demo's checkout. Its acceptance test: a lost-response retry never double-charges, duplicate and reordered webhooks are deduplicated, and a seat held for an abandoned 3-D Secure payment is released by the system's own timeout.
2. **M2, load realism:** latency distributions, rate limits, per-merchant populations with outlier merchants, the hard-decline retry detection, and the stand-alone benchmark against the performance contract.
3. **M3, own Aether cluster:** consensus-backed idempotency records, durable webhook delivery, and tests pinning the cluster guarantees above.
4. **M4, Forge integration:** simulated external dependencies declared alongside a Forge scenario.

## Alternatives Considered

- **Extend `stripe-mock`.** It is Go, stateless by design, and outside our runtime. Adding state and webhooks would mean forking it and maintaining that fork.
- **A generic stub server with scripted responses.** Every behaviour above would be per-test scripting. The value is in shared, reproducible behaviour.
- **A standalone service, not a slice.** It would run in one way only. As a slice the same code runs in three runtimes. It also exercises the slice HTTP surface on a protocol shape our own applications do not use: form encoding, bracket-nested parameters, HMAC-signed outbound calls.

## Migration

Not applicable (new component).

## Decided (owner, 2026-10-10)

- **Product status and home:** a dedicated private repository, Apache-licensed, made public later. The repository is created when work starts.
- **Runtimes:** the same slice runs unchanged everywhere. Guarantees are per resource and per runtime; the slice's own guarantees are conditional on them.
- **Field data:** the market is Ukraine. The decline split is replaced by a configurable retry success rate.

## Open questions

1. The 3-D Secure intervals (challenge timeout, abandonment, session expiry) are likely fixed by the EMV 3-D Secure specification and by the market's regulation. Research them before the defaults are set, with sources.
2. Is the HTTP surface complete? Bracket-nested form parameters and arbitrary request headers in `@Http` routes: `application/x-www-form-urlencoded` is recognised by the app HTTP server, but nested-parameter parsing is [unverified].

## References

- The provider's public API reference and published OpenAPI specification (MIT-licensed), used as the compatibility target. Request and response shapes are checked against the pinned spec version in CI.
- Related: Terra runtime (phase-2), Forge, the ticketing example application.
