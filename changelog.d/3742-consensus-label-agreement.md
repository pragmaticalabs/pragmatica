### Fixed (2026-11-25 — #3742, #3525 L1: a node starts consensus only after its pre-sync check, and the check agrees labels with a quorum)
- **A far-future Propose could move a node that had not passed its pre-sync check into `Syncing`** (and so towards a ballot):
  `triggerResync` re-entered the sync round without the check. `doClusterConnected`, the one entry into `Syncing`, now
  refuses while the check has not passed, for every caller.
- **Core members could run different configurations of one cluster.** `NodeConfig.agreeingLabels()` names the Hello labels every
  core member must share; a node refuses to start when a handshaken core peer's value of any of them differs from its own (an
  absent label differs from a present one), and the peer refuses it in the same way. The set is empty by default and consensus
  names no label.
- **No ballot before a handshaken quorum that agrees.** With agreeing labels set, the join guard has no deadline pass-through for
  agreement: it waits until a quorum of the core has handshaken, and raises the operator warning `consensus-quorum-incomplete`
  once per wait episode, naming the core peers not yet handshaken. The wire-generation cold-start pass is unchanged.
