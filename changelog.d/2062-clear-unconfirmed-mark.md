### Added (2026-10-10 — #2062: operator clear path for an unconfirmed-termination mark)

- **An operator can clear an unconfirmed-termination mark (#2062).** `aether cluster clear-unconfirmed <node> --instance <provider-instance-id|unknown>`
  (`POST /api/v1/nodes/unconfirmed/clear/{id}`, OPERATOR, leader-routed) clears ONE mark after the operator verified at the provider and terminated the instance
  by hand. It is refused, with nothing cleared, for a node that shows life (409), for a node with no mark (404), and when the mark changed while the clear was
  committing (the delete carries the exact mark it read, so a later incarnation's mark survives). A clear raises `instance-termination-cleared`, which names the
  node, the authenticated operator, the instance the operator attests, the instance id the cluster had listed for the node (or `never listed`) and the mark's
  cause, and closes the open warning cluster-wide. The `instance-termination-unconfirmed` warning now names this command in every form.
