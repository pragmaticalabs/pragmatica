/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pragmatica.postgres.net.netty;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.lang.reflect.Proxy;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.SqlError;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #3635 (L3): the event-loop group a builder creates for itself is released when the Connectibles it built are closed,
/// whatever the connect outcome was; a supplied shared group is never released.
class NettyConnectibleBuilderGroupReleaseTest {
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(10));

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<Socket> accepted = new ArrayList<>();
    private final List<EventLoopGroup> sharedGroups = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (var accept : accepted) {
            accept.close();
        }

        for (var server : servers) {
            server.close();
        }

        sharedGroups.forEach(group -> group.shutdownGracefully(0, 1, TimeUnit.SECONDS));
    }

    @Test
    void close_terminatesOwnedGroup_afterRefusedConnect() throws IOException {
        var builder = builderTo(closedPort());
        var pool = builder.pool();

        assertThat(pool.getConnection().await(GUARD).isFailure()).isTrue();
        var group = builder.ownedGroup();

        assertThat(group).isNotNull();
        assertThat(group.isShuttingDown()).isFalse();
        assertThat(pool.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
        assertThat(builder.ownedGroup()).isNull();
    }

    @Test
    void close_terminatesOwnedGroup_whileConnectNeverAnswers() throws Exception {
        var builder = builderTo(silentServerPort());
        var plain = builder.plain();
        var connecting = plain.getConnection();

        awaitAccepted();
        var group = builder.ownedGroup();

        assertThat(group).isNotNull();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
        assertThat(connecting.await(GUARD).isFailure()).isTrue();
    }

    @Test
    void close_resolves_whenTheGroupWasAlreadyShutDown() throws IOException {
        var builder = builderTo(closedPort());
        var plain = builder.plain();

        assertThat(plain.getConnection().await(GUARD).isFailure()).isTrue();
        var group = builder.ownedGroup();

        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
        assertThat(builder.ownedGroup()).isNull();
    }

    @Test
    void close_leavesSuppliedGroupRunning() throws IOException {
        var shared = sharedGroup();
        var builder = builderTo(closedPort());

        builder.eventLoopGroup(shared);
        var pool = builder.pool();
        var plain = builder.plain();

        assertThat(pool.getConnection().await(GUARD).isFailure()).isTrue();
        assertThat(builder.ownedGroup()).as("streams use the supplied group, not a builder-owned one").isNull();
        assertThat(pool.close().await(GUARD).isSuccess()).isTrue();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertThat(shared.isShuttingDown()).isFalse();
        assertThat(builder.ownedGroup()).isNull();
    }

    @Test
    void group_isReleasedWithTheLastConnectible() throws IOException {
        var builder = builderTo(closedPort());
        var pool = builder.pool();
        var plain = builder.plain();

        assertThat(pool.getConnection().await(GUARD).isFailure()).isTrue();
        var group = builder.ownedGroup();

        assertThat(plain.getConnection().await(GUARD).isFailure()).isTrue();
        assertThat(builder.ownedGroup()).as("both Connectibles share one owned group").isSameAs(group);
        assertThat(pool.close().await(GUARD).isSuccess()).isTrue();
        assertThat(group.isShuttingDown()).isFalse();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
    }

    @Test
    void closedConnectible_cannotStartAnotherGroup() throws IOException {
        var builder = builderTo(closedPort());
        var plain = builder.plain();

        assertThat(plain.getConnection().await(GUARD).isFailure()).isTrue();
        var group = builder.ownedGroup();

        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
        var again = plain.getConnection().await(GUARD);

        assertThat(again.isFailure()).isTrue();
        again.onFailure(cause -> assertThat(cause).isInstanceOf(SqlError.ConnectionPoolClosed.class));
        assertThat(builder.ownedGroup()).isNull();
    }

    @Test
    void builder_startsFreshGroup_afterTheLastRelease() throws IOException {
        var builder = builderTo(closedPort());
        var first = builder.plain();

        assertThat(first.getConnection().await(GUARD).isFailure()).isTrue();
        var firstGroup = builder.ownedGroup();

        assertThat(first.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(firstGroup);
        var second = builder.plain();

        assertThat(second.getConnection().await(GUARD).isFailure()).isTrue();
        var secondGroup = builder.ownedGroup();

        assertThat(secondGroup).isNotNull().isNotSameAs(firstGroup);
        assertThat(secondGroup.isShuttingDown()).isFalse();
        assertThat(second.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(secondGroup);
    }

    @Test
    void repeatedClose_doesNotReleaseTwice() throws IOException {
        var builder = builderTo(closedPort());
        var pool = builder.pool();
        var plain = builder.plain();

        assertThat(plain.getConnection().await(GUARD).isFailure()).isTrue();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
        assertThat(builder.ownedGroup()).as("the pool still holds its lease").isNotNull();
        var group = builder.ownedGroup();

        assertThat(pool.close().await(GUARD).isSuccess()).isTrue();
        assertTerminated(group);
        assertThat(builder.ownedGroup()).isNull();
    }

    @Test
    void close_closesTheDelegate_beforeReleasingTheGroup() {
        var owned = new OwnedEventLoopGroup(() -> 1);
        var lease = new OwnedConnectible(owned);
        var group = owned.group();
        var groupShuttingDownAtDelegateClose = new AtomicBoolean(true);
        var delegate = (Connectible) Proxy.newProxyInstance(Connectible.class.getClassLoader(),
                                                            new Class<?>[]{Connectible.class},
                                                            (_, method, _) -> {
                                                                groupShuttingDownAtDelegateClose.set(group.isShuttingDown());
                                                                return Promise.success(Unit.unit());
                                                            });

        lease.delegate(delegate);

        assertThat(lease.close().await(GUARD).isSuccess()).isTrue();
        assertThat(groupShuttingDownAtDelegateClose).as("group still running while the delegate closes").isFalse();
        assertTerminated(group);
    }

    @Test
    void streamLease_isReleasedWhenTheGroupIsStillHeldByAnotherLease_thenGroupTerminates() {
        var owned = new OwnedEventLoopGroup(() -> 1);
        var lease = new OwnedConnectible(owned);
        var group = lease.group().await(GUARD).unwrap();

        lease.delegate(noopDelegate());

        assertThat(lease.close().await(GUARD).isSuccess()).isTrue();
        assertThat(group.isShuttingDown()).as("the stream's lease keeps the group alive after close").isFalse();
        owned.release();
        assertTerminated(group);
    }

    @Test
    void remoteClose_releasesTheStreamLease() throws Exception {
        var builder = builderTo(silentServerPort());
        var plain = builder.plain();
        var connecting = plain.getConnection();

        awaitAccepted();
        assertThat(builder.leaseCount()).as("the Connectible's lease and the open stream's").isEqualTo(2);
        accepted.getFirst().close();
        awaitLeaseCount(builder, 1);
        assertThat(connecting.await(GUARD).isFailure()).isTrue();
        assertThat(builder.ownedGroup().isShuttingDown()).isFalse();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
    }

    @Test
    void failedConnect_releasesTheStreamLease() throws IOException {
        var builder = builderTo(closedPort());
        var plain = builder.plain();

        assertThat(plain.getConnection().await(GUARD).isFailure()).isTrue();
        awaitLeaseCount(builder, 1);
        assertThat(builder.ownedGroup().isShuttingDown()).isFalse();
        assertThat(plain.close().await(GUARD).isSuccess()).isTrue();
    }

    private static void awaitLeaseCount(NettyConnectibleBuilder builder, int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (builder.leaseCount() != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        assertThat(builder.leaseCount()).isEqualTo(expected);
    }

    private static void assertTerminated(EventLoopGroup group) {
        assertThat(group.terminationFuture().awaitUninterruptibly(10, TimeUnit.SECONDS)).isTrue();
    }

    private static Connectible noopDelegate() {
        return (Connectible) Proxy.newProxyInstance(Connectible.class.getClassLoader(),
                                                    new Class<?>[]{Connectible.class},
                                                    (_, _, _) -> Promise.success(Unit.unit()));
    }

    private NettyConnectibleBuilder builderTo(int port) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname("127.0.0.1").port(port).username("u").password("p").database("d");

        return builder;
    }

    private EventLoopGroup sharedGroup() {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        sharedGroups.add(group);

        return group;
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private final CountDownLatch acceptedLatch = new CountDownLatch(1);

    /// Accepts TCP connections and never answers the startup message: the connect never completes.
    private int silentServerPort() throws IOException {
        var server = new ServerSocket(0);

        servers.add(server);
        Thread.ofVirtual().start(() -> acceptOne(server));

        return server.getLocalPort();
    }

    private void acceptOne(ServerSocket server) {
        try {
            accepted.add(server.accept());
            acceptedLatch.countDown();
        } catch (IOException _) {
        // closed by cleanUp
        }
    }

    private void awaitAccepted() throws InterruptedException {
        assertThat(acceptedLatch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
