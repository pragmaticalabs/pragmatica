package org.pragmatica.portbind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// The bytecode scan behind the port-bind gate (#3801): finds every call site of a socket-bind or port-allocation API ([BindApis])
/// in a module's compiled test classes, whatever the port argument is, and fails the build on each one that is neither in
/// `HeldPort` (a `src/main` class of this module, not a scanned tree) nor on the [AllowList] with its reason.
///
/// It reads class files, not source: a qualified call, a static import, a call through a subtype, a method reference
/// (`ServerSocket::new`) and a call inside a lambda or an initialiser all compile to a reference the scan sees, and the spelling
/// of the port (`0`, `(int) 0`, a variable, a computed value) never reaches it. Each site is reported as `class:line`.
///
/// Run per module by the root pom's `port-bind-gate` profile over `target/test-classes`; the module's test classpath is the
/// class loader that resolves a call's receiver type to its supertypes. `mode` is `enforce` (a site fails the run) or `report`
/// (every site is listed with its verdict and the run passes: how the sites were first enumerated).
///
/// What it cannot see: reflection and `MethodHandles.Lookup` finds by name, a bind in `src/main` (not a test class; that is
/// production code and its own concern), and a port that reaches a bind inside the repository's own server-start entry points.
public final class PortBindScan {
    static final String ENFORCE = "enforce";
    static final String REPORT = "report";
    private static final String ALLOW_LIST_RESOURCE = "allowlist.txt";
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");
    private static final String FIX = "bind the port through org.pragmatica.portbind.HeldPort (it comes back open and holds the port for as long as it is open), or"
                                      + " take a reserved port from the module's ClusterTestPorts, or bind port 0 inside the server under test and read the bound port back."
                                      + " A class whose job is the socket itself is listed with its reason in port-bind-gate's allowlist.txt";

    /// One call site: the class (binary name), the enclosing method (lambdas folded into the method that holds them), the source
    /// line and the API reference it reaches.
    public record Site(String className, String method, int line, String api) {
        String location() {
            return className + ":" + line;
        }
    }

    /// What one scan of one module found.
    record Result(int classFiles, List<Site> sites, List<Site> violations, List<AllowList.Entry> stale) {
        boolean clean() {
            return violations.isEmpty() && stale.isEmpty();
        }
    }

    private PortBindScan() {}

