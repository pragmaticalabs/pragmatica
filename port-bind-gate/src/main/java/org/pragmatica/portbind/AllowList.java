package org.pragmatica.portbind;

import java.util.ArrayList;
import java.util.List;


/// The classes allowed to call a bind API, each with the reason (#3801). One entry per line, `module | target | reason`; blank
/// lines and `#` comments are ignored. `module` is the Maven artifactId whose test classes hold the call, `target` is a binary
/// class name, optionally `#method` (a lambda counts under its enclosing method): `Class#method` excuses that method only,
/// `Class` excuses the class. The reason is required: an entry without one is a parse failure, not an empty justification.
///
/// An entry that excuses no call site in its module is STALE and fails the scan of that module: the list may not outlive the
/// call it excused. `HeldPort` has no entry: it ships in this module's `src/main`, which is not a scanned tree.
///
/// JBCT rules suppressed on this class: this module may depend on nothing in this repository (every module with `src/test` gets it
/// at test scope, so a dependency on `core` is a reactor cycle), which leaves `Result` and `Option` unavailable, and the class
/// is a command-line tool whose boundary is the JDK's (`throws`, `Optional`, a `void main`).
@SuppressWarnings({"JBCT-EX-01", "JBCT-RET-01", "JBCT-BND-01"})
public record AllowList(List<Entry> entries) {
    public record Entry(String module, String target, String reason) {
        boolean excuses(String module, String className, String method) {
            return this.module.equals(module) && (target.equals(className) || target.equals(className + "#" + method));
        }
    }

    public static AllowList parse(String text) {
        var entries = new ArrayList<Entry>();

        text.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .forEach(line -> entries.add(entry(line)));

        return new AllowList(List.copyOf(entries));
    }

    private static Entry entry(String line) {
        var parts = line.split("\\|", 3);

        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("allow-list line needs `module | target | reason`, each non-blank: " + line);
        }

        return new Entry(parts[0].strip(), parts[1].strip(), parts[2].strip());
    }

    public boolean excuses(String module, String className, String method) {
        return entries.stream()
                      .anyMatch(entry -> entry.excuses(module, className, method));
    }

    /// The entries of `module` that excuse none of `sites`.
    public List<Entry> staleFor(String module, List<PortBindScan.Site> sites) {
        return entries.stream()
                      .filter(entry -> entry.module()
                                            .equals(module))
                      .filter(entry -> sites.stream()
                                            .noneMatch(site -> entry.excuses(module,
                                                                             site.className(),
                                                                             site.method())))
                      .toList();
    }
}
