### Added (2026-10-10 — #2062: operator clear path for an unconfirmed-termination mark)

- **An operator can clear an unconfirmed-termination mark (#2062).** `aether cluster clear-unconfirmed <node> --instance <provider-instance-id|unknown> [--force]`
  (`POST /api/v1/nodes/unconfirmed/clear/{id}`, OPERATOR, leader-routed) clears ONE mark after the operator verified at the provider and terminated the instance
  by hand. The cluster asks the node's provider first, by the node's labels, so a wrong or `unknown` instance id does not hide a listed instance: the clear is
  refused (409, naming the instance and its state) while an instance of the node is still listed and not terminated, and refused when the provider cannot be
  asked; `--force` overrides both and is recorded in the event. It is also refused, with nothing cleared, for a node that shows life (409), for a node with no mark
  (404), and when the mark changed while the clear was committing (the delete carries the exact mark it read, so a later incarnation's mark survives). A clear
  raises `instance-termination-cleared`, which names the node, the authenticated operator, the instance the operator attests, whether the check was forced, the
  provider state seen, the instance id the cluster had listed for the node (or `never listed`) and the mark's cause, and closes the open warning cluster-wide.
  The `instance-termination-unconfirmed` warning now names this command in every form. A missing `--instance` exits 64, like the other commands' usage errors.

### Fixed

- **A re-check that was in flight when the operator cleared a mark no longer re-creates it.** The re-check's late result is fenced on the node's incarnation and on the
  mark still being open, and its write carries the mark it read.
