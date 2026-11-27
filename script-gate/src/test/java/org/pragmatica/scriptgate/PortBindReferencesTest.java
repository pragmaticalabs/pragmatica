package org.pragmatica.scriptgate;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;
import org.pragmatica.portbind.AllowList;
import org.pragmatica.portbind.PortBindScan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;


/// #3801 - what the scan reports for each member of the gate's API set, and for the receivers it cannot resolve: the reference is
/// `owner#name` as the compiler wrote it, so a member test that matches the wrong member (a negated name, a dropped `bind`) or an
/// owner test that resolves wrongly changes a pinned set, not only a count.
class PortBindReferencesTest {
    private static final String MODULE = "fixture-module";

    /// The references of `fx.EveryApi`, one method per row: the whole set, member by member. A set `Set.of(..)` with a reference that is
    /// not there is a member the gate stopped refusing; an extra one is a member it refuses by mistake.
    private static final Map<String, Set<String>> EVERY_API = Map.ofEntries(Map.entry("serverSocket",
                                                                                      Set.of("java.net.ServerSocket#<init>")),
                                                                            Map.entry("datagramSocket",
                                                                                      Set.of("java.net.DatagramSocket#<init>")),
                                                                            Map.entry("socket",
                                                                                      Set.of("java.net.Socket#bind")),
                                                                            Map.entry("networkChannel",
                                                                                      Set.of("java.nio.channels.ServerSocketChannel#bind",
                                                                                             "java.nio.channels.DatagramChannel#bind",
                                                                                             "java.nio.channels.AsynchronousServerSocketChannel#bind")),
                                                                            Map.entry("serverSocketFactory",
                                                                                      Set.of("javax.net.ServerSocketFactory#createServerSocket")),
                                                                            Map.entry("httpServer",
                                                                                      Set.of("com.sun.net.httpserver.HttpServer#create")),
                                                                            Map.entry("datagramBind",
                                                                                      Set.of("java.net.DatagramSocket#<init>",
                                                                                             "java.net.DatagramSocket#bind")),
                                                                            Map.entry("httpServerBind",
                                                                                      Set.of("com.sun.net.httpserver.HttpServer#bind")),
                                                                            Map.entry("simpleFileServer",
                                                                                      Set.of("com.sun.net.httpserver.SimpleFileServer#createFileServer")),
                                                                            Map.entry("httpServerProvider",
                                                                                      Set.of("com.sun.net.httpserver.spi.HttpServerProvider#createHttpServer",
                                                                                             "com.sun.net.httpserver.spi.HttpServerProvider#createHttpsServer")),
                                                                            Map.entry("rmiRegistry",
                                                                                      Set.of("java.rmi.registry.LocateRegistry#createRegistry")),
                                                                            Map.entry("rmiExport",
                                                                                      Set.of("java.rmi.server.UnicastRemoteObject#exportObject")),
                                                                            Map.entry("rmiSocketFactory",
                                                                                      Set.of("java.rmi.server.RMISocketFactory#createServerSocket")),
                                                                            Map.entry("nettyBootstrap",
                                                                                      Set.of("io.netty.bootstrap.ServerBootstrap#bind")),
                                                                            Map.entry("nettyChannel",
                                                                                      Set.of("io.netty.channel.Channel#bind")));

    @Test
    void everyMemberOfTheSet_isReportedAsExactlyItsOwnReference(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiled(root));
        var references = findings.sites()
                                 .stream()
                                 .filter(site -> site.className()
                                                     .equals("fx.EveryApi"))
                                 .collect(Collectors.groupingBy(PortBindScan.Site::method,
                                                                Collectors.mapping(PortBindScan.Site::api,
                                                                                   Collectors.toSet())));

        assertThat(references).isEqualTo(EVERY_API);
    }

    @Test
    void aMethodHandleConstantLoadedByLdc_isFlagged_atItsMethod(@TempDir Path root) {
        var findings = scan(PortBindFixtures.withLdcOfAConstructorHandle(root.resolve("classes")));

        assertThat(findings.sites()).extracting(PortBindScan.Site::className,
                                                PortBindScan.Site::method,
                                                PortBindScan.Site::api)
                  .containsExactly(tuple("fx.Ldc", "handle", "java.net.ServerSocket#<init>"));
    }

    @Test
    void aReceiverTheScanCannotLoad_isFlaggedNotPassed_failClosed(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiledWithUnloadableReceiver(root));

        assertThat(findings.sites()).as("fx.Broken extends the deleted fx.Missing: what it extends is unknown, so its bind is a site")
                  .extracting(PortBindScan.Site::className, PortBindScan.Site::method, PortBindScan.Site::api)
                  .contains(tuple("fx.CallsBroken", "callIt", "fx.Broken#bind"));
        assertThat(findings.violations()).isNotEmpty();
    }

    private static PortBindScan.Findings scan(Path classes) {
        return Result.lift(() -> PortBindScan.scan(MODULE,
                                                   classes,
                                                   PortBindReferencesTest.class.getClassLoader(),
                                                   AllowList.parse(""))).unwrap();
    }
}
