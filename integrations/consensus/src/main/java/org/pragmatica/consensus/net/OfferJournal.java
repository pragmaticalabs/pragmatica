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
package org.pragmatica.consensus.net;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The offers a WIPED core has been handed and not yet acted on (#2429, ID-1c): one set of retirements per distinct configured
/// seat. They are kept on disk BESIDE the ledger, with the same three durable steps (F1-F3), so a restart in the middle of counting
/// the majority resumes the count instead of starting it again; the ledger file itself stays missing, because that is what "wiped"
/// means. A file that is missing or does not parse reads as no offers, which only delays the release: it is never a release.
///
/// Serialised form: `aether-identity-offers-v1`, then per offering seat an `O` line and its `T` lines (node id, cause), and a
/// trailing digest line.
public interface OfferJournal {
    Logger log = LoggerFactory.getLogger(OfferJournal.class);
    /// The offers on disk. Empty when there is no file; also empty, but LOUD, when there is one that cannot be used: it is logged and
    /// reported through the health callback, and the node goes on as if it held no offers.
    Map<NodeId, IdentityFacts> load();
    /// Remember `sender`'s offer and complete once the whole set is durable. Never blocks the caller; a failed write fails the promise.
    Promise<Unit> record(NodeId sender, IdentityFacts facts);

    /// Forget everything: the wiped state has ended and the ledger holds the union. Best effort, a leftover file is ignored by a
    /// node whose ledger exists.
    @Contract
    void discard();

    /// `health` is told when the journal stops being usable (the cause) and when it works again (empty): the ledger hands it its own
    /// durability channel, so a journal that cannot be written is reported to the operator like a ledger that cannot be.
    static OfferJournal offerJournal(Path file, IdentityLedger.DurableWriter writer, Consumer<Option<Cause>> health) {
        return new FileJournal(file, writer, health, new AtomicBoolean(), new ConcurrentHashMap<>(), new Object());
    }

    record FileJournal(Path file,
                       IdentityLedger.DurableWriter writer,
                       Consumer<Option<Cause>> health,
                       AtomicBoolean failing,
                       Map<NodeId, IdentityFacts> held,
                       Object lock) implements OfferJournal {
        private static final String HEADER = "aether-identity-offers-v1";

        @Override
        public Map<NodeId, IdentityFacts> load() {
            return Files.exists(file)
                   ? loadPresent()
                   : Map.of();
        }

        private Map<NodeId, IdentityFacts> loadPresent() {
            return FileOps.readString(file)
                          .flatMap(text -> parse(text))
                          .onSuccess(held::putAll)
                          .onFailure(this::unusable)
                          .or(Map.of());
        }

        @Contract
        private void unusable(Cause cause) {
            log.warn("The identity offers journal {} cannot be used and is treated as holding no offers: {}",
                     file,
                     cause.message());
            report(Option.some(cause));
        }

        /// Only transitions are reported: a failure while failing is already known, a success is news only after a failure.
        @Contract
        private void report(Option<Cause> change) {
            if (failing.getAndSet(change.isPresent()) != change.isPresent()) {
                health.accept(change);
            }
        }

        @Override
        public Promise<Unit> record(NodeId sender, IdentityFacts facts) {
            var done = Promise.<Unit> promise();

            held.merge(sender, facts, IdentityFacts::merge);
            Thread.ofPlatform()
                  .daemon()
                  .name("identity-offer-journal")
                  .start(() -> done.resolve(writeHeld().onSuccess(_ -> report(Option.none()))
                                                     .onFailure(cause -> report(Option.some(cause)))));

            return done;
        }

        @Override
        @Contract
        public void discard() {
            held.clear();
            FileOps.deleteIfExists(file);
        }

        /// One write at a time, each of the whole current set, so a later write never loses an earlier offer.
        private Result<Unit> writeHeld() {
            synchronized (lock) {
                var directory = file.toAbsolutePath().getParent();
                var temp = directory.resolve(".identity-offers-" + System.nanoTime() + ".tmp");
                var content = render(Map.copyOf(held)).getBytes(StandardCharsets.UTF_8);

                return FileOps.createDirectoriesDurable(directory)
                              .flatMap(_ -> writer.writeForced(temp, content))
                              .flatMap(_ -> writer.moveAtomic(temp, file))
                              .flatMap(_ -> writer.forceDirectory(directory))
                              .onFailure(_ -> FileOps.deleteIfExists(temp));
            }
        }

        static String render(Map<NodeId, IdentityFacts> offers) {
            var body = new StringBuilder(HEADER).append('\n');

            offers.entrySet()
                  .stream()
                  .sorted(Map.Entry.comparingByKey())
                  .forEach(entry -> appendOffer(body,
                                                entry.getKey(),
                                                entry.getValue()));

            return body + "D\t" + IdentityFacts.digestOf(body.toString()) + "\n";
        }

        private static void appendOffer(StringBuilder body, NodeId sender, IdentityFacts facts) {
            body.append("O\t").append(encode(sender.id())).append('\n');
            facts.terminated()
                 .entrySet()
                 .stream()
                 .sorted(Map.Entry.comparingByKey())
                 .forEach(fact -> body.append("T\t")
                                      .append(encode(fact.getKey().id()))
                                      .append('\t')
                                      .append(encode(fact.getValue()))
                                      .append('\n'));
        }

        public static Result<Map<NodeId, IdentityFacts>> parse(String content) {
            var lines = List.of(content.split("\n", -1));
            var size = lines.size();

            if (size < 3 || !lines.get(size - 1).isEmpty() || !HEADER.equals(lines.get(0))) {
                return IdentityFacts.MALFORMED.result();
            }

            var offerLines = lines.subList(1, size - 2);
            var body = HEADER + "\n" + String.join("",
                                                   offerLines.stream().map(line -> line + "\n").toList());

            return ("D\t" + IdentityFacts.digestOf(body)).equals(lines.get(size - 2))
                   ? parseOffers(offerLines)
                   : IdentityFacts.MALFORMED.result();
        }

        private static Result<Map<NodeId, IdentityFacts>> parseOffers(List<String> offerLines) {
            var offers = new HashMap<NodeId, IdentityFacts>();
            NodeId current = null;

            for (var line : offerLines) {
                var parts = line.split("\t", -1);

                if (parts.length == 2 && "O".equals(parts[0])) {
                    current = new NodeId(decode(parts[1]));
                    offers.putIfAbsent(current, IdentityFacts.identityFacts());
                } else if (parts.length == 3 && "T".equals(parts[0]) && current != null) {
                    offers.merge(current,
                                 IdentityFacts.terminated(new NodeId(decode(parts[1])), decode(parts[2])),
                                 IdentityFacts::merge);
                } else {
                    return IdentityFacts.MALFORMED.result();
                }
            }

            return Result.success(Map.copyOf(offers));
        }

        private static String encode(String text) {
            return URLEncoder.encode(text, StandardCharsets.UTF_8);
        }

        private static String decode(String text) {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        }
    }
}
