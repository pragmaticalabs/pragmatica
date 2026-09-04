### Fixed (2026-09-05 — #292: live alerts never render)

- `app.js` no longer unwraps the WS envelope before dispatching an `ALERT`/`ALERT_RESOLVED`
  message to the alerts store. `AlertManager.buildAlertMessage` puts the `type` discriminator
  at the top level only (`{"type":"ALERT","data":{...}}`), never duplicated inside `data`; the
  old `updateFromWs(data.data || data)` call stripped the envelope before the store ever saw
  `type`, so its `data.type === 'ALERT'` check was always false and nothing rendered.
  [verified: `AlertWsEnvelopeContractTest.appJs_dispatchesWholeEnvelopeToAlertsStore_notUnwrappedPayload`]
- `alerts.js`'s `updateFromWs` now reads the discriminator off the still-wrapped envelope
  (`envelope.type`) and unwraps `data` itself, instead of checking `data.type` on a payload
  that never carried it.
  [verified: `AlertWsEnvelopeContractTest.alertsJs_readsDiscriminatorOnTheEnvelope_notOnTheUnwrappedPayload`]
- The wire shape itself — top-level `type`, alert fields under `data`, never duplicated — is
  pinned against `AlertManager.checkThreshold`'s actual return value (the literal broadcast
  payload, per `DashboardMetricsPublisher.checkAndBroadcastAlerts`), not a hand-written stand-in.
  [verified: `AlertWsEnvelopeContractTest.checkThreshold_valueAboveCritical_returnsEnvelopeWithTopLevelTypeAndUnwrappedData`]
- The alerts store is now also refreshed from the same gated, repeating poll timer that drives
  cluster status and events (`app.js` `startPolling()`), not only once at page load — so a
  missed or dropped WS message self-heals within one poll cycle instead of leaving the panel
  stale until the next alert fires.
  [verified: `AlertWsEnvelopeContractTest.appJs_startPolling_refreshesAlertsStoreOnTheRepeatingTimer_notOnlyOnce`]
- `ALERT_RESOLVED`'s envelope shape (`AlertManager.broadcastAlertResolved`) is a private,
  directly-broadcasting method with no return-value seam to exercise from a test; its shape
  matches `ALERT`'s by source inspection only.
  [design intent — unverified]
