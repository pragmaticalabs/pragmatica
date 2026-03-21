/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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

package org.pragmatica.cloud.aws.api;

import org.pragmatica.lang.Option;

import java.util.List;

/// Parameters for EC2 RunInstances API call.
public record RunInstancesRequest(
    String imageId,
    String instanceType,
    int minCount,
    int maxCount,
    Option<String> keyName,
    List<String> securityGroupIds,
    Option<String> subnetId,
    Option<String> userData
) {
    /// Factory method for creating a RunInstances request.
    public static RunInstancesRequest runInstancesRequest(String imageId,
                                                          String instanceType,
                                                          int minCount,
                                                          int maxCount,
                                                          Option<String> keyName,
                                                          List<String> securityGroupIds,
                                                          Option<String> subnetId,
                                                          Option<String> userData) {
        return new RunInstancesRequest(imageId, instanceType, minCount, maxCount,
                                       keyName, securityGroupIds, subnetId, userData);
    }
}
