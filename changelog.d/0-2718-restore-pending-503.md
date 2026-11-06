### Fixed
- A management write refused while the cluster's backup restore decision has not committed yet (`RestoreGate.RestorePending`) now answers a classified 503 instead of an unclassified 500. The refusal is transient: the gate opens as soon as the decision lands, so a client can retry, and it can now tell this window from a server error (#2718).
