/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamFrame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// #1578 — the lane-ownership rule of [QuicPeerConnection#registerStream], in isolation.
///
/// Stream ids follow RFC 9000 §2.1: bit 0 clear = opened by the QUIC client (the dialer), set = opened
/// by the server (the acceptor). Both ends of a connection see the same ids, which is what lets each end
/// apply the rule alone and still keep the same stream. The real-connection counterparts are in
/// [QuicLaneOwnershipTest].
class QuicPeerConnectionLaneOwnershipTest {
    private static final StreamType LANE = StreamType.FORWARD;
    private static final long DIALER_OPENED = 16;
    private static final long NEWER_DIALER_OPENED = 20;
    private static final long ACCEPTOR_OPENED = 17;
    private static final long NEWER_ACCEPTOR_OPENED = 21;

    @Test
    void outranks_dialerOpenedBeatsAcceptorOpened_inEitherOrder() {
        var dialer = stream(DIALER_OPENED, false);
        var acceptor = stream(NEWER_ACCEPTOR_OPENED, true);

        assertThat(QuicPeerConnection.outranks(dialer, acceptor)).isTrue();
        assertThat(QuicPeerConnection.outranks(acceptor, dialer)).isFalse();
    }

    @Test
    void outranks_newerBeatsOlder_whenTheSameSideOpenedBoth() {
        assertThat(QuicPeerConnection.outranks(stream(NEWER_DIALER_OPENED, true), stream(DIALER_OPENED, true))).isTrue();
        assertThat(QuicPeerConnection.outranks(stream(DIALER_OPENED, true), stream(NEWER_DIALER_OPENED, true))).isFalse();
        assertThat(QuicPeerConnection.outranks(stream(NEWER_ACCEPTOR_OPENED, true), stream(ACCEPTOR_OPENED, true))).isTrue();
    }

    /// The defect ordering at the acceptor: its own stand-in registers AFTER the dialer's stream for the
    /// lane. The dialer's stream keeps the lane, the call reports it (so waiting messages go there), and
    /// the stand-in — opened locally — is finished rather than left holding a stream credit.
    @Test
    void registerStream_localStandInAfterTheDialerStream_keepsTheDialerStream_andFinishesTheStandIn() {
        var connection = connection();
        var dialerStream = stream(DIALER_OPENED, false);
        var standIn = stream(ACCEPTOR_OPENED, true);

        connection.registerStream(LANE, dialerStream);
        var kept = connection.registerStream(LANE, standIn);

        assertThat(kept).isSameAs(dialerStream);
        assertThat(connection.stream(LANE)).isEqualTo(Option.some(dialerStream));
        verify(standIn, times(1)).writeAndFlush(argThat(QuicPeerConnectionLaneOwnershipTest::isFin));
        verify(standIn, never()).close();
        verify(dialerStream, never()).writeAndFlush(any());
    }

    /// The same pair at the DIALER, where the stand-in is peer-opened: it loses, but the dialer neither
    /// finishes nor closes it — the acceptor may still be writing into it until the dialer's stream
    /// reaches it, and it finishes the stand-in itself then.
    @Test
    void registerStream_peerOpenedLoser_isNeitherFinishedNorClosed() {
        var connection = connection();
        var dialerStream = stream(DIALER_OPENED, true);
        var standIn = stream(ACCEPTOR_OPENED, false);

        connection.registerStream(LANE, dialerStream);
        var kept = connection.registerStream(LANE, standIn);

        assertThat(kept).isSameAs(dialerStream);
        verify(standIn, never()).writeAndFlush(any());
        verify(standIn, never()).close();
    }

    /// A stand-in holding the lane is displaced when the dialer's stream arrives, and the side that
    /// opened it finishes it behind whatever it already wrote.
    @Test
    void registerStream_dialerStreamDisplacesALocalStandIn_finishesTheStandIn_neverClosesIt() {
        var connection = connection();
        var standIn = stream(ACCEPTOR_OPENED, true);
        var dialerStream = stream(DIALER_OPENED, false);

        connection.registerStream(LANE, standIn);
        var kept = connection.registerStream(LANE, dialerStream);

        assertThat(kept).isSameAs(dialerStream);
        assertThat(connection.stream(LANE)).isEqualTo(Option.some(dialerStream));
        verify(standIn, times(1)).writeAndFlush(argThat(QuicPeerConnectionLaneOwnershipTest::isFin));
        verify(standIn, never()).close();
    }

    /// A dead incumbent never keeps a lane, whatever its rank: an acceptor stand-in replaces a
    /// dialer-opened stream that has already ended (the stand-in's reason to exist).
    @Test
    void registerStream_deadIncumbent_isReplacedEvenByALowerRankedStream() {
        var connection = connection();
        var ended = stream(DIALER_OPENED, false);
        var standIn = stream(ACCEPTOR_OPENED, true);

        connection.registerStream(LANE, ended);
        lenient().when(ended.isActive()).thenReturn(false);
        var kept = connection.registerStream(LANE, standIn);

        assertThat(kept).isSameAs(standIn);
        verify(standIn, never()).writeAndFlush(any());
    }

    private static boolean isFin(Object message) {
        return message instanceof QuicStreamFrame frame && frame.hasFin() && frame.content().readableBytes() == 0;
    }

    private static QuicPeerConnection connection() {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(new NodeId("ownership-peer"), channel);
    }

    private static QuicStreamChannel stream(long id, boolean openedLocally) {
        var stream = mock(QuicStreamChannel.class);

        lenient().when(stream.streamId()).thenReturn(id);
        lenient().when(stream.isLocalCreated()).thenReturn(openedLocally);
        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);

        return stream;
    }
}
