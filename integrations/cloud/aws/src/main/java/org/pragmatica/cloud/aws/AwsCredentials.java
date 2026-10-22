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
package org.pragmatica.cloud.aws;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;


/// One set of AWS signing credentials. `sessionToken` is present for temporary credentials (an instance
/// profile, an assumed role, `AWS_SESSION_TOKEN`), which must be sent as `x-amz-security-token`;
/// `expiration` is present when the issuer states one. [#toString] redacts every secret.
public record AwsCredentials(String accessKeyId,
                             String secretAccessKey,
                             Option<String> sessionToken,
                             Option<Instant> expiration) {
    /// Long-lived credentials: no session token, no expiry.
    public static AwsCredentials awsCredentials(String accessKeyId, String secretAccessKey) {
        return new AwsCredentials(accessKeyId, secretAccessKey, Option.none(), Option.none());
    }

    public static AwsCredentials awsCredentials(String accessKeyId,
                                                String secretAccessKey,
                                                Option<String> sessionToken,
                                                Option<Instant> expiration) {
        return new AwsCredentials(accessKeyId, secretAccessKey, sessionToken, expiration);
    }

    /// True while these credentials stay valid for at least `margin` after `now`; always true without an expiry.
    public boolean validFor(Instant now, Duration margin) {
        return expiration.map(expiry -> now.plus(margin)
                                           .isBefore(expiry))
                         .or(true);
    }

    /// The signer's view: these credentials in `region`.
    public AwsConfig toAwsConfig(String region) {
        return AwsConfig.awsConfig(accessKeyId, secretAccessKey, region);
    }

    @Override
    public String toString() {
        return "AwsCredentials[accessKeyId=" + accessKeyId
             + ", secretAccessKey=REDACTED, sessionToken=" + sessionToken.map(_ -> "REDACTED")
                                                                         .or("none")
             + ", expiration=" + expiration
             + "]";
    }
}
