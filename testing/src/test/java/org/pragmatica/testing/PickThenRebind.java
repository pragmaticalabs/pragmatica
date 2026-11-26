/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/// The `PICK_THEN_REBIND` pass of `FlakeScan` (#1826): a test that binds a socket to port 0, reads the port the OS chose, and lets
/// the socket close before anything binds that number. Between the close and the real bind any other process, or another module's
/// test under the module-parallel build, can take the port, and the test fails with `BindException: Address already in use`.
/// The port has to come from an allocator whose reservation holds until the real bind (the module's `ClusterTestPorts`), or the test
/// binds port 0 itself and reads the bound port back.
///
/// What the pass SEES: a try-with-resources that opens `new ServerSocket(0..)` or `new DatagramSocket(0..)` and, inside its body,
/// either returns the socket's `getLocalPort()` (the pick-then-close helper) or assigns it to a target that was declared before (`port =
/// probe.getLocalPort();`, the port outliving the block). Both shapes let the number leave the socket's lifetime by construction.
/// What it does NOT see, and review must: a socket held in a field or a local and closed by an explicit `close()`; a port that leaves
/// the block through a collection, a lambda or standard output (`PortHolderMain`'s `legacy` mode does that on purpose, as the red
/// control for the allocator that replaced it); a port-0 bind through another API (`InetSocketAddress`, a channel). A held socket
/// whose port is used inside the block (`held.getLocalPort()` as an argument, `var port = held.getLocalPort()` read before the close) is
/// the safe form and is not flagged.
final class PickThenRebind {
    private static final Pattern TRY = Pattern.compile("^try\\s*\\(.*", Pattern.DOTALL);

    private static final Pattern PORT_ZERO_SOCKET = Pattern.compile("\\b(\\w+)\\s*=\\s*new\\s+(?:java\\s*\\.\\s*net\\s*\\.\\s*)?(?:ServerSocket|DatagramSocket)\\s*\\(\\s*0\\s*[,)]");

    private PickThenRebind() {}

    /// The escaping reads of every port-0 socket a try-with-resources opens in `statements` (from `FlakeScan`'s statement reader).
    static List<FlakeScan.Hit> hits(String file, List<FlakeScan.Statement> statements) {
        var hits = new ArrayList<FlakeScan.Hit>();

        for (var i = 0; i < statements.size(); i++) {
            if (TRY.matcher(statements.get(i).text()).matches()) {
                var opened = headerEnd(statements, i);
                var sockets = socketVariables(statements, i, opened);

                sockets.forEach(socket -> escapes(statements, opened, socket, file, hits));
            }
        }

        return hits;
    }

    /// The index of the statement that ends the `try (...)` header with `{`: resources separated by `;` arrive as several statements.
    private static int headerEnd(List<FlakeScan.Statement> statements, int from) {
        var end = from;

        while (end < statements.size() - 1 && !statements.get(end).text().endsWith("{")) {
            end++;
        }

        return end;
    }

    private static List<String> socketVariables(List<FlakeScan.Statement> statements, int from, int to) {
        var header = new StringBuilder();

        for (var i = from; i <= to; i++) {
            header.append(statements.get(i).text()).append('\n');
        }

        return PORT_ZERO_SOCKET.matcher(header)
                               .results()
                               .map(match -> match.group(1))
                               .toList();
    }

    /// Walks the try body (statements until the brace depth opened by the header closes) and reports every escaping read of `socket`.
    private static void escapes(List<FlakeScan.Statement> statements,
                                int headerEnd,
                                String socket,
                                String file,
                                List<FlakeScan.Hit> hits) {
        var access = Pattern.quote(socket) + "\\s*\\.\\s*getLocalPort\\s*\\(\\s*\\)\\s*;$";
        var returned = Pattern.compile("^return\\s+" + access, Pattern.DOTALL);
        var assigned = Pattern.compile("^[\\w.\\[\\]]+\\s*=\\s*" + access, Pattern.DOTALL);
        var depth = 1;

        for (var i = headerEnd + 1; i < statements.size() && depth > 0; i++) {
            var statement = statements.get(i);

            if (returned.matcher(statement.text()).matches() || assigned.matcher(statement.text()).matches()) {
                hits.add(FlakeScan.Hit.hit(file,
                                           statement.line(),
                                           FlakeScan.Kind.PICK_THEN_REBIND,
                                           String.join(" ",
                                                       statement.text().split("\\s+"))));
            }

            depth += braceDelta(statement.text());
        }
    }

    private static int braceDelta(String text) {
        if (text.endsWith("{")) {
            return 1;
        }

        return text.endsWith("}")
               ? -1
               : 0;
    }
}
