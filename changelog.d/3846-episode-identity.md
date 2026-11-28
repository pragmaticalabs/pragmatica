### Fixed (2026-11-27 — #3846: every async completion into the leader-election FSM carries its episode's identity)
- **A completion acts only on the entry that started it.** The proposal promise's `onSuccess`/`onFailure` still entered the serial executor unfenced, so a late `ProposalSettled` of an exited `Electing`/`ReElecting`
  could be handled by the next episode. The episode identity is now the Fsm entry's lifetime (`Fsm.entered()`), resolved when the completion is armed, so a state record re-entered from a saved record is a new episode.
  `LeaderElectionContext.serialized(Runnable|Consumer)` is private: the only ways in are `serializedFor(owner, ...)` and `serializedFor(EpisodeIdentity, ...)` (pre-vote episode, node lifetime for the view re-assertion),
  so an arm site without an episode does not compile.
