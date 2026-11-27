package org.pragmatica.scriptgate;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;
import org.pragmatica.portbind.AllowList;
import org.pragmatica.portbind.HeldPort;
import org.pragmatica.portbind.PortBindScan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #3801 - the port-bind gate: a bytecode scan that fails a module on a call site of a socket-bind or port-allocation API in its
/// compiled test classes, whatever the port argument is. It replaces a source-text pattern (`FlakeScan`'s `PICK_THEN_REBIND`)
/// that two verification rounds beat with an alias, a method reference and a re-read port.
///
/// Four things are pinned, each from the side that fails differently:
///  - the FAILURE INPUT: every form the issue and the verification rounds list, compiled from source into a scratch directory,
///    is flagged at its own line, and the message the build prints names `class:line` (the red-first fixture);
///  - the SET: every API type the gate refuses has a fixture method, so a type added without one reddens here;
///  - the ALLOW-LIST: a method entry excuses that method alone, a class entry the class, another module nothing, and an entry that
///    excuses nothing is stale; a line without a reason does not parse;
///  - the WIRING, observed from inside this fork: the scan ran over THIS module's test classes in the build that produced them
///    (its stamp names this class's allowed bind), and `HeldPort`, the helper the failure message points at, holds a port while open.
class PortBindGateTest {
    private static final String MODULE = "fixture-module";

    /// The fixture method for each API type of the gate's set. Keep in step with `BindApis.ALL`: the set pin below fails otherwise.
    private static final Map<String, String> FIXTURE_FOR_TYPE = Map.of("java.net.ServerSocket",
                                                                       "serverSocket",
                                                                       "java.net.DatagramSocket",
                                                                       "datagramSocket",
                                                                       "java.net.Socket",
                                                                       "socket",
                                                                       "java.nio.channels.NetworkChannel",
                                                                       "networkChannel",
                                                                       "javax.net.ServerSocketFactory",
                                                                       "serverSocketFactory",
                                                                       "com.sun.net.httpserver.HttpServer",
                                                                       "httpServer",
                                                                       "io.netty.bootstrap.AbstractBootstrap",
                                                                       "nettyBootstrap",
                                                                       "io.netty.channel.ChannelOutboundInvoker",
                                                                       "nettyChannel");

    /// The forms of `fx.Forms` and the method that holds each; `<clinit>` and `<init>` are the initialiser forms (a lambda counts under
    /// the method that holds it).
    private static final List<String> FORM_METHODS = List.of("computed",
                                                             "zeroLiteral",
                                                             "spelledOddly",
                                                             "aliasedAddress",
                                                             "methodReference",
                                                             "insideLambda",
                                                             "noArgBindsAWildcardPort",
                                                             "multicast",
                                                             "throughSubtypeReceiver",
                                                             "<clinit>",
                                                             "<init>");

    @Test
    void everyFormOfTheFailureInput_isFlagged_andTheMessageNamesTheSite(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiled(root), AllowList.parse(""));
        var forms = sitesOf(findings, "fx.Forms");

