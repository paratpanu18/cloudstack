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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import org.apache.cloudstack.context.CallContext;
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

    private class TestManager extends ReverseProxyManagerImpl {
        boolean customNames = true;
        boolean defaultNetworkUnavailable;
        Queue<String> generated = new ArrayDeque<>();

        @Override
        protected void validateConfiguration() { }

        @Override
        public boolean isEnabled() { return true; }

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
