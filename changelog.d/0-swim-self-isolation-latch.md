### Fixed
- SWIM self-isolation detection no longer races a peer's revival: the all-peers-FAULTY check and the latch write are now atomic with the revival's latch clear, so the latch can no longer be left set while a peer is ALIVE (which delayed gossip of a genuine death verdict by up to one probe round).