        assertThat(forms.keySet()).containsAll(FORM_METHODS);
        assertThat(findings.violations()).containsExactlyElementsOf(findings.sites());
        assertThat(PortBindScan.failures(MODULE, findings)).anyMatch(message -> message.contains("fx.Forms:" + PortBindFixtures.lineOf(PortBindFixtures.FORMS,
                                                                                                                                       "new ServerSocket(computedPort + 1)"))
                                                                                && message.contains("(computed)")
                                                                                && message.contains("java.net.ServerSocket#<init>"));
    }

    @Test
    void aSubtypesOwnConstruction_isFlagged_atItsSuperCall_whateverItsArguments(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiled(root), AllowList.parse(""));

        assertThat(sitesOf(findings, "fx.Forms$Superclass")).containsKey("<init>");
        assertThat(sitesOf(findings, "fx.Forms$Narrow")).as("the argument never matters: a no-arg `super()` is a ServerSocket constructor too")
                  .containsKey("<init>");
    }

    @Test
    void everyApiTypeOfTheSet_hasAFixtureThatIsFlagged(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiled(root), AllowList.parse(""));
        var flagged = sitesOf(findings, "fx.EveryApi");

        assertThat(Set.copyOf(PortBindScan.apiTypes())).as("an API type was added to or removed from the set: give it a fixture here")
                  .isEqualTo(FIXTURE_FOR_TYPE.keySet());
        assertThat(flagged.keySet()).containsAll(FIXTURE_FOR_TYPE.values());
        assertThat(flagged.get("networkChannel")).as("ServerSocketChannel, DatagramChannel and AsynchronousServerSocketChannel are three sites")
                  .hasSize(3);
    }

    @Test
    void whatATestMayDo_withoutBinding_isNotFlagged(@TempDir Path root) {
        var findings = scan(PortBindFixtures.compiled(root), AllowList.parse(""));

        assertThat(sitesOf(findings, "fx.Clean")).as("a connect, an address and a HeldPort read are not binds")
                  .isEmpty();
    }

    @Test
    void aMethodEntry_excusesThatMethodOnly(@TempDir Path root) {
        var classes = PortBindFixtures.compiled(root);
        var allow = AllowList.parse(MODULE + " | fx.Forms#zeroLiteral | the fixture binds on purpose");
        var findings = scan(classes, allow);

        assertThat(violationMethods(findings, "fx.Forms")).doesNotContain("zeroLiteral")
                  .contains("computed", "multicast");
        assertThat(findings.stale()).isEmpty();
    }

    @Test
    void aLambdaInsideAnExcusedMethod_isExcusedWithIt(@TempDir Path root) {
        var allow = AllowList.parse(MODULE + " | fx.Forms#insideLambda | the lambda is the method's own");
        var findings = scan(PortBindFixtures.compiled(root), allow);

        assertThat(violationMethods(findings, "fx.Forms")).doesNotContain("insideLambda");
    }

    @Test
    void aClassEntry_excusesEveryMethodOfTheClass(@TempDir Path root) {
        var allow = AllowList.parse(MODULE + " | fx.EveryApi | the fixture is every API on purpose");
        var findings = scan(PortBindFixtures.compiled(root), allow);

        assertThat(violationMethods(findings, "fx.EveryApi")).isEmpty();
        assertThat(violationMethods(findings, "fx.Forms")).isNotEmpty();
    }

    @Test
    void anEntryOfAnotherModule_excusesNothing_andIsNotStaleHere(@TempDir Path root) {
        var allow = AllowList.parse("some-other-module | fx.EveryApi | not this module's");
        var findings = scan(PortBindFixtures.compiled(root), allow);

        assertThat(violationMethods(findings, "fx.EveryApi")).isNotEmpty();
        assertThat(findings.stale()).isEmpty();
    }

    @Test
    void anEntryThatExcusesNothing_isStale_andFailsTheScan(@TempDir Path root) {
        var allow = AllowList.parse(MODULE + " | fx.Gone#vanished | the call this excused was deleted");
        var findings = scan(PortBindFixtures.compiled(root), allow);

        assertThat(findings.stale()).extracting(AllowList.Entry::target).containsExactly("fx.Gone#vanished");
        assertThat(findings.clean()).isFalse();
        assertThat(PortBindScan.failures(MODULE, findings)).anyMatch(message -> message.contains("stale allow-list entry") && message.contains("fx.Gone#vanished"));
    }

    @Test
    void anEntryWithoutAReason_doesNotParse() {
        assertThatThrownBy(() -> AllowList.parse(MODULE + " | fx.Forms#computed |")).isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining("module | target | reason");
        assertThatThrownBy(() -> AllowList.parse(MODULE + " | fx.Forms#computed")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBundledAllowList_parses_andNamesNoHeldPort() {
        var bundled = PortBindScan.bundledAllowList();

        assertThat(bundled.entries()).allSatisfy(entry -> assertThat(entry.reason()).isNotBlank());
        assertThat(bundled.entries()).extracting(AllowList.Entry::target)
                  .noneMatch(target -> target.contains("HeldPort"));
    }

    @Test
    void anEmptyClassDirectory_reportsZeroClassFiles_notAPassWithoutReading(@TempDir Path root) {
        var findings = scan(root, AllowList.parse(""));

        assertThat(findings.classFiles()).isZero();
        assertThat(PortBindScan.summary(MODULE, findings)).contains("scanned 0 test class file(s)");
    }

    /// HeldPort is the one helper the gate sends a test to: the socket is open and bound on return, and the port stays taken for as long as
    /// it is open. The second bind below is this module's single real bind outside HeldPort; it is on the allow-list with that reason, which
    /// also gives the wiring test a real allowed site to find in the stamp.
    @Test
    void heldPort_holdsItsPort_whileOpen_andReleasesItOnClose() {
        var held = Result.lift(() -> HeldPort.udp()).unwrap();

        assertThat(held.isBound()).isTrue();
        assertThat(held.isClosed()).isFalse();
        assertThat(held.getLocalPort()).isPositive();
        assertThat(Result.lift(() -> new DatagramSocket(held.getLocalPort())).isFailure()).as("another socket cannot take a held port")
                  .isTrue();
        held.close();
        assertThat(held.isClosed()).isTrue();
    }

    @Test
    void heldPortTcp_isOpenAndBound() {
        var held = Result.lift(() -> HeldPort.tcp()).unwrap();

        assertThat(held.isBound()).isTrue();
        assertThat(held.isClosed()).isFalse();
        assertThat(held.getLocalPort()).isPositive();
        assertThat(Result.lift(held::close).isSuccess()).isTrue();
    }

    @Test
    void heldPortHttpServer_holdsItsOwnPort_andReadsItBack() {
        var server = Result.lift(() -> HeldPort.httpServer()).unwrap();

        assertThat(server.getAddress().getPort()).isPositive();
        server.stop(0);
    }

    @Test
    void heldPortLoopbackVariants_bindOnTheRequestedAddress() {
        var loopback = InetAddress.getLoopbackAddress();
        var udp = Result.lift(() -> HeldPort.udp(loopback)).unwrap();
        var tcp = Result.lift(() -> HeldPort.tcp(5, loopback)).unwrap();

        assertThat(udp.getLocalAddress()).isEqualTo(loopback);
        assertThat(tcp.getInetAddress()).isEqualTo(loopback);
        udp.close();
        assertThat(Result.lift(tcp::close).isSuccess()).isTrue();
    }

    @Test
    void thisModule_isScanned_byTheBuildThatCompiledIt() {
        var basedir = System.getProperty("basedir");

        assertThat(basedir).as("surefire sets basedir in the fork").isNotNull();
        var stamp = Path.of(basedir, "target", PortBindScan.STAMP);

        assertThat(stamp).as("the gate's execution ran in this module's build (the root pom's port-bind-gate profile)")
                  .exists();
        var lines = Result.lift(() -> Files.readAllLines(stamp)).or(List.of());

        assertThat(lines).isNotEmpty();
        assertThat(lines.getFirst()).contains("script-gate: scanned").doesNotContain("scanned 0 test class file(s)");
        assertThat(lines.getFirst()).as("the build runs the gate in enforce mode: a raw bind fails the module (the root pom's port.bind.gate.mode, passed through the antrun execution)")
                  .endsWith(", mode=enforce");
        assertThat(lines.stream()
                        .filter(line -> line.contains(PortBindGateTest.class.getName()) && line.endsWith(" allowed"))
                        .collect(Collectors.toList())).as("the stamp lists this class's allowed bind: the scan read this module's real test classes")
                  .isNotEmpty();
        assertThat(lines).noneMatch(line -> line.endsWith(" VIOLATION"));
    }

    private static PortBindScan.Findings scan(Path classes, AllowList allowList) {
        return Result.lift(() -> PortBindScan.scan(MODULE, classes, PortBindGateTest.class.getClassLoader(), allowList)).unwrap();
    }

    private static Map<String, List<PortBindScan.Site>> sitesOf(PortBindScan.Findings findings, String className) {
        return findings.sites()
                       .stream()
                       .filter(site -> site.className()
                                           .equals(className))
                       .collect(Collectors.groupingBy(PortBindScan.Site::method));
    }

    private static Set<String> violationMethods(PortBindScan.Findings findings, String className) {
        return findings.violations()
                       .stream()
                       .filter(site -> site.className()
                                           .equals(className))
                       .map(PortBindScan.Site::method)
                       .collect(Collectors.toSet());
    }
}
