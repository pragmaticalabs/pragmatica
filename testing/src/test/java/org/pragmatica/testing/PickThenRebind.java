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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// The `PICK_THEN_REBIND` pass of `FlakeScan` (#1826): a test that binds a socket to port 0, reads the port the OS chose, and lets
/// the socket close before anything binds that number. Between the close and the real bind any other process, or another module's
/// test under the module-parallel build, can take the port, and the test fails with `BindException: Address already in use`.
///
/// The pass does not analyse where the port goes: text cannot follow a number through a variable, a collection, a lambda or a return,
/// and every form it missed was another way out. It enforces a LOCATION rule on the construction instead. Every port-0 socket built in a
/// scanned tree is either built inside [#HELPER], the one file that returns such a socket still open (bind-and-hold), or sits on a line
/// marked `// port0-held: <reason>`, the reason saying the socket stays open for as long as its port is used. An unmarked construction
/// elsewhere is a hit, and a marker with no reason does not count. A hit is fixed, never listed.
///
/// What it SEES, in a statement with string and char literals blanked (a pattern in a string or a text block is not a construction):
/// `new ServerSocket(0..)` and `new DatagramSocket(0..)`, also `java.net.`-qualified; either with a `new InetSocketAddress(.., 0)` argument;
/// `new DatagramSocket()` (it binds an ephemeral port at once); and a `.bind(..)` of `new InetSocketAddress(.., 0)`, of `0` or of `null`
/// (a channel, a Netty bootstrap, a socket built unbound). One statement or wrapped over lines; the line of the construction carries the marker.
/// What it does NOT see, and review must: a port-0 bind whose port or address arrives through a variable (`new ServerSocket(port)` with
/// `port == 0`), a server another API binds to port 0 itself (`HttpServer.create`, a Netty `bind` with a computed argument), and whether a
/// marked socket really stays open: the marker is a statement by its author, enforced by review, as is a close the caller makes on a socket
/// the helper returned (`PortHolderMain`'s `legacy` mode does that on purpose, as the red control for the allocator that replaced it).
final class PickThenRebind {
    /// The one file that builds port-0 sockets for the caller and hands them back open.
    static final String HELPER = "aether/node/src/test/java/org/pragmatica/aether/node/HeldPort.java";

    private static final String NET = "(?:java\\s*\\.\\s*net\\s*\\.\\s*)?";
    private static final String ADDRESS_PORT_0 = "new\\s+" + NET + "InetSocketAddress\\s*\\(\\s*(?:[^()]*(?:\\([^()]*\\))?[^()]*,\\s*)?0\\s*\\)";
    private static final Pattern CONSTRUCTION = Pattern.compile("new\\s+" + NET + "(?:ServerSocket|DatagramSocket)\\s*\\(\\s*(?:0\\s*[,)]|" + ADDRESS_PORT_0 + ")"
                                                                + "|new\\s+" + NET + "DatagramSocket\\s*\\(\\s*\\)"
                                                                + "|\\.\\s*bind\\s*\\(\\s*(?:(?:0|null)\\s*\\)|" + ADDRESS_PORT_0 + ")");
    private static final Pattern MARKER = Pattern.compile("//\\s*port0-held:\\s*\\S");
    private static final Pattern LITERAL = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");

    private PickThenRebind() {}

    /// The unmarked port-0 constructions in `statements` (from `FlakeScan`'s statement reader over `lines`), none for [#HELPER] itself.
    static List<FlakeScan.Hit> hits(String file, String[] lines, List<FlakeScan.Statement> statements) {
        var hits = new ArrayList<FlakeScan.Hit>();

        if (!HELPER.equals(file)) {
            statements.forEach(statement -> constructions(file, lines, statement, hits));
        }
        return hits;
    }

    private static void constructions(String file, String[] lines, FlakeScan.Statement statement, List<FlakeScan.Hit> hits) {
        var seen = blank(statement.text());
        var matcher = CONSTRUCTION.matcher(seen);

        while (matcher.find()) {
            var line = statement.line() + (int) seen.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();

            if (!marked(lines[line - 1])) {
                hits.add(FlakeScan.Hit.hit(file, line, FlakeScan.Kind.PICK_THEN_REBIND, String.join(" ", statement.text().split("\\s+"))));
            }
        }
    }

    /// A `// port0-held: <reason>` comment with a non-empty reason, outside any string literal on the line.
    private static boolean marked(String line) {
        return MARKER.matcher(blank(line)).find();
    }

    /// `text` with the content of every string literal, char literal and text block replaced by spaces, line breaks kept, so offsets and lines hold.
    private static String blank(String text) {
        return LITERAL.matcher(text).replaceAll(literal -> Matcher.quoteReplacement(literal.group().replaceAll("[^\\n]", " ")));
    }
}
