/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright (c) 2020, MicroRaft.
 *
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

package io.microraft.impl.task;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.microraft.RaftNode;
import io.microraft.report.RaftNodeReport;
import io.microraft.report.RaftNodeReport.RaftNodeReportReason;

/**
 * Publishes a {@link RaftNodeReport} for the current state of the Raft node
 */
public interface RaftStateSummaryPublishTask extends RaftNodeStatusAwareTask {
    @Override
    default void doRun() {
        try {
            node().publishRaftNodeReport(RaftNodeReportReason.PERIODIC);
        } finally {
            node().executor().schedule(this, node().config().raftNodeReportPublishPeriodSecs(), SECONDS);
        }
    }
}
