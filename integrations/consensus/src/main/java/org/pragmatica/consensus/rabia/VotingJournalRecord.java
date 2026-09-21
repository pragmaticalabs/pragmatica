package org.pragmatica.consensus.rabia;

import org.pragmatica.serialization.Codec;


/// Monotonic record identity is covered by the frame checksum.
@Codec
public record VotingJournalRecord<M extends RabiaProtocolMessage>(long sequence, M message) {}
