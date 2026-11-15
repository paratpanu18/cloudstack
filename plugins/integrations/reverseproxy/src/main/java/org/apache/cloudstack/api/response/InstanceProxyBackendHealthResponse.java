// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.api.response;

import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class InstanceProxyBackendHealthResponse extends BaseResponse {

    @SerializedName("healthy")
    @Param(description = "whether the backend returned HTTP 200")
    private Boolean healthy;

    @SerializedName("httpstatus")
    @Param(description = "the HTTP status returned by the backend")
    private Integer httpStatus;

    @SerializedName("message")
    @Param(description = "the check result or connection failure")
    private String message;

    @SerializedName("checkedat")
    @Param(description = "when the backend was checked")
    private java.util.Date checkedAt;

    @SerializedName("elapsedms")
    @Param(description = "elapsed probe time in milliseconds")
    private Long elapsedMs;

    @SerializedName("tlsverified")
    @Param(description = "whether HTTPS certificate verification succeeded; omitted for HTTP")
    private Boolean tlsVerified;

    @SerializedName("cleanupcomplete")
    @Param(description = "whether temporary forwarding cleanup completed")
    private Boolean cleanupComplete;

    @SerializedName("temporaryruleid")
    @Param(description = "temporary forwarding rule UUID when cleanup failed")
    private String temporaryRuleId;

    public Boolean getHealthy() {
        return healthy;
    }

    public void setHealthy(Boolean value) {
        this.healthy = value;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer value) {
        this.httpStatus = value;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String value) {
        this.message = value;
    }

    public java.util.Date getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(java.util.Date value) {
        this.checkedAt = value;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long value) {
        this.elapsedMs = value;
    }

    public Boolean getTlsVerified() {
        return tlsVerified;
    }

    public void setTlsVerified(Boolean value) {
        this.tlsVerified = value;
    }

    public Boolean getCleanupComplete() {
        return cleanupComplete;
    }

    public void setCleanupComplete(Boolean value) {
        this.cleanupComplete = value;
    }

    public String getTemporaryRuleId() {
        return temporaryRuleId;
    }

    public void setTemporaryRuleId(String value) {
        this.temporaryRuleId = value;
    }
}
