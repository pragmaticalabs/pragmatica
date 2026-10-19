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
package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;


/// An observer (a node outside the voter roster) is observing again after a stretch outside `Observing` (#2306): the
/// recovery edge of the `PASSIVE` it published. Separate from `ClusterStateNotification` on purpose: that signal means
/// "consensus is operational" and every consumer acts on `ACTIVE`; an observer is not that and must not look like it.
public record ObserverResumedNotification(NodeId self) implements Message.Local {
    public static ObserverResumedNotification observerResumed(NodeId self) {
        return new ObserverResumedNotification(self);
    }
}
