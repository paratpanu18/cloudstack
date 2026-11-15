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

package org.apache.cloudstack.reverseproxy;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.response.InstanceProxyBackendHealthResponse;
import org.apache.cloudstack.reverseproxy.client.NpmClient;
import org.apache.cloudstack.reverseproxy.client.NpmProxyHost;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainMapDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyHostDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

public class ReverseProxyExposureTest {
    private final NpmClient client = mock(NpmClient.class);
    private final ReverseProxyHostDao hosts = mock(ReverseProxyHostDao.class);
    private final ReverseProxyDomainDao domains = mock(ReverseProxyDomainDao.class);
    private final ReverseProxyDomainMapDao grants = mock(ReverseProxyDomainMapDao.class);
    private final NetworkDao networks = mock(NetworkDao.class);
    private final NetworkModel networkModel = mock(NetworkModel.class);
    private final IPAddressDao ips = mock(IPAddressDao.class);
    private final RulesService rules = mock(RulesService.class);
    private final FirewallService firewall = mock(FirewallService.class);
    private final PortForwardingRulesDao forwarding = mock(PortForwardingRulesDao.class);
    private final UserVmDao vms = mock(UserVmDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final NetworkVO network = mock(NetworkVO.class);
    private final IPAddressVO ip = mock(IPAddressVO.class);
    private final ReverseProxyDomainVO domain = mock(ReverseProxyDomainVO.class);
    private final Account caller = mock(Account.class);
    private final AtomicReference<ReverseProxyHostVO> stored = new AtomicReference<>();
    private TestManager manager;
    private PortForwardingRuleVO temporaryRule;
    private InstanceProxyBackendHealthResponse probeResult;
    private List<PortForwardingRuleVO> staleRules = List.of();
    private String checkedIp;
    private int checkedPort;
    private int probeCalls;

    private class TestManager extends ReverseProxyManagerImpl {
        boolean integrationEnabled = true;
        boolean customNames = true;
        boolean defaultNetworkUnavailable;
        Queue<String> generated = new ArrayDeque<>();

        @Override
        protected void validateConfiguration() { }

        @Override
        public boolean isEnabled() { return integrationEnabled; }

        @Override
        protected NetworkVO resolveTargetNetwork(UserVmVO instance, Long networkId) {
            if (networkId == null && defaultNetworkUnavailable) {
                throw new CloudRuntimeException("stale configured shared network");
            }
            return super.resolveTargetNetwork(instance, networkId);
        }

        @Override
        protected boolean allowCustomName() { return customNames; }

        @Override
        protected NpmClient getClient() { return client; }

        @Override
        protected long resolveCertificateId(NpmClient npm, ReverseProxyDomainVO suffix) { return 1; }

        @Override
        protected <T> T withDomainLock(Long domainId, Supplier<T> operation) { return operation.get(); }

        @Override
        protected <T> T withPublicIpLock(Long publicIpId, Supplier<T> operation) { return operation.get(); }

        @Override
        protected InstanceProxyBackendHealthResponse probeBackend(UserVmVO instance, NetworkVO targetNetwork, String protocol, String ip, int port) {
            probeCalls++;
            checkedIp = ip;
            checkedPort = port;
            return probeResult;
        }

        @Override
        protected List<PortForwardingRuleVO> listStaleBackendRules() { return staleRules; }

        @Override
        protected String generateProxyName() { return generated.isEmpty() ? super.generateProxyName() : generated.remove(); }
    }

    private void inject(String name, Object value) throws Exception {
        Field field = ReverseProxyManagerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    @Before
    public void setup() throws Exception {
        manager = new TestManager();
        probeResult = new InstanceProxyBackendHealthResponse();
        probeResult.setHealthy(true);
        probeResult.setHttpStatus(200);
        probeResult.setMessage("HTTP 200 OK");
        probeResult.setCleanupComplete(true);
        probeResult.setCheckedAt(new Date());
        inject("reverseProxyHostDao", hosts);
        inject("reverseProxyDomainDao", domains);
        inject("reverseProxyDomainMapDao", grants);
        inject("networkDao", networks);
        inject("networkModel", networkModel);
        inject("ipAddressDao", ips);
        inject("rulesService", rules);
        inject("firewallService", firewall);
        inject("portForwardingRulesDao", forwarding);
        inject("userVmDao", vms);
        inject("accountManager", accounts);
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        when(caller.getAccountId()).thenReturn(20L);
        CallContext.register(mock(User.class), caller);
        when(vm.getId()).thenReturn(10L);
        when(vm.getAccountId()).thenReturn(20L);
        when(vm.getDomainId()).thenReturn(1L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vms.findById(10L)).thenReturn(vm);
        when(network.getId()).thenReturn(100L);
        when(network.getUuid()).thenReturn("network-uuid");
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getVpcId()).thenReturn(null);
        when(networks.findById(100L)).thenReturn(network);
        Nic nic = mock(Nic.class);
        when(nic.getIPv4Address()).thenReturn("10.0.0.10");
        when(nic.getNetworkId()).thenReturn(100L);
        when(networkModel.getNicInNetwork(10L, 100L)).thenReturn(nic);
        when(networkModel.getDefaultNic(10L)).thenReturn(nic);
        when(networkModel.getNics(10L)).thenAnswer(invocation -> List.of(nic));
        when(networkModel.areServicesSupportedInNetwork(100L, Network.Service.PortForwarding)).thenReturn(true);
        when(domain.getId()).thenReturn(30L);
        when(domain.getDomain()).thenReturn("apps.example.com");
        when(domain.isPublic()).thenReturn(true);
        when(domains.listAll()).thenReturn(List.of(domain));
        when(domains.findById(30L)).thenReturn(domain);
        when(ip.readyToUse()).thenReturn(true);
        when(ip.getAccountId()).thenReturn(20L);
        when(ip.getDataCenterId()).thenReturn(1L);
        when(ip.getAssociatedWithNetworkId()).thenReturn(100L);
        when(ip.getAddress()).thenReturn(new Ip("203.0.113.10"));
        when(ips.findById(200L)).thenReturn(ip);
        PortForwardingRuleVO rule = mock(PortForwardingRuleVO.class);
        when(rule.getId()).thenReturn(500L);
        when(forwarding.findById(500L)).thenReturn(rule);
        when(rules.createPortForwardingRule(any(), eq(10L), isNull(), anyBoolean(), eq(true))).thenReturn(rule);
        when(rules.applyPortForwardingRules(200L, caller)).thenReturn(true);
        when(rules.revokePortForwardingRule(500L, true)).thenReturn(true);
        when(firewall.applyIngressFirewallRules(200L, caller)).thenReturn(true);
        when(firewall.revokeRelatedFirewallRule(500L, true)).thenReturn(true);
        NpmProxyHost created = new NpmProxyHost();
        created.setId(99L);
        when(client.createProxyHost(any())).thenReturn(created);
        when(client.deleteProxyHost(anyLong())).thenReturn(true);
        when(hosts.persist(any())).thenAnswer(invocation -> {
            ReverseProxyHostVO proxy = invocation.getArgument(0);
            Field id = ReverseProxyHostVO.class.getDeclaredField("id");
            id.setAccessible(true);
            id.setLong(proxy, 1L);
            stored.set(proxy);
            return proxy;
        });
        when(hosts.update(anyLong(), any())).thenReturn(true);
        when(hosts.findById(1L)).thenAnswer(invocation -> stored.get());
        when(hosts.remove(1L)).thenAnswer(invocation -> { stored.set(null); return true; });
    }

    @After
    public void teardown() { CallContext.unregister(); }

    private ReverseProxyHost expose(String name) {
        return manager.createInstanceProxy(10L, name, "http", 8080, 30L, 100L, 200L, 8443);
    }

    private PortForwardingRuleVO existingRule() {
        PortForwardingRuleVO rule = forwarding.findById(500L);
        when(rule.getState()).thenReturn(FirewallRule.State.Active);
        when(rule.getProtocol()).thenReturn("tcp");
        when(rule.getSourcePortStart()).thenReturn(8443);
        when(rule.getSourcePortEnd()).thenReturn(8443);
        when(rule.getDestinationPortStart()).thenReturn(8080);
        when(rule.getDestinationPortEnd()).thenReturn(8080);
        when(rule.getDestinationIpAddress()).thenReturn(new Ip("10.0.0.10"));
        when(rule.getVirtualMachineId()).thenReturn(10L);
        when(rule.getAccountId()).thenReturn(20L);
        when(rule.getNetworkId()).thenReturn(100L);
        when(forwarding.listByIpAndNotRevoked(200L)).thenReturn(List.of(rule));
        return rule;
    }

    private ReverseProxyHostVO editableProxy() {
        existingRule();
        expose("app");
        ReverseProxyHostVO proxy = stored.get();
        NpmProxyHost host = new NpmProxyHost();
        host.setId(99L);
        host.setDomainNames(List.of(proxy.getFqdn()));
        host.setMeta(Map.of("cloudstack_instance_proxy_uuid", proxy.getUuid()));
        when(client.findProxyHostByDomain(proxy.getFqdn())).thenReturn(host);
        org.mockito.Mockito.clearInvocations(client, rules, firewall, forwarding, hosts);
        return proxy;
    }

    @Test
    public void renamePreservesExposureIdentityAndManualForwarding() {
        ReverseProxyHostVO proxy = editableProxy();
        String uuid = proxy.getUuid();
        manager.updateInstanceProxy(1L, "renamed", null, null, false);
        assertEquals(uuid, proxy.getUuid());
        assertEquals("renamed.apps.example.com", proxy.getFqdn());
        assertEquals(99L, proxy.getNpmProxyHostId());
        assertEquals(Long.valueOf(500L), proxy.getPortForwardingRuleId());
        assertEquals(8080, proxy.getForwardPort());
        verify(client).updateProxyHostDomains(99L, List.of("renamed.apps.example.com"));
        verify(client, never()).createProxyHost(any());
        verify(client, never()).deleteProxyHost(anyLong());
        verify(hosts, never()).persist(any());
        verifyNoInteractions(rules, firewall, forwarding);
    }

    @Test
    public void renameRejectsDisabledCustomNamesAndConflicts() {
        editableProxy();
        manager.customNames = false;
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        manager.customNames = true;
        when(hosts.findByFqdn("taken.apps.example.com")).thenReturn(new ReverseProxyHostVO());
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, "taken", null, null, false));
        when(client.findProxyHostByDomain("external.apps.example.com")).thenReturn(new NpmProxyHost());
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, "external", null, null, false));
        verify(client, never()).updateProxyHostDomains(anyLong(), any());
    }

    @Test
    public void renameChecksAccessAndRejectsInactiveOrMissingExposures() {
        ReverseProxyHostVO proxy = editableProxy();
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(42L, "renamed", null, null, false));
        proxy.setState(ReverseProxyHost.State.Error);
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        proxy.setState(ReverseProxyHost.State.Active);
        doThrow(new PermissionDeniedException("denied")).when(accounts).checkAccess(eq(caller), any(), eq(false), eq(proxy));
        assertThrows(PermissionDeniedException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        verify(client, never()).updateProxyHostDomains(anyLong(), any());
    }

    @Test
    public void renameRefusesAnUnrelatedNpmHost() {
        ReverseProxyHostVO proxy = editableProxy();
        NpmProxyHost host = new NpmProxyHost();
        host.setId(99L);
        host.setMeta(Map.of("cloudstack_instance_proxy_uuid", "another-proxy"));
        when(client.findProxyHostByDomain(proxy.getFqdn())).thenReturn(host);
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        verify(client, never()).updateProxyHostDomains(anyLong(), any());
    }

    @Test
    public void renameRestoresOriginalDomainWhenNpmFailsOrDatabaseCannotSave() {
        ReverseProxyHostVO proxy = editableProxy();
        doThrow(new CloudRuntimeException("timeout")).when(client).updateProxyHostDomains(99L, List.of("renamed.apps.example.com"));
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        verify(client).updateProxyHostDomains(99L, List.of("app.apps.example.com"));
        assertEquals("app.apps.example.com", proxy.getFqdn());
        when(hosts.update(anyLong(), any())).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, "other", null, null, false));
        assertEquals("app", proxy.getName());
        assertEquals(ReverseProxyHost.State.Active, proxy.getState());
        verifyNoInteractions(rules, firewall, forwarding);
    }

    @Test
    public void failedRenameRollbackMarksExposureForAdministratorFollowUp() {
        ReverseProxyHostVO proxy = editableProxy();
        doThrow(new CloudRuntimeException("unreachable")).when(client).updateProxyHostDomains(anyLong(), any());
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, "renamed", null, null, false));
        assertEquals(ReverseProxyHost.State.Error, proxy.getState());
        verify(hosts).update(1L, proxy);
        verify(client, never()).deleteProxyHost(anyLong());
        verifyNoInteractions(rules, firewall, forwarding);
    }

    @Test
    public void unchangedNameIsANoopAndRandomRenameRemainsAvailableByApi() {
        editableProxy();
        manager.updateInstanceProxy(1L, "app", null, null, false);
        verify(client, never()).updateProxyHostDomains(anyLong(), any());
        manager.customNames = false;
        manager.generated.add("vm-random");
        manager.updateInstanceProxy(1L, null, null, null, true);
        assertEquals("vm-random.apps.example.com", stored.get().getFqdn());
    }

    @Test
    public void exclusiveManagedForwardingUpdatesInPlaceWithBackendPort() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        proxy.setPortForwardingRuleManaged(true);
        PortForwardingRuleVO rule = forwarding.findById(500L);
        when(rules.updatePortForwardingRule(any())).thenReturn(rule);
        manager.updateInstanceProxy(1L, null, "https", 8444, false);
        assertEquals(8444, proxy.getForwardPort());
        assertEquals("https", proxy.getForwardScheme());
        assertEquals(Integer.valueOf(8443), proxy.getPublicPort());
        ArgumentCaptor<org.apache.cloudstack.api.command.user.firewall.UpdatePortForwardingRuleCmd> command =
                ArgumentCaptor.forClass(org.apache.cloudstack.api.command.user.firewall.UpdatePortForwardingRuleCmd.class);
        verify(rules).updatePortForwardingRule(command.capture());
        assertEquals(Long.valueOf(500L), command.getValue().getId());
        assertEquals(Integer.valueOf(8444), command.getValue().getPrivatePort());
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "https", "203.0.113.10", 8443);
        verify(rules, never()).createPortForwardingRule(any(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    private void replacementRule() throws Exception {
        PortForwardingRuleVO replacement = mock(PortForwardingRuleVO.class);
        when(replacement.getId()).thenReturn(600L);
        when(rules.createPortForwardingRule(any(), eq(10L), isNull(), anyBoolean(), eq(true))).thenReturn(replacement);
        when(rules.revokePortForwardingRule(600L, true)).thenReturn(true);
        when(firewall.revokeRelatedFirewallRule(600L, true)).thenReturn(true);
    }

    @Test
    public void privatePortEditKeepsManualForwardingAndCreatesSeparateManagedRule() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        replacementRule();
        manager.updateInstanceProxy(1L, null, "http", 8081, false);
        assertEquals(Integer.valueOf(8081), proxy.getPublicPort());
        assertEquals(Long.valueOf(600L), proxy.getPortForwardingRuleId());
        assertTrue(proxy.isPortForwardingRuleManaged());
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "http", "203.0.113.10", 8081);
        verify(rules, never()).updatePortForwardingRule(any());
        verify(rules, never()).revokePortForwardingRule(500L, true);
        verify(firewall, never()).revokeRelatedFirewallRule(500L, true);
    }

    @Test
    public void replacementForwardingSkipsAnOccupiedPreferredPort() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        PortForwardingRuleVO oldRule = forwarding.findById(500L);
        PortForwardingRuleVO occupied = mock(PortForwardingRuleVO.class);
        when(occupied.getProtocol()).thenReturn("tcp");
        when(occupied.getSourcePortStart()).thenReturn(8081);
        when(occupied.getSourcePortEnd()).thenReturn(8081);
        when(forwarding.listByIpAndNotRevoked(200L)).thenReturn(List.of(oldRule, occupied));
        replacementRule();
        manager.updateInstanceProxy(1L, null, null, 8081, false);
        assertEquals(Integer.valueOf(49152), proxy.getPublicPort());
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "http", "203.0.113.10", 49152);
    }

    @Test
    public void privatePortEditCanReplaceAMissingForwardingRule() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        proxy.setPortForwardingRuleManaged(true);
        when(forwarding.listByIpAndNotRevoked(200L)).thenReturn(List.of());
        replacementRule();
        manager.updateInstanceProxy(1L, null, null, 8081, false);
        assertEquals(Long.valueOf(600L), proxy.getPortForwardingRuleId());
        assertEquals(8081, proxy.getForwardPort());
        verify(rules, never()).updatePortForwardingRule(any());
    }

    @Test
    public void privatePortEditDoesNotChangeManagedRuleSharedByAnotherExposure() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        proxy.setPortForwardingRuleManaged(true);
        ReverseProxyHostVO other = new ReverseProxyHostVO();
        Field id = ReverseProxyHostVO.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setLong(other, 2L);
        when(hosts.listByPortForwardingRuleId(500L)).thenReturn(List.of(proxy, other));
        replacementRule();
        manager.updateInstanceProxy(1L, null, null, 8081, false);
        assertEquals(Long.valueOf(600L), proxy.getPortForwardingRuleId());
        verify(rules, never()).updatePortForwardingRule(any());
        verify(rules, never()).revokePortForwardingRule(500L, true);
    }

    @Test
    public void failedBackendUpdateRestoresNpmAndRemovesOnlyReplacementRule() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        replacementRule();
        doThrow(new CloudRuntimeException("timeout")).when(client)
                .updateProxyHost(99L, List.of("app.apps.example.com"), "https", "203.0.113.10", 8081);
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, null, "https", 8081, false));
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "http", "203.0.113.10", 8443);
        verify(rules).revokePortForwardingRule(600L, true);
        verify(firewall).revokeRelatedFirewallRule(600L, true);
        verify(rules, never()).revokePortForwardingRule(500L, true);
        assertEquals(Long.valueOf(500L), proxy.getPortForwardingRuleId());
        assertEquals(Integer.valueOf(8443), proxy.getPublicPort());
        assertEquals(8080, proxy.getForwardPort());
        assertFalse(proxy.isPortForwardingRuleManaged());
    }

    @Test
    public void failedManagedForwardingUpdateRestoresTheOriginalPrivatePort() {
        ReverseProxyHostVO proxy = editableProxy();
        proxy.setPortForwardingRuleManaged(true);
        PortForwardingRuleVO rule = forwarding.findById(500L);
        doAnswer(invocation -> {
            org.apache.cloudstack.api.command.user.firewall.UpdatePortForwardingRuleCmd command = invocation.getArgument(0);
            if (command.getPrivatePort() == 8081) {
                throw new CloudRuntimeException("router apply failed");
            }
            assertEquals(Integer.valueOf(8080), command.getPrivatePort());
            return rule;
        }).when(rules).updatePortForwardingRule(any());
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, null, null, 8081, false));
        verify(rules, org.mockito.Mockito.times(2)).updatePortForwardingRule(any());
        verify(client, never()).updateProxyHost(anyLong(), any(), any(), any(), any());
        assertEquals(ReverseProxyHost.State.Active, proxy.getState());
        assertEquals(8080, proxy.getForwardPort());
    }

    @Test
    public void failedReplacementCleanupRetainsTheRuleReferenceForRetry() throws Exception {
        ReverseProxyHostVO proxy = editableProxy();
        replacementRule();
        when(rules.applyPortForwardingRules(200L, caller)).thenReturn(false);
        when(firewall.revokeRelatedFirewallRule(600L, true)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> manager.updateInstanceProxy(1L, null, null, 8081, false));
        assertEquals(ReverseProxyHost.State.Error, proxy.getState());
        assertEquals(Long.valueOf(600L), proxy.getPortForwardingRuleId());
        assertTrue(proxy.isPortForwardingRuleManaged());
        verify(hosts).update(1L, proxy);
        verify(rules, never()).revokePortForwardingRule(500L, true);
    }

    @Test
    public void sharedNetworkBackendEditUpdatesOnlyTheExistingNpmHost() {
        ReverseProxyHostVO proxy = editableProxy();
        proxy.setPublicIpId(null);
        proxy.setPublicPort(null);
        proxy.setPortForwardingRuleId(null);
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        manager.updateInstanceProxy(1L, null, "https", 8444, false);
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "https", "10.0.0.10", 8444);
        verifyNoInteractions(rules, firewall, forwarding);
        assertEquals(8444, proxy.getForwardPort());
    }

    @Test
    public void randomNamePolicyAllowsBackendEditsAndValidatesPortAndProtocol() {
        editableProxy();
        manager.customNames = false;
        manager.updateInstanceProxy(1L, null, "https", null, false);
        verify(client).updateProxyHost(99L, List.of("app.apps.example.com"), "https", "203.0.113.10", 8443);
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, null, "ftp", null, false));
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, null, null, 0, false));
        assertThrows(InvalidParameterValueException.class, () -> manager.updateInstanceProxy(1L, "new", null, null, true));
    }

    @Test
    public void matchingManualForwardingIsReusedWithoutChangingNetworkRules() {
        existingRule();
        ReverseProxyHost proxy = expose("app");
        assertEquals(Long.valueOf(500L), proxy.getPortForwardingRuleId());
        assertFalse(proxy.isPortForwardingRuleManaged());
        assertEquals(ReverseProxyHost.State.Active, proxy.getState());
        verify(client).createProxyHost(any());
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void deletingAnExposurePreservesItsManualForwardingAndFirewall() {
        existingRule();
        expose("app");
        manager.deleteInstanceProxy(1L);
        verify(client).deleteProxyHost(99L);
        verifyNoInteractions(rules, firewall);
        assertNull(stored.get());
    }

    @Test
    public void failedNpmCreationPreservesReusedManualForwarding() {
        existingRule();
        when(client.createProxyHost(any())).thenThrow(new CloudRuntimeException("NPM unavailable"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
        assertNull(stored.get());
    }

    @Test
    public void failedOwnershipLookupCannotDeleteAManualRule() {
        existingRule();
        when(hosts.listByPortForwardingRuleId(500L)).thenThrow(new CloudRuntimeException("lookup failed"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
        assertNull(stored.get());
    }

    @Test
    public void domainDeletionPreservesReusedManualForwarding() {
        existingRule();
        expose("app");
        when(hosts.listByDomainId(30L)).thenReturn(List.of(stored.get()));
        manager.deleteReverseProxyDomain(30L);
        verifyNoInteractions(rules, firewall);
        verify(domains).remove(30L);
    }

    @Test
    public void matchingPortWithinAnExistingRangeIsReused() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getSourcePortStart()).thenReturn(8440);
        when(rule.getSourcePortEnd()).thenReturn(8450);
        when(rule.getDestinationPortStart()).thenReturn(8077);
        when(rule.getDestinationPortEnd()).thenReturn(8087);
        assertEquals(Long.valueOf(500L), expose("app").getPortForwardingRuleId());
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void conflictingDestinationPortIsRejectedWithoutChangingTheRule() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getDestinationPortStart()).thenReturn(8081);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
        verify(client, never()).createProxyHost(any());
    }

    @Test
    public void forwardingToAnotherInstanceIsNotReused() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getVirtualMachineId()).thenReturn(11L);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void forwardingToAnotherNicIpIsNotReused() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getDestinationIpAddress()).thenReturn(new Ip("10.0.0.11"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void forwardingForAnotherNetworkIsNotReused() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getNetworkId()).thenReturn(101L);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void forwardingForAnotherAccountIsNotReused() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getAccountId()).thenReturn(21L);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void pendingForwardingIsNotReusedOrReapplied() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getState()).thenReturn(FirewallRule.State.Add);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void existingUdpRuleDoesNotPreventCreatingTcpForwarding() throws Exception {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getProtocol()).thenReturn("udp");
        assertTrue(expose("app").isPortForwardingRuleManaged());
        verify(rules).createPortForwardingRule(any(), eq(10L), isNull(), eq(true), eq(true));
    }

    @Test
    public void existingRuleOnAnotherPublicPortDoesNotPreventCreation() throws Exception {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getSourcePortStart()).thenReturn(8444);
        when(rule.getSourcePortEnd()).thenReturn(8444);
        assertTrue(expose("app").isPortForwardingRuleManaged());
        verify(rules).createPortForwardingRule(any(), eq(10L), isNull(), eq(true), eq(true));
    }

    @Test
    public void sharedManagedRuleIsRemovedOnlyAfterItsLastExposure() throws Exception {
        Map<Long, ReverseProxyHostVO> mappings = new HashMap<>();
        doAnswer(invocation -> {
            ReverseProxyHostVO proxy = invocation.getArgument(0);
            Field id = ReverseProxyHostVO.class.getDeclaredField("id");
            id.setAccessible(true);
            id.setLong(proxy, mappings.size() + 1L);
            mappings.put(proxy.getId(), proxy);
            return proxy;
        }).when(hosts).persist(any());
        when(hosts.findById(anyLong())).thenAnswer(invocation -> mappings.get(invocation.getArgument(0)));
        when(hosts.remove(anyLong())).thenAnswer(invocation -> mappings.remove(invocation.getArgument(0)) != null);
        when(hosts.listByPortForwardingRuleId(500L)).thenAnswer(invocation -> mappings.values().stream()
                .filter(proxy -> Long.valueOf(500L).equals(proxy.getPortForwardingRuleId())).collect(java.util.stream.Collectors.toList()));
        ReverseProxyHost first = expose("first");
        existingRule();
        ReverseProxyHost second = expose("second");
        assertTrue(first.isPortForwardingRuleManaged());
        assertTrue(second.isPortForwardingRuleManaged());
        manager.deleteInstanceProxy(first.getId());
        verify(rules, never()).revokePortForwardingRule(500L, true);
        manager.deleteInstanceProxy(second.getId());
        verify(rules).revokePortForwardingRule(500L, true);
        verify(firewall).revokeRelatedFirewallRule(500L, true);
        assertTrue(mappings.isEmpty());
    }

    private void legacyTemporaryRule() {
        temporaryRule = mock(PortForwardingRuleVO.class);
        when(temporaryRule.getId()).thenReturn(700L);
        when(temporaryRule.getUuid()).thenReturn("temporary-rule-uuid");
        when(temporaryRule.getXid()).thenReturn("rphc-0123456789abcdef0123456789abcdef");
        when(temporaryRule.getCreated()).thenReturn(new Date());
        when(temporaryRule.getSourceIpAddressId()).thenReturn(200L);
        when(forwarding.findById(700L)).thenReturn(temporaryRule);
        when(firewall.revokeRelatedFirewallRule(700L, true)).thenReturn(true);
        when(rules.revokePortForwardingRule(700L, true)).thenReturn(true);
    }

    private InstanceProxyBackendHealthResponse checkBackend() {
        return manager.checkInstanceProxyBackend(10L, "http", 8080, 100L, 200L, 8443);
    }

    @Test
    public void isolatedHealthCheckUsesPrivateBackendWithoutAnyForwarding() {
        InstanceProxyBackendHealthResponse result = checkBackend();
        assertTrue(result.getHealthy());
        assertTrue(result.getCleanupComplete());
        assertEquals("10.0.0.10", checkedIp);
        assertEquals(8080, checkedPort);
        verifyNoInteractions(rules, firewall, forwarding, client, hosts, domains, grants);
    }

    @Test
    public void failedRouterCheckCreatesNoRulesOrNpmHosts() {
        probeResult.setHealthy(false);
        probeResult.setHttpStatus(503);
        probeResult.setMessage("Backend returned HTTP 503");
        assertFalse(checkBackend().getHealthy());
        verifyNoInteractions(rules, firewall, forwarding, client, hosts);
    }

    @Test
    public void healthCheckDoesNotRequirePublicIpOrForwarding() {
        assertTrue(manager.checkInstanceProxyBackend(10L, "http", 8080, 100L, null, null).getHealthy());
        verifyNoInteractions(ips, rules, firewall, forwarding);
    }

    @Test
    public void healthCheckPreservesExistingManualForwarding() {
        existingRule();
        assertTrue(checkBackend().getHealthy());
        verifyNoInteractions(rules, firewall, client, hosts);
    }

    @Test
    public void healthCheckSharedNetworkUsesPrivateAddressWithoutRules() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        assertTrue(manager.checkInstanceProxyBackend(10L, "http", 8080, 100L, null, null).getHealthy());
        assertEquals("10.0.0.10", checkedIp);
        assertEquals(8080, checkedPort);
        verifyNoInteractions(rules, firewall, client, hosts);
    }

    @Test
    public void stoppedInstanceHealthCheckCreatesNoResources() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        InstanceProxyBackendHealthResponse result = checkBackend();
        assertFalse(result.getHealthy());
        assertTrue(result.getMessage().contains("not running"));
        assertEquals(0, probeCalls);
        verifyNoInteractions(rules, firewall, client, hosts);
    }

    @Test
    public void healthCheckEnforcesVmAccessBeforeAnyNetworkOperations() {
        doThrow(new PermissionDeniedException("not owned")).when(accounts).checkAccess(eq(caller), any(), eq(false), eq(vm));
        assertThrows(PermissionDeniedException.class, this::checkBackend);
        verifyNoInteractions(rules, firewall, client, hosts);
    }

    @Test
    public void healthCheckValidatesLegacyPublicIpOwnershipWhenProvided() {
        when(ip.getAccountId()).thenReturn(21L);
        assertThrows(InvalidParameterValueException.class, this::checkBackend);
        assertEquals(0, probeCalls);
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void healthCheckIgnoresConflictingPublicForwardingAndTestsApplication() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getDestinationPortStart()).thenReturn(9000);
        assertTrue(checkBackend().getHealthy());
        assertEquals(1, probeCalls);
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void legacyTemporaryRulesAreCleanedEvenWhenIntegrationDisabled() {
        legacyTemporaryRule();
        when(temporaryRule.getCreated()).thenReturn(new Date(System.currentTimeMillis() - 600000));
        staleRules = List.of(temporaryRule);
        manager.integrationEnabled = false;
        manager.cleanupStaleBackendRules();
        verify(rules).revokePortForwardingRule(700L, true);
    }

    @Test
    public void staleCleanupSkipsManualAndFreshRules() {
        legacyTemporaryRule();
        staleRules = List.of(existingRule(), temporaryRule);
        manager.cleanupStaleBackendRules();
        verify(rules, never()).revokePortForwardingRule(anyLong(), anyBoolean());
    }

    @Test
    public void staleCleanupFailureIsRetriedOnLaterScan() {
        legacyTemporaryRule();
        when(temporaryRule.getCreated()).thenReturn(new Date(System.currentTimeMillis() - 600000));
        staleRules = List.of(temporaryRule);
        when(rules.revokePortForwardingRule(700L, true)).thenReturn(false, true);
        manager.cleanupStaleBackendRules();
        manager.cleanupStaleBackendRules();
        verify(rules, org.mockito.Mockito.times(2)).revokePortForwardingRule(700L, true);
    }

    @Test
    public void normalExposureCannotAdoptLegacyTemporaryHealthRule() {
        PortForwardingRuleVO rule = existingRule();
        when(rule.getXid()).thenReturn("rphc-0123456789abcdef0123456789abcdef");
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verifyNoInteractions(rules, firewall);
        verify(client, never()).createProxyHost(any());
    }

    @Test
    public void healthCheckUsesVpcPrivateBackendWithoutForwarding() {
        when(network.getVpcId()).thenReturn(300L);
        assertTrue(checkBackend().getHealthy());
        assertEquals("10.0.0.10", checkedIp);
        verifyNoInteractions(rules, firewall, forwarding);
    }

    @Test
    public void isolatedExposureCreatesTcpForwardingAndPublicNpmTarget() throws Exception {
        ReverseProxyHost proxy = expose("myapp");
        ArgumentCaptor<PortForwardingRule> pf = ArgumentCaptor.forClass(PortForwardingRule.class);
        verify(rules).createPortForwardingRule(pf.capture(), eq(10L), isNull(), eq(true), eq(true));
        assertEquals("tcp", pf.getValue().getProtocol());
        assertEquals(Integer.valueOf(8443), pf.getValue().getSourcePortStart());
        assertEquals(8080, pf.getValue().getDestinationPortStart());
        assertEquals("10.0.0.10", pf.getValue().getDestinationIpAddress().addr());
        ArgumentCaptor<NpmProxyHost> npm = ArgumentCaptor.forClass(NpmProxyHost.class);
        verify(client).createProxyHost(npm.capture());
        assertEquals(proxy.getUuid(), npm.getValue().getMeta().get("cloudstack_instance_proxy_uuid"));
        assertEquals("203.0.113.10", npm.getValue().getForwardHost());
        assertEquals(Integer.valueOf(8443), npm.getValue().getForwardPort());
        assertEquals("myapp.apps.example.com", proxy.getFqdn());
        assertEquals("10.0.0.10", proxy.getIpAddress());
        assertEquals(Long.valueOf(500), proxy.getPortForwardingRuleId());
        assertEquals(ReverseProxyHost.State.Active, proxy.getState());
    }

    @Test
    public void portConflictDoesNotCreateNpmHostOrDeleteOtherRules() throws Exception {
        when(rules.createPortForwardingRule(any(), eq(10L), isNull(), anyBoolean(), eq(true)))
                .thenThrow(new NetworkRuleConflictException("port already in use"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(client, never()).createProxyHost(any());
        verify(rules, never()).revokePortForwardingRule(anyLong(), anyBoolean());
        assertNull(stored.get());
    }

    @Test
    public void deniedVmAccessCreatesNoResources() {
        doThrow(new PermissionDeniedException("not owned")).when(accounts).checkAccess(eq(caller), any(), eq(false), eq(vm));
        assertThrows(PermissionDeniedException.class, () -> expose("app"));
        verifyNoInteractions(client, rules);
    }

    @Test
    public void domainListingRejectsForeignVmBeforeCheckingNetworkGrants() {
        doThrow(new PermissionDeniedException("not owned")).when(accounts).checkAccess(eq(caller), any(), eq(false), eq(vm));
        ListReverseProxyDomainsCmd cmd = mock(ListReverseProxyDomainsCmd.class);
        when(cmd.getVirtualMachineId()).thenReturn(10L);
        assertThrows(PermissionDeniedException.class, () -> manager.listReverseProxyDomains(cmd));
        verifyNoInteractions(grants);
    }

    @Test
    public void staleSharedNetworkSettingDoesNotPreventSelectingAnIsolatedNetwork() {
        manager.defaultNetworkUnavailable = true;
        ListReverseProxyDomainsCmd cmd = mock(ListReverseProxyDomainsCmd.class);
        when(cmd.getVirtualMachineId()).thenReturn(10L);
        when(cmd.getId()).thenReturn(null);
        assertEquals(1, manager.listReverseProxyDomains(cmd).getResponses().size());
        assertEquals(ReverseProxyHost.State.Active, expose("app").getState());
    }

    @Test
    public void publicPortDefaultsToApplicationPort() {
        ReverseProxyHost proxy = manager.createInstanceProxy(10L, "app", "http", 8080, 30L, 100L, 200L, null);
        assertEquals(Integer.valueOf(8080), proxy.getPublicPort());
    }

    @Test
    public void sharedExposureUsesPrivateIpAndCreatesNoForwarding() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        ReverseProxyHost proxy = manager.createInstanceProxy(10L, "app", "https", 443, 30L, null, null, null);
        ArgumentCaptor<NpmProxyHost> npm = ArgumentCaptor.forClass(NpmProxyHost.class);
        verify(client).createProxyHost(npm.capture());
        assertEquals("10.0.0.10", npm.getValue().getForwardHost());
        assertNull(proxy.getPublicPort());
        assertNull(proxy.getPortForwardingRuleId());
        verifyNoInteractions(rules, firewall);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void sharedExposureRejectsForwardingParameters() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsPublicIpOwnedByAnotherAccount() {
        when(ip.getAccountId()).thenReturn(21L);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsIpOnAnotherNetwork() {
        when(ip.getAssociatedWithNetworkId()).thenReturn(101L);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsUnallocatedIp() {
        when(ip.readyToUse()).thenReturn(false);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsStaticNatIp() {
        when(ip.isOneToOneNat()).thenReturn(true);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void requiresPublicIpForIsolatedExposure() {
        manager.createInstanceProxy(10L, "app", "http", 8080, 30L, 100L, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsOfferingWithoutForwarding() {
        when(networkModel.areServicesSupportedInNetwork(100L, Network.Service.PortForwarding)).thenReturn(false);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsNetworkWithoutInstanceNic() {
        when(networkModel.getNicInNetwork(10L, 100L)).thenReturn(null);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsDestroyedInstance() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Destroyed);
        expose("app");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsInvalidPublicPort() {
        manager.createInstanceProxy(10L, "app", "http", 8080, 30L, 100L, 200L, 65536);
    }

    @Test
    public void disabledCustomNamesAreEnforcedByServer() {
        manager.customNames = false;
        assertThrows(InvalidParameterValueException.class, () -> expose("chosen"));
        verifyNoInteractions(client, rules);
        ReverseProxyHost proxy = expose(null);
        assertTrue(proxy.getName().startsWith("vm-"));
        assertEquals(proxy.getName(), manager.validateProxyName(proxy.getName()));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void disabledCustomNamesRejectAvailabilityChecks() {
        manager.customNames = false;
        manager.checkInstanceProxyName("chosen", 30L, mock(CheckInstanceProxyNameCmd.class));
    }

    @Test
    public void generatedNamesRetryInvalidAndOccupiedCandidates() {
        manager.generated.addAll(Arrays.asList("-invalid", "occupied", "available"));
        when(hosts.findByFqdn("occupied.apps.example.com")).thenReturn(new ReverseProxyHostVO());
        assertEquals("available", expose(null).getName());
    }

    @Test(expected = CloudRuntimeException.class)
    public void generatedNameRetriesAreBounded() {
        for (int i = 0; i < 10; i++) manager.generated.add("occupied");
        when(hosts.findByFqdn("occupied.apps.example.com")).thenReturn(new ReverseProxyHostVO());
        expose(null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void customNameCollisionIsRejected() {
        when(client.findProxyHostByDomain("app.apps.example.com")).thenReturn(new NpmProxyHost());
        expose("app");
    }

    @Test
    public void firewallApplyFailureRollsBackOwnedForwarding() throws Exception {
        when(firewall.applyIngressFirewallRules(200L, caller)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(rules).revokePortForwardingRule(500L, true);
        verify(firewall).revokeRelatedFirewallRule(500L, true);
        verify(client, never()).createProxyHost(any());
        assertNull(stored.get());
    }

    @Test
    public void forwardingApplyFailureRollsBackOwnedResources() throws Exception {
        when(rules.applyPortForwardingRules(200L, caller)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void npmCreateFailureRollsBackForwarding() {
        when(client.createProxyHost(any())).thenThrow(new CloudRuntimeException("NPM unavailable"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void timedOutNpmCreateIsDiscoveredAndRemoved() {
        NpmProxyHost remote = new NpmProxyHost();
        remote.setId(99L);
        when(client.findProxyHostByDomain("app.apps.example.com")).thenReturn(null, remote);
        when(client.createProxyHost(any())).thenAnswer(invocation -> {
            remote.setMeta(Map.of("cloudstack_instance_proxy_uuid", stored.get().getUuid()));
            throw new CloudRuntimeException("timeout");
        });
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(client).deleteProxyHost(99L);
        verify(rules).revokePortForwardingRule(500L, true);
    }

    @Test
    public void rollbackNeverDeletesAnUnrelatedNpmHostClaimingTheSameName() {
        NpmProxyHost foreign = new NpmProxyHost();
        foreign.setId(101L);
        foreign.setMeta(Map.of("cloudstack_instance_proxy_uuid", "another-reservation"));
        when(client.findProxyHostByDomain("app.apps.example.com")).thenReturn(null, foreign);
        when(client.createProxyHost(any())).thenThrow(new CloudRuntimeException("hostname already taken"));
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(client, never()).deleteProxyHost(anyLong());
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void failedCleanupRetainsMappingAndCanBeRetried() {
        when(client.createProxyHost(any())).thenThrow(new CloudRuntimeException("NPM unavailable"));
        when(rules.revokePortForwardingRule(500L, true)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        assertEquals(ReverseProxyHost.State.Error, stored.get().getState());
        assertEquals(Long.valueOf(500L), stored.get().getPortForwardingRuleId());
        when(rules.revokePortForwardingRule(500L, true)).thenReturn(true);
        manager.deleteInstanceProxy(1L);
        assertNull(stored.get());
    }

    @Test
    public void persistenceFailureAfterNpmCreationRollsBackBothResources() {
        when(hosts.update(anyLong(), any())).thenReturn(true, false);
        assertThrows(CloudRuntimeException.class, () -> expose("app"));
        verify(client).deleteProxyHost(99L);
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void deletingExposureRemovesOwnedResources() {
        expose("app");
        manager.deleteInstanceProxy(1L);
        verify(client).deleteProxyHost(99L);
        verify(firewall).revokeRelatedFirewallRule(500L, true);
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void deletingSharedExposureDoesNotTouchFirewallOrForwarding() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        manager.createInstanceProxy(10L, "app", "http", 80, 30L, 100L, null, null);
        manager.deleteInstanceProxy(1L);
        verifyNoInteractions(rules, firewall);
    }

    @Test
    public void deletionCanRetryAfterForwardingWasAlreadyRemoved() {
        expose("app");
        when(forwarding.findById(500L)).thenReturn(null);
        manager.deleteInstanceProxy(1L);
        verify(firewall).revokeRelatedFirewallRule(500L, true);
        verify(rules, never()).revokePortForwardingRule(anyLong(), anyBoolean());
        assertNull(stored.get());
    }

    @Test
    public void npmDeletionFailureRetainsMappingAndForwardingForRetry() {
        expose("app");
        when(client.deleteProxyHost(99L)).thenThrow(new CloudRuntimeException("NPM unavailable"));
        assertThrows(CloudRuntimeException.class, () -> manager.deleteInstanceProxy(1L));
        assertEquals(ReverseProxyHost.State.Error, stored.get().getState());
        verify(rules, never()).revokePortForwardingRule(anyLong(), anyBoolean());
    }

    @Test
    public void expungeCleansForwardingAndProxy() {
        expose("app");
        when(hosts.listByVmId(10L)).thenReturn(List.of(stored.get()));
        manager.cleanupProxiesForVm(10L);
        verify(rules).revokePortForwardingRule(500L, true);
        assertNull(stored.get());
    }

    @Test
    public void domainDeletionFailureRetainsDomainAndErrorMapping() {
        expose("app");
        when(hosts.listByDomainId(30L)).thenReturn(List.of(stored.get()));
        when(client.deleteProxyHost(99L)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> manager.deleteReverseProxyDomain(30L));
        verify(domains, never()).remove(30L);
        assertEquals(ReverseProxyHost.State.Error, stored.get().getState());
    }

    @Test
    public void isolatedNetworkCanBeGrantedDomainAccess() {
        when(domain.isPublic()).thenReturn(false);
        manager.updateGrants(domain, null, List.of(100L));
        verify(grants).persist(any(ReverseProxyDomainMapVO.class));
        when(grants.findByDomainAndNetwork(30L, 100L)).thenReturn(new ReverseProxyDomainMapVO(30L, null, 100L));
        assertTrue(manager.canUseDomain(caller, vm, domain));
    }

    @Test
    public void nameCheckUsesVmNetworkGrantAndChecksVmAccess() {
        when(domain.isPublic()).thenReturn(false);
        when(grants.findByDomainAndNetwork(30L, 100L)).thenReturn(new ReverseProxyDomainMapVO(30L, null, 100L));
        CheckInstanceProxyNameCmd cmd = mock(CheckInstanceProxyNameCmd.class);
        when(cmd.getVirtualMachineId()).thenReturn(10L);
        assertTrue(manager.checkInstanceProxyName("app", 30L, cmd).getAvailable());
        verify(accounts).checkAccess(eq(caller), any(), eq(false), eq(vm));
    }

    @Test
    public void vpcExposureUsesNativeForwardingWithoutStandaloneFirewallRule() throws Exception {
        when(network.getVpcId()).thenReturn(300L);
        when(ip.getAssociatedWithNetworkId()).thenReturn(null);
        when(ip.getVpcId()).thenReturn(300L);
        expose("app");
        verify(rules).createPortForwardingRule(any(), eq(10L), isNull(), eq(false), eq(true));
        verify(firewall, never()).applyIngressFirewallRules(anyLong(), any());
    }
}
