package org.pragmatica.net.redis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;

/// The #3558 interleaving, forced instead of waited for: the caller's thread is held between `Bootstrap.connect`
/// returning and its `addListener` until the connect failed and the close path ran on the loop, and then until the
/// loop stopped (bounded by [#HOLD_MILLIS]). That is what a caller descheduled under load does. On a loop that the
/// close path stops at once, netty rejects the late listener notification and drops it, so the connect would
/// never resolve.
///
/// How the hold is placed: the group waits for each registration, and the channel hands the caller a view of its
/// loop whose promises complete on the caller (so `Bootstrap` dispatches the connect from the caller's thread), and
/// whose `execute` holds the caller after dispatching. Only the socket connect call is scripted, as in
/// [ScriptedConnect]; [#engaged] is the positive control that the close path really ran before the listener.
final class LateListener {
    static final long HOLD_MILLIS = 1_000;
    private static final long CLOSE_WAIT_MILLIS = 5_000;

    private final AtomicBoolean engaged = new AtomicBoolean();

    /// A connect that is refused at once, with the caller held past the close path.
    ChannelFactory<Channel> refused() {
        return () -> new RefusedChannel(engaged);
    }

    /// A connect that never answers (only the connect timeout ends it), with the caller held past the close path.
    ChannelFactory<Channel> neverCompletes() {
        return () -> new PendingChannel(engaged);
    }

    /// Whether the caller was held until the close path had run, so its connect listener really came late.
    boolean engaged() {
        return engaged.get();
    }

    /// The group as seen by the connect: each registration is complete before `register` returns.
    static EventLoopGroup registeringBeforeReturn(EventLoopGroup group) {
        return (EventLoopGroup) Proxy.newProxyInstance(LateListener.class.getClassLoader(),
                                                       new Class<?>[]{EventLoopGroup.class},
                                                       (_, method, args) -> registered(invoke(group, method, args),
                                                                                       method));
    }

    private static Object registered(Object result, Method method) {
        if (method.getName().equals("register") && result instanceof ChannelFuture registration) {
            registration.awaitUninterruptibly(CLOSE_WAIT_MILLIS);
        }
        return result;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private abstract static class HoldingChannel extends NioSocketChannel {
        private final AtomicBoolean engaged;

        HoldingChannel(AtomicBoolean engaged) {
            this.engaged = engaged;
        }

        @Override
        public EventLoop eventLoop() {
            var loop = super.eventLoop();

            return isRegistered() && !loop.inEventLoop()
                   ? callersView(loop)
                   : loop;
        }

        private EventLoop callersView(EventLoop loop) {
            return (EventLoop) Proxy.newProxyInstance(LateListener.class.getClassLoader(),
                                                      new Class<?>[]{EventLoop.class},
                                                      (_, method, args) -> onCaller(loop, method, args));
        }

        private Object onCaller(EventLoop loop, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("newPromise")) {
                return new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
            }
            var result = invoke(loop, method, args);

            if (method.getName().equals("execute")) {
                hold(loop);
            }
            return result;
        }

        private void hold(EventLoop loop) {
            engaged.set(closeFuture().awaitUninterruptibly(CLOSE_WAIT_MILLIS));
            loop.terminationFuture().awaitUninterruptibly(HOLD_MILLIS);
        }
    }

    private static final class RefusedChannel extends HoldingChannel {
        RefusedChannel(AtomicBoolean engaged) {
            super(engaged);
        }

        @Override
        protected boolean doConnect(SocketAddress remote, SocketAddress local) throws Exception {
            throw new ConnectException("Connection refused");
        }
    }

    private static final class PendingChannel extends HoldingChannel {
        PendingChannel(AtomicBoolean engaged) {
            super(engaged);
        }

        @Override
        protected boolean doConnect(SocketAddress remote, SocketAddress local) {
            return false;
        }
    }
}
