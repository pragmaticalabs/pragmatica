// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationBatchTest {
    @Test
    void failedRegionDoesNotStarveOtherRegionsOrItsNextItem() {
        var visited = new CopyOnWriteArrayList<Integer>();
        var failed = new CopyOnWriteArrayList<Integer>();
        ReconciliationBatch.reconcile(List.of(0, 1, 2, 3), 2, TimeSpan.timeSpan(1).seconds(),
            item -> {
                visited.add(item);
                return item == 0 ? Causes.cause("region unavailable").promise() : Promise.unitPromise();
            }, (item, cause) -> failed.add(item)).await().unwrap();
        assertThat(visited).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(failed).containsExactly(0);
    }

    @Test
    void lostCompletionIsBoundedAndCannotStopHealthyLane() {
        var visited = new CopyOnWriteArrayList<Integer>();
        var failed = new CopyOnWriteArrayList<Integer>();
        var stalled = Promise.<Unit>promise();
        ReconciliationBatch.reconcile(List.of(0, 1, 2, 3), 2, TimeSpan.timeSpan(30).millis(),
            item -> {
                visited.add(item);
                return item == 0 ? stalled : Promise.unitPromise();
            }, (item, cause) -> failed.add(item)).await(TimeSpan.timeSpan(2).seconds()).unwrap();
        assertThat(visited).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(failed).containsExactly(0);
    }
}
