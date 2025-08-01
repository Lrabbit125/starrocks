// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.credential.gcp;

import com.google.common.base.Preconditions;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationProvider;

import java.util.Map;

import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_ENDPOINT;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_IMPERSONATION_SERVICE_ACCOUNT;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_EMAIL;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY_ID;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.GCP_GCS_USE_COMPUTE_ENGINE_SERVICE_ACCOUNT;

public class GCPCloudConfigurationProvider implements CloudConfigurationProvider {
    public static final String GCS_ACCESS_TOKEN = "gcs.oauth2.token";
    public static final String GCS_ACCESS_TOKEN_EXPIRES_AT = "gcs.oauth2.token-expires-at";
    public static final String ACCESS_TOKEN_KEY = "fs.gs.temporary.access.token";
    public static final String TOKEN_EXPIRATION_KEY = "fs.gs.temporary.token.expiration";
    public static final String ACCESS_TOKEN_PROVIDER_IMPL =
            "com.starrocks.connector.gcp.TemporaryGCPAccessTokenProvider";

    @Override
    public CloudConfiguration build(Map<String, String> properties) {
        Preconditions.checkNotNull(properties);

        GCPCloudCredential gcpCloudCredential = new GCPCloudCredential(
                properties.getOrDefault(GCP_GCS_ENDPOINT, ""),
                Boolean.parseBoolean(properties.getOrDefault(GCP_GCS_USE_COMPUTE_ENGINE_SERVICE_ACCOUNT, "false")),
                properties.getOrDefault(GCP_GCS_SERVICE_ACCOUNT_EMAIL, ""),
                properties.getOrDefault(GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY_ID, ""),
                properties.getOrDefault(GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY, ""),
                properties.getOrDefault(GCP_GCS_IMPERSONATION_SERVICE_ACCOUNT, ""),
                properties.getOrDefault(GCS_ACCESS_TOKEN, ""),
                properties.getOrDefault(GCS_ACCESS_TOKEN_EXPIRES_AT, "")
        );
        if (!gcpCloudCredential.validate()) {
            return null;
        }
        return new GCPCloudConfiguration(gcpCloudCredential);
    }
}
