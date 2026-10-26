### Fixed
- A certificate rotation that is overtaken by a quorum loss, a quorum return or a stop no longer leaves the listeners it bound open and unreferenced (#2418). The app HTTP state machine now handles the rotation's completion report in every state, and every state handles every event; a listener reported to a state that cannot own it is closed at once.
