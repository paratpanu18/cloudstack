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

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.response.InstanceProxyBackendHealthResponse;
import org.apache.cloudstack.diagnostics.DiagnosticsAnswer;
import org.apache.cloudstack.diagnostics.DiagnosticsCommand;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.VirtualRouter;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class VirtualRouterBackendProbeTest {
    private final DomainRouterDao routers = mock(DomainRouterDao.class);
    private final NetworkOrchestrationService networks = mock(NetworkOrchestrationService.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final NetworkVO network = mock(NetworkVO.class);
    private final DomainRouterVO router = mock(DomainRouterVO.class);
    private final VirtualRouterBackendProbe probe = new VirtualRouterBackendProbe(routers, networks, agents);

    @Before
    public void setup() {
        when(vm.getAccountId()).thenReturn(20L);
        when(network.getId()).thenReturn(100L);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(router.getAccountId()).thenReturn(20L);
        when(router.getState()).thenReturn(VirtualMachine.State.Running);
        when(router.getHostId()).thenReturn(50L);
        when(routers.findByNetwork(100L)).thenReturn(List.of(router));
        when(networks.getSystemVMAccessDetails(router)).thenReturn(Map.of(
                NetworkElementCommand.ROUTER_IP, "169.254.0.2", NetworkElementCommand.ROUTER_NAME, "r-1-VM"));
        output("{\"healthy\":true,\"httpstatus\":200,\"message\":\"HTTP 200 OK\",\"elapsedms\":12}");
    }

    private void output(String json) {
        when(agents.easySend(eq(50L), any(DiagnosticsCommand.class))).thenAnswer(call ->
                new DiagnosticsAnswer(call.getArgument(1), true, json + "&&\n&&0"));
    }

    private InstanceProxyBackendHealthResponse check() {
        return probe.check(vm, network, "http", "10.0.0.10", 8080);
    }

    @Test
    public void dispatchesBoundedPrivateProbeToRouterWithoutManagementHttp() {
        InstanceProxyBackendHealthResponse result = check();
        assertTrue(result.getHealthy());
        assertEquals(Integer.valueOf(200), result.getHttpStatus());
        assertEquals(Long.valueOf(12), result.getElapsedMs());
        assertNotNull(result.getCheckedAt());
        assertTrue(result.getCleanupComplete());
        ArgumentCaptor<DiagnosticsCommand> command = ArgumentCaptor.forClass(DiagnosticsCommand.class);
        verify(agents).easySend(eq(50L), command.capture());
        assertEquals("backend-health http 10.0.0.10 8080", command.getValue().getSrciptArguments());
        assertEquals("169.254.0.2", command.getValue().getAccessDetail(NetworkElementCommand.ROUTER_IP));
        assertEquals(30, command.getValue().getWait());
        assertFalse(command.getValue().executeInSequence());
    }

    @Test
    public void noRouterReportsUnavailableWithoutFallback() {
        when(routers.findByNetwork(100L)).thenReturn(List.of());
        InstanceProxyBackendHealthResponse result = check();
        assertFalse(result.getHealthy());
        assertTrue(result.getMessage().contains("No suitable running virtual router"));
        verifyNoInteractions(agents, networks);
    }

    @Test
    public void stoppedOrUnhostedRouterIsNotUsed() {
        when(router.getState()).thenReturn(VirtualMachine.State.Stopped);
        assertFalse(check().getHealthy());
        when(router.getState()).thenReturn(VirtualMachine.State.Running);
        when(router.getHostId()).thenReturn(null);
        assertFalse(check().getHealthy());
        verifyNoInteractions(agents, networks);
    }

    @Test
    public void redundantBackupIsNeverUsed() {
        when(router.getIsRedundantRouter()).thenReturn(true);
        when(router.getRedundantState()).thenReturn(VirtualRouter.RedundantState.BACKUP);
        assertFalse(check().getHealthy());
        verifyNoInteractions(agents);
        when(router.getRedundantState()).thenReturn(VirtualRouter.RedundantState.PRIMARY);
        assertTrue(check().getHealthy());
    }

    @Test
    public void wrongAccountIsolatedRouterIsNotUsed() {
        when(router.getAccountId()).thenReturn(30L);
        assertFalse(check().getHealthy());
        verifyNoInteractions(agents, networks);
    }

    @Test
    public void sharedRouterMustMatchInstancePod() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(vm.getPodIdToDeployIn()).thenReturn(1L);
        when(router.getPodIdToDeployIn()).thenReturn(2L);
        assertFalse(check().getHealthy());
        verifyNoInteractions(agents);
        when(router.getPodIdToDeployIn()).thenReturn(1L);
        when(router.getAccountId()).thenReturn(30L);
        assertTrue(check().getHealthy());
    }

    @Test
    public void missingControlAddressIsUnavailable() {
        when(networks.getSystemVMAccessDetails(router)).thenReturn(Map.of());
        assertFalse(check().getHealthy());
        verifyNoInteractions(agents);
    }

    @Test
    public void agentFailureIsAdvisoryAndHasNoFallback() {
        when(agents.easySend(eq(50L), any(DiagnosticsCommand.class))).thenReturn(null);
        assertFalse(check().getHealthy());
        when(agents.easySend(eq(50L), any(DiagnosticsCommand.class))).thenReturn(new Answer(null, false, "agent offline"));
        assertFalse(check().getHealthy());
    }

    @Test
    public void missingRouterScriptReportsUpgradeRequirement() {
        when(agents.easySend(eq(50L), any(DiagnosticsCommand.class))).thenAnswer(call ->
                new DiagnosticsAnswer(call.getArgument(1), true, "&&script missing&&2"));
        assertTrue(check().getMessage().contains("update the router scripts"));
    }

    @Test
    public void trustedAndUntrustedHttpsResultsArePreserved() {
        for (boolean verified : new boolean[] {true, false}) {
            output("{\"healthy\":true,\"httpstatus\":200,\"message\":\"HTTP 200 OK\",\"tlsverified\":" + verified + "}");
            InstanceProxyBackendHealthResponse result = probe.check(vm, network, "https", "10.0.0.10", 8443);
            assertTrue(result.getHealthy());
            assertEquals(verified, result.getTlsVerified());
        }
    }

    @Test
    public void non200AndConnectionFailuresAreUnhealthy() {
        output("{\"healthy\":false,\"httpstatus\":302,\"message\":\"Backend returned HTTP 302\"}");
        assertFalse(check().getHealthy());
        assertEquals(Integer.valueOf(302), check().getHttpStatus());
        output("{\"healthy\":false,\"message\":\"Backend timed out\"}");
        assertFalse(check().getHealthy());
    }

    @Test
    public void malformedOversizedOrContradictoryOutputIsRejected() {
        for (String json : new String[] {"invalid", "{}", "x".repeat(4097),
                "{\"healthy\":true,\"httpstatus\":500,\"message\":\"wrong\"}"}) {
            output(json);
            assertFalse(check().getHealthy());
        }
    }

    @Test
    public void unsafeArgumentsCannotReachAgent() {
        assertFalse(probe.check(vm, network, "http;id", "10.0.0.10", 80).getHealthy());
        assertFalse(probe.check(vm, network, "http", "10.0.0.10;id", 80).getHealthy());
        assertFalse(probe.check(vm, network, "http", "10.0.0.10", 65536).getHealthy());
        verify(agents, never()).easySend(eq(50L), any(DiagnosticsCommand.class));
    }
}
