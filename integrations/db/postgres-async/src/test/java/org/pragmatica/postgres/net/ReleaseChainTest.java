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
package org.pragmatica.postgres.net;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.postgres.net.ReleaseChain.releaseAll;


/// #3635, ReleaseChain contract: every release runs exactly once, in order, each only after the previous one settled, on
/// every outcome; the chain resolves with the FIRST failure, or with success when no release failed.
class ReleaseChainTest {
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(10));
    private static final Cause FIRST = Causes.cause("first release failed");
    private static final Cause SECOND = Causes.cause("second release failed");

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void twoFailingReleases_resolveWithTheFirstCause() {
        var outcome = releaseAll(failing("a", FIRST), failing("b", SECOND)).await(GUARD);

        assertThat(describe(outcome)).isEqualTo("failure: first release failed");
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void failureThenSuccess_resolvesWithTheFailure() {
        var outcome = releaseAll(failing("a", FIRST), succeeding("b")).await(GUARD);

        assertThat(describe(outcome)).isEqualTo("failure: first release failed");
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void successThenFailure_resolvesWithThatFailure() {
        var outcome = releaseAll(succeeding("a"), failing("b", SECOND)).await(GUARD);

        assertThat(describe(outcome)).isEqualTo("failure: second release failed");
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void everyReleaseSucceeding_resolvesWithSuccess_andRunsEachOnceInOrder() {
        var outcome = releaseAll(succeeding("a"), succeeding("b"), succeeding("c")).await(GUARD);

        assertThat(describe(outcome)).isEqualTo("success");
        assertThat(events).containsExactly("a", "b", "c");
    }

    @Test
    void noReleases_resolveWithSuccess() {
        assertThat(describe(releaseAll().await(GUARD))).isEqualTo("success");
    }

    @Test
    void eachRelease_startsOnlyAfterThePreviousOneSettled() {
        var firstSettles = Promise.<Unit> promise();
        var chain = releaseAll(pending("a", firstSettles), succeeding("b"));

        assertThat(events).as("b must wait for a to settle").containsExactly("a");
        assertThat(chain.isResolved()).isFalse();

        firstSettles.fail(FIRST);

        assertThat(describe(chain.await(GUARD))).isEqualTo("failure: first release failed");
        assertThat(events).containsExactly("a", "b");
    }

    /// Out of contract: a release is a total function returning a Promise. A throwing supplier is still turned into a failure
    /// by Promise.fold (it catches what the fold function throws), so every later release still runs once and the chain
    /// resolves with a failure instead of throwing; the throw's failure replaces an earlier failure of the chain.
    @Test
    void aThrowingRelease_isTurnedIntoAFailure_andLaterReleasesStillRun() {
        var outcome = releaseAll(failing("a", FIRST), throwing("b"), succeeding("c")).await(GUARD);

        assertThat(escaped(outcome)).hasMessageContaining("release b blew up");
        assertThat(events).containsExactly("a", "b", "c");
    }

    @Test
    void aThrowingRelease_afterSuccess_isAFailure() {
        var outcome = releaseAll(succeeding("a"), throwing("b")).await(GUARD);

        assertThat(escaped(outcome)).hasMessageContaining("release b blew up");
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void releasingAfter_runsTheReleaseOnceWhateverTheStepOutcome_andKeepsTheStepFailure() {
        var failed = ReleaseChain.releasingAfter(FIRST.<Unit> promise(), failing("r", SECOND)).await(GUARD);
        var succeeded = ReleaseChain.releasingAfter(Promise.success(unit()), failing("s", SECOND)).await(GUARD);

        assertThat(describe(failed)).isEqualTo("failure: first release failed");
        assertThat(describe(succeeded)).isEqualTo("failure: second release failed");
        assertThat(events).containsExactly("r", "s");
    }

    @Test
    void releasingOnFailure_releasesOnlyAFailedStep_andKeepsTheStepOutcome() {
        var failed = ReleaseChain.releasingOnFailure(FIRST.<Unit> promise(), failing("r", SECOND)).await(GUARD);
        var succeeded = ReleaseChain.releasingOnFailure(Promise.success(unit()), failing("s", SECOND)).await(GUARD);

        assertThat(describe(failed)).isEqualTo("failure: first release failed");
        assertThat(describe(succeeded)).isEqualTo("success");
        assertThat(events).containsExactly("r");
    }

    private Supplier<Promise<Unit>> succeeding(String name) {
        return () -> recorded(name, Promise.success(unit()));
    }

    private Supplier<Promise<Unit>> failing(String name, Cause cause) {
        return () -> recorded(name, cause.promise());
    }

    private Supplier<Promise<Unit>> pending(String name, Promise<Unit> settles) {
        return () -> recorded(name, settles);
    }

    private Supplier<Promise<Unit>> throwing(String name) {
        return () -> {
            events.add(name);
            throw new IllegalStateException("release " + name + " blew up (injected by the test)");
        };
    }

    private Promise<Unit> recorded(String name, Promise<Unit> result) {
        events.add(name);

        return result;
    }

    /// The Throwable Promise.fold contained (CoreError.Exception, #1311); an assertion error when the outcome is anything else.
    private static Throwable escaped(Result<Unit> outcome) {
        return outcome.fold(ReleaseChainTest::contained, _ -> new AssertionError("expected a failure, got success"));
    }

    private static Throwable contained(Cause cause) {
        return cause instanceof CoreError.Exception exception
               ? exception.cause()
               : new AssertionError("not a contained throw: " + cause.message());
    }

    private static String describe(Result<Unit> outcome) {
        return outcome.fold(cause -> "failure: " + cause.message(), _ -> "success");
    }
}
