### Fixed (2026-11-26 — #3754: scheduling bound to its owner's stop, forwarders)
- **`AlertForwarder` and `ForwardReplyResender` retries end with their owner**: the webhook retry and the management reply
  re-send were one-shot tasks on the shared scheduler that their owner's stop never cancelled, so a retry armed before
  `AlertForwarder.close()` / `ManagementServer.stop()` still ran after it (a webhook POST, a wire re-send, a WARN). Both now
  schedule on an `OwnerLifetime` that the stop ends; a send in flight at the stop settles without a WARN or a re-arm
  (the second check, class N: each node is the only sender of its webhook POSTs and reply frames, and one late is harmless).
  `AlertForwarder.close()` resolves every `forward` waiting on a retry it cancelled, so a caller awaiting `forward` across
  a close never dangles: `forward` fails with a cause naming the stop (a delivery given up after `retry_count` still
  resolves it successfully, #3536). `ForwardReplyResender` has no waiter: a reply send is fire-and-forget.
  Operator recovery action: none.
  [mechanism: `OwnerLifetime.end()` in the stop path, a re-read of the flag in the send's continuation, and `close()` resolving the retry promises it cancelled]
  [verified: `AlertForwarderStopTest`, `ForwardReplyResenderTest`, `ManagementServerStopEndsReplyResenderTest`]