    /// `module classesDirectory [enforce|report]`. Exit 1 when `enforce` finds a violation or a stale entry, or the directory is missing.
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("usage: PortBindScan <module> <test-classes directory> [enforce|report]");
        }

        var module = args[0];
        var directory = Path.of(args[1]);
        var mode = args.length == 3 ? args[2] : ENFORCE;

        if (!mode.equals(ENFORCE) && !mode.equals(REPORT)) {
            throw new IllegalArgumentException("mode must be enforce or report, was " + mode);
        }
        if (!Files.isDirectory(directory)) {
            System.err.println("[port-bind-gate] " + module + ": " + directory + " does not exist: the gate has nothing to read, which is not a pass (#3801)");
            System.exit(1);
        }

        var result = scan(module, directory, PortBindScan.class.getClassLoader(), bundledAllowList());

        System.out.println(summary(module, result));
        if (mode.equals(REPORT)) {
            reportLines(module, result).forEach(System.out::println);
        } else if (!result.clean()) {
            failures(module, result).forEach(System.err::println);
            System.exit(1);
        }
    }

    static AllowList bundledAllowList() {
        try (var in = PortBindScan.class.getResourceAsStream(ALLOW_LIST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(ALLOW_LIST_RESOURCE + " is missing from the port-bind-gate jar");
            }
            return AllowList.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Scans every class file under `classes`. `loader` resolves a call's receiver type; the directory itself is added in front
    /// of it, so a test class that extends a bind type is resolved from the very classes being scanned.
    static Result scan(String module, Path classes, ClassLoader loader, AllowList allowList) throws IOException {
        try (var own = new URLClassLoader(new URL[]{classes.toUri().toURL()}, loader)) {
            var apis = new BindApis(own);
            var sites = new ArrayList<Site>();
            var count = 0;

            for (var file : classFiles(classes)) {
                sites.addAll(scanClass(Files.readAllBytes(file), apis));
                count++;
            }
            sites.sort(Comparator.comparing(Site::className)
                                 .thenComparingInt(Site::line));

            var violations = sites.stream()
                                  .filter(site -> !allowList.excuses(module, site.className(), site.method()))
                                  .toList();

            return new Result(count, List.copyOf(sites), violations, allowList.staleFor(module, sites));
        }
    }

    private static List<Path> classFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                        .sorted()
                        .toList();
        }
    }

    private static List<Site> scanClass(byte[] bytes, BindApis apis) {
        var model = ClassFile.of().parse(bytes);
        var className = model.thisClass().asInternalName().replace('/', '.');
        var sites = new ArrayList<Site>();

        for (var method : model.methods()) {
            scanMethod(className, method, apis, sites);
        }
        return sites;
    }

    private static void scanMethod(String className, MethodModel method, BindApis apis, List<Site> sites) {
        var enclosing = enclosingName(method.methodName().stringValue());

        method.code().ifPresent(code -> {
            var line = 0;

            for (CodeElement element : code) {
                if (element instanceof LineNumber number) {
                    line = number.line();
                } else {
                    for (var api : bindsAt(element, apis)) {
                        sites.add(new Site(className, enclosing, line, api));
                    }
                }
            }
        });
    }

    /// The APIs one instruction reaches: a direct call, a method handle loaded as a constant, or a handle in an invokedynamic's
    /// bootstrap arguments (`ServerSocket::new` compiles to the last).
    private static List<String> bindsAt(CodeElement element, BindApis apis) {
        return switch (element) {
            case InvokeInstruction call -> reference(call.owner().asInternalName(), call.name().stringValue(), call.type().stringValue(), apis);
            case ConstantInstruction.LoadConstantInstruction load -> handle(load.constantEntry(), apis);
            case InvokeDynamicInstruction dynamic -> dynamic.invokedynamic()
                                                            .bootstrap()
                                                            .arguments()
                                                            .stream()
                                                            .flatMap(entry -> handle(entry, apis).stream())
                                                            .toList();
            default -> List.of();
        };
    }

    private static List<String> handle(LoadableConstantEntry entry, BindApis apis) {
        return entry instanceof MethodHandleEntry handle
               ? member(handle.reference(), apis)
               : List.of();
    }

    private static List<String> member(MemberRefEntry ref, BindApis apis) {
        return reference(ref.owner().asInternalName(), ref.name().stringValue(), ref.type().stringValue(), apis);
    }

    private static List<String> reference(String owner, String name, String descriptor, BindApis apis) {
        return owner.startsWith("[")
               ? List.of()
               : apis.match(owner, name, descriptor).stream().toList();
    }

    private static String enclosingName(String methodName) {
        var matcher = LAMBDA.matcher(methodName);

        if (!matcher.matches()) {
            return methodName;
        }

        return switch (matcher.group(1)) {
            case "static" -> "<clinit>";
            case "new" -> "<init>";
            default -> matcher.group(1);
        };
    }

    static String summary(String module, Result result) {
        return "[port-bind-gate] " + module + ": scanned " + result.classFiles() + " test class file(s), "
               + result.sites().size() + " bind call site(s), " + result.violations().size() + " not allowed, " + result.stale().size() + " stale allow-list entr(ies)";
    }

    private static List<String> reportLines(String module, Result result) {
        return result.sites()
                     .stream()
                     .map(site -> "PORT-BIND-SITE " + module + " " + site.location() + " " + site.method() + " " + site.api()
                                  + (result.violations().contains(site) ? " VIOLATION" : " allowed"))
                     .toList();
    }

    static List<String> failures(String module, Result result) {
        var lines = new ArrayList<String>();

        result.violations()
              .forEach(site -> lines.add("[port-bind-gate] " + site.location() + " (" + site.method() + ") calls " + site.api()
                                         + "\n    fix: " + FIX));
        result.stale()
              .forEach(entry -> lines.add("[port-bind-gate] stale allow-list entry in " + module + ": " + entry.target() + " (" + entry.reason() + ")"
                                          + "\n    fix: it excuses no bind call site any more; delete it from port-bind-gate's allowlist.txt"));
        return lines;
    }
}
