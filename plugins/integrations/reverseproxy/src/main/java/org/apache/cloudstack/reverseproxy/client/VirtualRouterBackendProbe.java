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

package org.apache.cloudstack.reverseproxy.client;

import java.util.Date;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.InstanceProxyBackendHealthResponse;
import org.apache.cloudstack.diagnostics.DiagnosticsAnswer;
import org.apache.cloudstack.diagnostics.DiagnosticsCommand;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.VirtualRouter;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Dispatches a fixed, bounded probe to a router attached to the instance network. */
public class VirtualRouterBackendProbe {
    private static final Logger LOGGER = LogManager.getLogger(VirtualRouterBackendProbe.class);
    private final DomainRouterDao routers;
    private final NetworkOrchestrationService networks;
    private final AgentManager agents;

    public VirtualRouterBackendProbe(DomainRouterDao routers, NetworkOrchestrationService networks, AgentManager agents) {
        this.routers = routers;
        this.networks = networks;
        this.agents = agents;
    }

    public InstanceProxyBackendHealthResponse check(UserVmVO vm, NetworkVO network, String protocol, String address, int port) {
        InstanceProxyBackendHealthResponse result = new InstanceProxyBackendHealthResponse();
        result.setHealthy(false);
        result.setCleanupComplete(true);
        result.setElapsedMs(0L);
        try {
            if (!("http".equals(protocol) || "https".equals(protocol)) || !NetUtils.isValidIp4(address) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid backend connection settings");
            }
            DomainRouterVO router = routers.findByNetwork(network.getId()).stream()
                    .filter(candidate -> candidate.getRemoved() == null && candidate.getState() == VirtualMachine.State.Running
                            && candidate.getHostId() != null && !candidate.isStopPending())
                    .filter(candidate -> !candidate.getIsRedundantRouter()
                            || candidate.getRedundantState() == VirtualRouter.RedundantState.PRIMARY)
                    .filter(candidate -> network.getGuestType() != Network.GuestType.Isolated || candidate.getAccountId() == vm.getAccountId())
                    .filter(candidate -> network.getGuestType() != Network.GuestType.Shared || vm.getPodIdToDeployIn() == null
                            || vm.getPodIdToDeployIn().equals(candidate.getPodIdToDeployIn()))
                    .findFirst().orElse(null);
            if (router == null) {
                result.setMessage("No suitable running virtual router is available for this network");
                return result;
            }
            Map<String, String> access = networks.getSystemVMAccessDetails(router);
            if (access == null || access.get(NetworkElementCommand.ROUTER_IP) == null
                    || !NetUtils.isValidIp4(access.get(NetworkElementCommand.ROUTER_IP))) {
                result.setMessage("The virtual router is not accessible; contact your administrator");
                return result;
            }
            // Only validated protocol, numeric IP and port are passed; never a user-supplied URL or shell command.
            DiagnosticsCommand command = new DiagnosticsCommand("backend-health " + protocol + " " + address + " " + port, false);
            command.setAccessDetail(access);
            command.setWait(30);
            Answer answer = agents.easySend(router.getHostId(), command);
            if (!(answer instanceof DiagnosticsAnswer) || !answer.getResult()) {
                result.setMessage("Unable to run the backend check on the virtual router");
                return result;
            }
            Map<String, String> output = ((DiagnosticsAnswer) answer).getExecutionDetails();
            String json = output.get(ApiConstants.STDOUT);
            if (!"0".equals(output.get(ApiConstants.EXITCODE)) || json == null || json.length() > 4096) {
                result.setMessage("Virtual router backend check unavailable; update the router scripts and retry");
                return result;
            }
            JsonNode data = new ObjectMapper().readTree(json);
            JsonNode healthy = data == null ? null : data.get("healthy");
            JsonNode status = data == null ? null : data.get("httpstatus");
            JsonNode message = data == null ? null : data.get("message");
            if (healthy == null || !healthy.isBoolean() || message == null || !message.isTextual() || message.asText().length() > 1024
                    || (status != null && (!status.isIntegralNumber() || status.asInt() < 100 || status.asInt() > 599))
                    || healthy.asBoolean() != (status != null && status.asInt() == 200)) {
                result.setMessage("The virtual router returned an invalid backend check result");
                return result;
            }
            result.setHealthy(healthy.asBoolean());
            result.setMessage(message.asText());
            if (status != null) {
                result.setHttpStatus(status.asInt());
            }
            if ("https".equals(protocol) && data.path("tlsverified").isBoolean()) {
                result.setTlsVerified(data.get("tlsverified").asBoolean());
            }
            if (data.path("elapsedms").isIntegralNumber() && data.get("elapsedms").asLong() >= 0) {
                result.setElapsedMs(data.get("elapsedms").asLong());
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to check backend via virtual router for instance " + vm.getUuid(), e);
            result.setMessage("Virtual router backend check unavailable; verify the router is reachable and its scripts are updated");
        } finally {
            result.setCheckedAt(new Date());
        }
        return result;
    }
}
