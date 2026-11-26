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
record AllowList(List<Entry> entries) {
    record Entry(String module, String target, String reason) {
        boolean excuses(String module, String className, String method) {
            return this.module.equals(module)
                   && (target.equals(className) || target.equals(className + "#" + method));
        }
    }

    static AllowList parse(String text) {
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

    boolean excuses(String module, String className, String method) {
        return entries.stream()
                      .anyMatch(entry -> entry.excuses(module, className, method));
    }

    /// The entries of `module` that excuse none of `sites`.
    List<Entry> staleFor(String module, List<PortBindScan.Site> sites) {
        return entries.stream()
                      .filter(entry -> entry.module().equals(module))
                      .filter(entry -> sites.stream()
                                            .noneMatch(site -> entry.excuses(module, site.className(), site.method())))
                      .toList();
    }
}
