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

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.command.admin.reverseproxy.AddReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.DeleteReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.ListReverseProxyHostsCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.UpdateReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.AddInstanceProxyCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.DeleteInstanceProxyCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListInstanceProxiesCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import org.apache.cloudstack.api.response.CheckInstanceProxyNameResponse;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ReverseProxyDomainResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.reverseproxy.client.NpmCertificate;
import org.apache.cloudstack.reverseproxy.client.NpmClient;
import org.apache.cloudstack.reverseproxy.client.NpmProxyHost;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainMapDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyHostDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentLifecycleBase;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

public class ReverseProxyManagerImpl extends ComponentLifecycleBase implements ReverseProxyService, Configurable {

    /**
     * A proxy name must be a single valid DNS label (lowercase letters, digits and hyphens,
     * starting and ending with an alphanumeric character).
     */
    private static final Pattern PROXY_NAME_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /**
     * A domain suffix must consist of at least two valid DNS labels
     */
    private static final Pattern DOMAIN_NAME_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private static final String PROXY_UUID_META_KEY = "cloudstack_instance_proxy_uuid";

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    @Inject
    private ReverseProxyHostDao reverseProxyHostDao;

    @Inject
    private ReverseProxyDomainDao reverseProxyDomainDao;

    @Inject
    private ReverseProxyDomainMapDao reverseProxyDomainMapDao;

    @Inject
    private UserVmDao userVmDao;

    @Inject
    private NetworkDao networkDao;

    @Inject
    private NetworkModel networkModel;

    @Inject
    private AccountManager accountManager;

    @Inject
    private AccountDao accountDao;

    @Inject
    private DomainDao domainDao;

    @Inject
    private IPAddressDao ipAddressDao;

    @Inject
    private RulesService rulesService;

    @Inject
    private FirewallService firewallService;

    @Inject
    private PortForwardingRulesDao portForwardingRulesDao;

    private volatile NpmClient npmClient;
    private volatile String npmClientSignature;

    ///////////////////////////////////////////////////////////
    //////////////////// Lifecycle methods ////////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        // Register a VM state listener so that proxy hosts are cleaned up when a VM is expunged
        VirtualMachine.State.getStateMachine().registerListener(new VmExpungeStateListener());
        logger.debug("Reverse Proxy integration registered the VM expunge cleanup listener");
        return true;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Service methods //////////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public boolean isEnabled() {
        return ReverseProxyEnabled.value() != null && ReverseProxyEnabled.value();
    }

    /**
     * Checks whether the integration is enabled and fully configured
     */
    protected boolean isConfigured() {
        if (!isEnabled()) {
            return false;
        }
        return StringUtils.isNoneBlank(ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(),
                ReverseProxyNpmPassword.value());
    }

    protected void validateConfiguration() {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        if (StringUtils.isBlank(ReverseProxyNpmUrl.value())) {
            throw new CloudRuntimeException("The reverse proxy integration is not fully configured, please set 'reverseproxy.npm.url'");
        }
        if (StringUtils.isBlank(ReverseProxyNpmUser.value()) || StringUtils.isBlank(ReverseProxyNpmPassword.value())) {
            throw new CloudRuntimeException("The reverse proxy integration is not fully configured, please set "
                    + "'reverseproxy.npm.user' and 'reverseproxy.npm.password'");
        }
    }

    protected NpmClient getClient() {
        final String signature = String.format("%s|%s|%s|%s|%s", ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(),
                ReverseProxyNpmPassword.value(), ReverseProxyNpmValidateSsl.value(), ReverseProxyNpmRequestTimeout.value());
        final NpmClient client = npmClient;
        if (client != null && signature.equals(npmClientSignature)) {
            return client;
        }
        synchronized (this) {
            if (npmClient == null || !signature.equals(npmClientSignature)) {
                try {
                    npmClient = new NpmClient(ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(), ReverseProxyNpmPassword.value(),
                            ReverseProxyNpmValidateSsl.value(), ReverseProxyNpmRequestTimeout.value());
                    npmClientSignature = signature;
                } catch (final KeyManagementException | KeyStoreException | NoSuchAlgorithmException e) {
                    throw new CloudRuntimeException("Failed to create the Nginx Proxy Manager API client", e);
                }
            }
            return npmClient;
        }
    }

    protected String getWildcardDomain(final ReverseProxyDomainVO domain) {
        return "*." + domain.getDomain();
    }    ///////////////////////////////////////////////////////////
    /////////////////// Domain suffix methods /////////////////
    ///////////////////////////////////////////////////////////

    protected boolean isAdmin(final Account account) {
        return account != null && account.getType() == Account.Type.ADMIN;
    }

    /**
     * Resolves the domain suffix to use for a new proxy host: when no domain id is given exactly one
     * domain suffix must be configured, otherwise the given domain suffix is validated
     */
    protected ReverseProxyDomainVO resolveProxyDomain(final Long domainId, final Account caller, final UserVmVO vm) {
        final List<ReverseProxyDomainVO> domains = reverseProxyDomainDao.listAll();
        if (domains.isEmpty()) {
            throw new CloudRuntimeException("No reverse proxy domain suffix is configured, please ask your administrator "
                    + "to add a domain suffix for the reverse proxy integration");
        }
        final ReverseProxyDomainVO domain;
        if (domainId == null) {
            if (domains.size() > 1) {
                throw new InvalidParameterValueException("Multiple reverse proxy domain suffixes are configured, "
                        + "please specify the domain suffix to expose the instance on");
            }
            domain = domains.get(0);
        } else {
            domain = reverseProxyDomainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException(String.format("Unable to find reverse proxy domain suffix with id %s", domainId));
            }
        }
        if (!canUseDomain(caller, vm, domain)) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' is not available for this instance, "
                    + "please ask your administrator for access", domain.getDomain()));
        }
        return domain;
    }

    /**
     * Checks whether the caller may use the given domain suffix: admins can use all suffixes, users need the
     * suffix to be public, granted to their account or granted to one of the networks of the instance
     */
    protected boolean canUseDomain(final Account caller, final UserVmVO vm, final ReverseProxyDomainVO domain) {
        if (caller == null) {
            return false;
        }
        if (isAdmin(caller)) {
            return true;
        }
        if (domain.isPublic()) {
            return true;
        }
        if (reverseProxyDomainMapDao.findByDomainAndAccount(domain.getId(), caller.getAccountId()) != null) {
            return true;
        }
        if (vm != null) {
            final List<? extends Nic> nics = networkModel.getNics(vm.getId());
            for (final Nic nic : nics) {
                if (nic.getNetworkId() > 0 && reverseProxyDomainMapDao.findByDomainAndNetwork(domain.getId(), nic.getNetworkId()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lists the domain suffixes the given account may use: admins get all suffixes, users get the public
     * suffixes, the suffixes granted to their account and (when a VM is given) the suffixes granted to the
     * networks of the VM
     */
    protected List<ReverseProxyDomainVO> listAllowedDomains(final Account caller, final UserVmVO vm) {
        final List<ReverseProxyDomainVO> domains = reverseProxyDomainDao.listAll();
        if (caller != null && isAdmin(caller)) {
            return domains;
        }
        final List<ReverseProxyDomainVO> allowed = new ArrayList<>();
        for (final ReverseProxyDomainVO domain : domains) {
            if (canUseDomain(caller, vm, domain)) {
                allowed.add(domain);
            }
        }
        return allowed;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Business methods /////////////////////
    ///////////////////////////////////////////////////////////

    /**
     * Validates the user provided proxy name and returns it in normalized (lowercase) form
     */
    protected String validateProxyName(final String name) {
        if (StringUtils.isBlank(name)) {
            throw new InvalidParameterValueException("A proxy name is required");
        }
        final String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (!PROXY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidParameterValueException(String.format("The name '%s' is not a valid proxy name, it must be a single "
                    + "DNS label consisting of lowercase letters, digits and hyphens and must not start or end with a hyphen", name));
        }
        final String reserved = ReverseProxyReservedNames.value();
        if (StringUtils.isNotBlank(reserved)) {
            for (final String reservedName : reserved.split(",")) {
                if (trimmed.equalsIgnoreCase(reservedName.trim())) {
                    throw new InvalidParameterValueException(String.format("The name '%s' is reserved", trimmed));
                }
            }
        }
        return trimmed;
    }

    /**
     * Validates the user provided protocol (the scheme used to forward requests to the instance)
     */
    protected String validateProtocol(final String protocol) {
        if (StringUtils.isBlank(protocol)) {
            throw new InvalidParameterValueException("A protocol is required, must be http or https");
        }
        final String trimmed = protocol.trim().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(trimmed) && !HTTPS.equals(trimmed)) {
            throw new InvalidParameterValueException(String.format("The protocol '%s' is not supported, must be http or https", protocol));
        }
        return trimmed;
    }

    /** Resolves a VM network while retaining the configured shared network restriction. */
    protected NetworkVO resolveTargetNetwork(final UserVmVO vm, final Long networkId) {
        if (networkId != null) {
            final NetworkVO selected = networkDao.findById(networkId);
            if (selected == null || (selected.getGuestType() != Network.GuestType.Shared
                    && selected.getGuestType() != Network.GuestType.Isolated)) {
                throw new InvalidParameterValueException("Select a shared or isolated instance network");
            }
            if (selected.getGuestType() == Network.GuestType.Isolated) {
                return selected;
            }
            if (StringUtils.isNotBlank(ReverseProxyNetworkId.value())
                    && !selected.getUuid().equals(ReverseProxyNetworkId.value().trim())) {
                throw new InvalidParameterValueException("Shared network exposure must use the configured reverse proxy network");
            }
            return selected;
        }
        final String configuredNetworkUuid = ReverseProxyNetworkId.value();
        if (StringUtils.isNotBlank(configuredNetworkUuid)) {
            final NetworkVO network = networkDao.findByUuid(configuredNetworkUuid.trim());
            if (network == null || network.getGuestType() != Network.GuestType.Shared) {
                throw new CloudRuntimeException("The configured reverse proxy shared network is invalid");
            }
            if (networkModel.getNicInNetwork(vm.getId(), network.getId()) != null) {
                return network;
            }
        }
        final Nic defaultNic = networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            final NetworkVO network = networkDao.findById(defaultNic.getNetworkId());
            if (network != null && (network.getGuestType() == Network.GuestType.Isolated
                    || (network.getGuestType() == Network.GuestType.Shared && StringUtils.isBlank(configuredNetworkUuid)))) {
                return network;
            }
        }
        throw new InvalidParameterValueException("The instance has no eligible shared or isolated network to expose");
    }

    protected UserVmVO getAccessibleVm(final Long vmId) {
        final UserVmVO vm = vmId == null ? null : userVmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find instance with id " + vmId);
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, vm);
        return vm;
    }

    protected boolean allowCustomName() {
        return Boolean.TRUE.equals(ReverseProxyAllowCustomName.value());
    }

    protected String generateProxyName() {
        return "vm-" + UUID.randomUUID().toString();
    }

    protected String resolveProxyName(final String name, final ReverseProxyDomainVO domain, final NpmClient client) {
        if (StringUtils.isNotBlank(name) && !allowCustomName()) {
            throw new InvalidParameterValueException("Custom proxy names are disabled; omit name for a server generated name");
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            final String candidate;
            try {
                candidate = validateProxyName(StringUtils.isBlank(name) ? generateProxyName() : name);
            } catch (final InvalidParameterValueException e) {
                if (StringUtils.isNotBlank(name)) {
                    throw e;
                }
                continue;
            }
            final String fqdn = candidate + "." + domain.getDomain();
            if (fqdn.length() > 253) {
                throw new InvalidParameterValueException("The proxy name and domain suffix exceed the DNS length limit");
            }
            if (reverseProxyHostDao.findByFqdn(fqdn) == null && client.findProxyHostByDomain(fqdn) == null) {
                return candidate;
            }
            if (StringUtils.isNotBlank(name)) {
                throw new InvalidParameterValueException("The proxy name is already in use; choose another name");
            }
        }
        throw new CloudRuntimeException("Unable to allocate a random proxy name; please try again");
    }

    /** Serializes name reservation and cleanup across management servers. */
    protected <T> T withDomainLock(final Long domainId, final Supplier<T> operation) {
        return withResourceLock("reverseproxy.domain." + domainId, operation);
    }

    protected <T> T withPublicIpLock(final Long publicIpId, final Supplier<T> operation) {
        return publicIpId == null ? operation.get() : withResourceLock("reverseproxy.ip." + publicIpId, operation);
    }

    private <T> T withResourceLock(final String key, final Supplier<T> operation) {
        final GlobalLock lock = GlobalLock.getInternLock(key);
        try {
            if (!lock.lock(30)) {
                throw new CloudRuntimeException("The reverse proxy resources are busy; please try again");
            }
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    protected IPAddressVO validatePublicIp(final UserVmVO vm, final NetworkVO network, final Long publicIpId) {
        if (!networkModel.areServicesSupportedInNetwork(network.getId(), Network.Service.PortForwarding)) {
            throw new InvalidParameterValueException("The selected network offering does not support port forwarding");
        }
        final IPAddressVO ip = publicIpId == null ? null : ipAddressDao.findById(publicIpId);
        if (ip == null || !ip.readyToUse() || ip.getAddress() == null || !NetUtils.isValidIp4(ip.getAddress().addr())
                || ip.isOneToOneNat() || ip.getAccountId() != vm.getAccountId() || ip.getDataCenterId() != network.getDataCenterId()) {
            throw new InvalidParameterValueException("Select an allocated public IPv4 address owned by the instance account without static NAT");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, ip);
        final boolean associated = Long.valueOf(network.getId()).equals(ip.getAssociatedWithNetworkId());
        final boolean unassignedVpcIp = ip.getAssociatedWithNetworkId() == null && network.getVpcId() != null
                && network.getVpcId().equals(ip.getVpcId());
        if (!associated && !unassignedVpcIp) {
            throw new InvalidParameterValueException("The public IP must belong to the selected network or its VPC");
        }
        return ip;
    }

    /**
     * Resolves the NPM certificate to terminate TLS with for the given domain suffix: the certificate
     * configured on the domain suffix takes precedence, otherwise the certificate covering the wildcard
     * domain is auto-discovered.
     */
    protected long resolveCertificateId(final NpmClient client, final ReverseProxyDomainVO domain) {
        if (domain.getNpmCertificateId() != null && domain.getNpmCertificateId() > 0) {
            return domain.getNpmCertificateId();
        }
        final NpmCertificate certificate = client.findCertificateForWildcardDomain(getWildcardDomain(domain));
        if (certificate == null || certificate.getId() == null) {
            throw new CloudRuntimeException(String.format(
                    "No Nginx Proxy Manager certificate covering '%s' was found. Please provision the wildcard certificate in "
                            + "Nginx Proxy Manager or set a certificate id on the reverse proxy domain suffix '%s'",
                    getWildcardDomain(domain), domain.getDomain()));
        }
        return certificate.getId();
    }

    /** Reuses only an active TCP mapping to the selected instance NIC and private port. */
    protected PortForwardingRuleVO findExistingForwardingRule(final ReverseProxyHostVO proxy) {
        for (final PortForwardingRuleVO rule : portForwardingRulesDao.listByIpAndNotRevoked(proxy.getPublicIpId())) {
            if (!"tcp".equalsIgnoreCase(rule.getProtocol()) || rule.getSourcePortStart() > proxy.getPublicPort()
                    || rule.getSourcePortEnd() < proxy.getPublicPort()) {
                continue;
            }
            final int privatePort = rule.getDestinationPortStart() + proxy.getPublicPort() - rule.getSourcePortStart();
            if (rule.getState() != FirewallRule.State.Active || rule.getVirtualMachineId() != proxy.getVmInstanceId()
                    || rule.getNetworkId() != proxy.getNetworkId() || rule.getAccountId() != proxy.getAccountId()
                    || rule.getDestinationIpAddress() == null || !proxy.getIpAddress().equals(rule.getDestinationIpAddress().addr())
                    || privatePort != proxy.getForwardPort() || privatePort > rule.getDestinationPortEnd()) {
                throw new InvalidParameterValueException("The public TCP port is already reserved by a forwarding rule that is not active "
                        + "or does not target the selected instance IP and private port");
            }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, rule);
            return rule;
        }
        return null;
    }

    @Override
    public ReverseProxyHost createInstanceProxy(final Long vmId, final String name, final String protocol, final Integer port,
            final Long domainId, final Long networkId, final Long publicIpId, final Integer publicPort) {
        validateConfiguration();
        final String validatedProtocol = validateProtocol(protocol);
        if (port == null || port < 1 || port > 65535 || (publicPort != null && (publicPort < 1 || publicPort > 65535))) {
            throw new InvalidParameterValueException("Ports must be between 1 and 65535");
        }
        if (StringUtils.isNotBlank(name) && !allowCustomName()) {
            throw new InvalidParameterValueException("Custom proxy names are disabled; omit name for a server generated name");
        }
        final UserVmVO vm = getAccessibleVm(vmId);
        final ReverseProxyDomainVO domain = resolveProxyDomain(domainId, CallContext.current().getCallingAccount(), vm);
        return withDomainLock(domain.getId(), () -> withPublicIpLock(publicIpId, () -> {
            getDomainByIdOrThrow(domain.getId());
            final UserVmVO currentVm = getAccessibleVm(vmId);
            if (currentVm.getState() == VirtualMachine.State.Destroyed || currentVm.getState() == VirtualMachine.State.Expunging) {
                throw new InvalidParameterValueException("A destroyed or expunging instance cannot be exposed");
            }
            final NetworkVO network = resolveTargetNetwork(currentVm, networkId);
            final Nic nic = networkModel.getNicInNetwork(vmId, network.getId());
            if (nic == null || StringUtils.isBlank(nic.getIPv4Address())) {
                throw new InvalidParameterValueException("The instance must have an IPv4 NIC in the selected network");
            }
            final boolean isolated = network.getGuestType() == Network.GuestType.Isolated;
            if (!isolated && (publicIpId != null || publicPort != null)) {
                throw new InvalidParameterValueException("Public IP and public port are only supported for isolated networks");
            }
            final IPAddressVO publicIp = isolated ? validatePublicIp(currentVm, network, publicIpId) : null;
            final NpmClient client = getClient();
            final long certificateId = resolveCertificateId(client, domain);
            final String proxyName = resolveProxyName(name, domain, client);
            final ReverseProxyHostVO proxy = new ReverseProxyHostVO();
            proxy.setName(proxyName);
            proxy.setFqdn(proxyName + "." + domain.getDomain());
            proxy.setVmInstanceId(vmId);
            proxy.setNetworkId(network.getId());
            proxy.setIpAddress(nic.getIPv4Address());
            proxy.setForwardScheme(validatedProtocol);
            proxy.setForwardPort(port);
            proxy.setReverseProxyDomainId(domain.getId());
            proxy.setAccountId(currentVm.getAccountId());
            proxy.setDomainId(currentVm.getDomainId());
            proxy.setPublicIpId(publicIpId);
            proxy.setPublicPort(isolated ? (publicPort == null ? port : publicPort) : null);
            proxy.setState(ReverseProxyHost.State.Creating);
            // Reserve the name before creating external resources. Failed cleanup retains this row for retry.
            final ReverseProxyHostVO persisted = reverseProxyHostDao.persist(proxy);
            if (persisted == null) {
                throw new CloudRuntimeException("Unable to reserve the proxy name");
            }
            boolean npmAttempted = false;
            try {
                if (isolated) {
                    final PortForwardingRuleVO existing = findExistingForwardingRule(persisted);
                    if (existing != null) {
                        persisted.setPortForwardingRuleManaged(false);
                        persisted.setPortForwardingRuleId(existing.getId());
                        // Ownership follows a shared managed rule until its last referencing exposure is removed.
                        persisted.setPortForwardingRuleManaged(reverseProxyHostDao.listByPortForwardingRuleId(existing.getId())
                                .stream().filter(other -> other.getId() != persisted.getId())
                                .anyMatch(ReverseProxyHostVO::isPortForwardingRuleManaged));
                        saveProxy(persisted);
                    } else {
                        final PortForwardingRule request = new PortForwardingRuleVO(null, publicIpId, proxy.getPublicPort(),
                                new Ip(nic.getIPv4Address()), port, "tcp", network.getId(), currentVm.getAccountId(),
                                currentVm.getDomainId(), vmId);
                        final PortForwardingRule rule = rulesService.createPortForwardingRule(request, vmId, null,
                                network.getVpcId() == null, true);
                        persisted.setPortForwardingRuleId(rule.getId());
                        persisted.setPortForwardingRuleManaged(true);
                        saveProxy(persisted);
                        final Account caller = CallContext.current().getCallingAccount();
                        if (network.getVpcId() == null && !firewallService.applyIngressFirewallRules(publicIpId, caller)) {
                            throw new CloudRuntimeException("Failed to apply the port forwarding firewall rule");
                        }
                        if (!rulesService.applyPortForwardingRules(publicIpId, caller)) {
                            throw new CloudRuntimeException("Failed to apply the port forwarding rule");
                        }
                    }
                }
                final NpmProxyHost request = new NpmProxyHost();
                request.setDomainNames(Arrays.asList(persisted.getFqdn()));
                request.setForwardScheme(validatedProtocol);
                request.setForwardHost(isolated ? publicIp.getAddress().addr() : nic.getIPv4Address());
                request.setForwardPort(isolated ? persisted.getPublicPort() : port);
                request.setCertificateId((int) certificateId);
                request.setSslForced(ReverseProxyForceHttps.value());
                request.setEnabled(true);
                request.setMeta(Map.of(PROXY_UUID_META_KEY, persisted.getUuid()));
                npmAttempted = true;
                final NpmProxyHost created = client.createProxyHost(request);
                if (created == null || created.getId() == null) {
                    throw new CloudRuntimeException("Nginx Proxy Manager did not return the created proxy host");
                }
                persisted.setNpmProxyHostId(created.getId());
                persisted.setState(ReverseProxyHost.State.Active);
                saveProxy(persisted);
                return persisted;
            } catch (final Exception e) {
                persisted.setState(ReverseProxyHost.State.Error);
                try {
                    // A timed out NPM create may have succeeded; resolve it before rolling back.
                    removeProxyResources(persisted, client, npmAttempted);
                } catch (final Exception cleanupException) {
                    try {
                        saveProxy(persisted);
                    } catch (final Exception saveException) {
                        cleanupException.addSuppressed(saveException);
                    }
                    logger.error("Failed to roll back instance proxy " + persisted.getUuid() + "; retry deleting this proxy", cleanupException);
                    throw new CloudRuntimeException("Exposure failed and cleanup is incomplete. Delete instance proxy "
                            + persisted.getUuid() + " to retry cleanup", e);
                }
                throw new CloudRuntimeException("Failed to expose the instance: " + e.getMessage(), e);
            }
        }));
    }

    protected void saveProxy(final ReverseProxyHostVO proxy) {
        if (!reverseProxyHostDao.update(proxy.getId(), proxy)) {
            throw new CloudRuntimeException("Unable to save instance proxy " + proxy.getUuid());
        }
    }

    /** Deletes only resources created by this integration; safe to retry after partial cleanup. */
    protected void removeProxyResources(final ReverseProxyHostVO proxy, final NpmClient client, final boolean resolveMissingNpmId) {
        withPublicIpLock(proxy.getPublicIpId(), () -> {
            removeProxyResourcesLocked(proxy, client, resolveMissingNpmId);
            return null;
        });
    }

    private void removeProxyResourcesLocked(final ReverseProxyHostVO proxy, final NpmClient client, final boolean resolveMissingNpmId) {
        if (proxy.getNpmProxyHostId() > 0) {
            if (!client.deleteProxyHost(proxy.getNpmProxyHostId())) {
                throw new CloudRuntimeException("Failed to remove the Nginx Proxy Manager host");
            }
        } else if (resolveMissingNpmId) {
            final NpmProxyHost host = client.findProxyHostByDomain(proxy.getFqdn());
            // A concurrent external NPM user may have claimed this name after our availability check.
            // Recover only the host carrying this reservation's UUID, never an unrelated host.
            if (host != null && host.getId() != null && host.getMeta() != null
                    && proxy.getUuid().equals(host.getMeta().get(PROXY_UUID_META_KEY))
                    && !client.deleteProxyHost(host.getId())) {
                throw new CloudRuntimeException("Failed to remove the Nginx Proxy Manager host");
            }
        }
        final Long ruleId = proxy.getPortForwardingRuleId();
        if (ruleId != null && proxy.isPortForwardingRuleManaged()
                && reverseProxyHostDao.listByPortForwardingRuleId(ruleId).stream().noneMatch(other -> other.getId() != proxy.getId())) {
            if (!firewallService.revokeRelatedFirewallRule(ruleId, true)) {
                throw new CloudRuntimeException("Failed to remove the port forwarding firewall rule");
            }
            if (portForwardingRulesDao.findById(ruleId) != null && !rulesService.revokePortForwardingRule(ruleId, true)) {
                throw new CloudRuntimeException("Failed to remove the port forwarding rule");
            }
        }
        if (!reverseProxyHostDao.remove(proxy.getId())) {
            throw new CloudRuntimeException("Unable to remove the instance proxy mapping");
        }
    }

    protected void cleanupProxy(final ReverseProxyHostVO proxy) {
        withDomainLock(proxy.getReverseProxyDomainId(), () -> {
            final ReverseProxyHostVO current = reverseProxyHostDao.findById(proxy.getId());
            if (current != null) {
                try {
                    removeProxyResources(current, getClient(), true);
                } catch (final Exception e) {
                    current.setState(ReverseProxyHost.State.Error);
                    saveProxy(current);
                    throw e;
                }
            }
            return null;
        });
    }

    @Override
    public void deleteInstanceProxy(final Long proxyId) {
        validateConfiguration();
        final ReverseProxyHostVO proxy = proxyId == null ? null : reverseProxyHostDao.findById(proxyId);
        if (proxy == null) {
            throw new InvalidParameterValueException("Unable to find instance proxy with id " + proxyId);
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, proxy);
        cleanupProxy(proxy);
    }

    @Override
    public void cleanupProxiesForVm(final Long vmId) {
        if (vmId == null) {
            return;
        }
        for (final ReverseProxyHostVO proxy : reverseProxyHostDao.listByVmId(vmId)) {
            try {
                cleanupProxy(proxy);
            } catch (final Exception e) {
                logger.error("Failed to clean up instance proxy " + proxy.getUuid() + "; retry deleting this proxy", e);
            }
        }
    }

    @Override
    public CheckInstanceProxyNameResponse checkInstanceProxyName(final String name, final Long domainId, final CheckInstanceProxyNameCmd cmd) {
        validateConfiguration();

        if (!allowCustomName()) {
            throw new InvalidParameterValueException("Custom proxy names are disabled; omit name when creating an exposure");
        }
        final UserVmVO vm = cmd.getVirtualMachineId() == null ? null : getAccessibleVm(cmd.getVirtualMachineId());
        final CheckInstanceProxyNameResponse response = new CheckInstanceProxyNameResponse();
        String message = null;
        String validatedName = null;
        try {
            validatedName = validateProxyName(name);
        } catch (final InvalidParameterValueException e) {
            message = e.getMessage();
        }
        final String fqdn;
        if (validatedName != null) {
            final ReverseProxyDomainVO domain;
            try {
                final Account caller = CallContext.current().getCallingAccount();
                domain = resolveProxyDomain(domainId, caller, vm);
            } catch (final CloudRuntimeException e) {
                response.setName(validatedName);
                response.setMessage(e.getMessage());
                response.setAvailable(false);
                return response;
            }
            fqdn = String.format("%s.%s", validatedName, domain.getDomain());
            response.setName(validatedName);
            response.setFqdn(fqdn);
            if (reverseProxyHostDao.findByFqdn(fqdn) != null) {
                message = String.format("The name '%s' is already in use, please choose another name", validatedName);
            } else {
                try {
                    final NpmClient client = getClient();
                    if (client.findProxyHostByDomain(fqdn) != null) {
                        message = String.format("The domain '%s' is already in use, please choose another name", fqdn);
                    }
                } catch (final Exception e) {
                    logger.warn(String.format("Failed to check the availability of %s on the Nginx Proxy Manager", fqdn), e);
                    message = "Unable to check the name availability on the Nginx Proxy Manager, please try again later";
                }
            }
        } else {
            response.setName(name);
        }
        response.setMessage(message);
        response.setAvailable(message == null);
        return response;
    }

    ///////////////////////////////////////////////////////////
    /////////////// Domain suffix management //////////////////
    ///////////////////////////////////////////////////////////

    protected String validateDomainName(final String domain) {
        if (StringUtils.isBlank(domain)) {
            throw new InvalidParameterValueException("A domain suffix is required");
        }
        final String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (!DOMAIN_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' is not valid, it must consist of "
                    + "DNS labels of lowercase letters, digits and hyphens separated by dots, for example 'cloud.company.com'", domain));
        }
        return trimmed;
    }

    @Override
    public ReverseProxyDomainVO addReverseProxyDomain(final String domain, final String description, final Boolean isPublic,
            final Long npmCertificateId, final List<Long> accountIds, final List<Long> networkIds) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final String validated = validateDomainName(domain);
        if (reverseProxyDomainDao.findByName(validated) != null) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' already exists", validated));
        }
        final ReverseProxyDomainVO vo = new ReverseProxyDomainVO(validated);
        vo.setDescription(StringUtils.trimToNull(description));
        vo.setPublic(isPublic != null && isPublic);
        if (npmCertificateId != null && npmCertificateId < 0) {
            throw new InvalidParameterValueException("The Nginx Proxy Manager certificate id must not be negative");
        }
        vo.setNpmCertificateId(npmCertificateId);
        final ReverseProxyDomainVO persisted = reverseProxyDomainDao.persist(vo);
        updateGrants(persisted, accountIds, networkIds);
        logger.info(String.format("Added reverse proxy domain suffix %s (id=%s, public=%s)", persisted.getDomain(),
                persisted.getUuid(), persisted.isPublic()));
        return persisted;
    }

    @Override
    public ReverseProxyDomainVO updateReverseProxyDomain(final Long id, final String description, final Boolean isPublic,
            final Long npmCertificateId, final List<Long> accountIds, final List<Long> networkIds) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final ReverseProxyDomainVO domain = getDomainByIdOrThrow(id);
        if (description != null) {
            domain.setDescription(StringUtils.trimToNull(description));
        }
        if (isPublic != null) {
            domain.setPublic(isPublic);
        }
        if (npmCertificateId != null) {
            if (npmCertificateId < 0) {
                throw new InvalidParameterValueException("The Nginx Proxy Manager certificate id must not be negative");
            }
            domain.setNpmCertificateId(npmCertificateId > 0 ? npmCertificateId : null);
        }
        reverseProxyDomainDao.update(id, domain);
        updateGrants(domain, accountIds, networkIds);
        logger.info(String.format("Updated reverse proxy domain suffix %s (id=%s, public=%s)", domain.getDomain(),
                domain.getUuid(), domain.isPublic()));
        return domain;
    }

    @Override
    public void deleteReverseProxyDomain(final Long id) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        withDomainLock(id, () -> {
            final ReverseProxyDomainVO domain = getDomainByIdOrThrow(id);
            for (final ReverseProxyHostVO proxy : reverseProxyHostDao.listByDomainId(id)) {
                try {
                    removeProxyResources(proxy, getClient(), true);
                } catch (final Exception e) {
                    proxy.setState(ReverseProxyHost.State.Error);
                    saveProxy(proxy);
                    throw new CloudRuntimeException("Unable to clean up proxy " + proxy.getUuid() + "; domain retained for retry", e);
                }
            }
            for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(id)) {
                reverseProxyDomainMapDao.remove(map.getId());
            }
            reverseProxyDomainDao.remove(domain.getId());
            return null;
        });
    }

    @Override
    public ListResponse<ReverseProxyDomainResponse> listReverseProxyDomains(final ListReverseProxyDomainsCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Account caller = CallContext.current().getCallingAccount();
        final UserVmVO vm = cmd.getVirtualMachineId() != null ? getAccessibleVm(cmd.getVirtualMachineId()) : null;
        final List<ReverseProxyDomainVO> domains = listAllowedDomains(caller, vm);
        final Long domainId = cmd.getId();
        final String keyword = cmd.getKeyword();
        final boolean showDetails = isAdmin(caller);
        final List<ReverseProxyDomainResponse> responses = new ArrayList<>();
        int count = 0;
        for (final ReverseProxyDomainVO domain : domains) {
            if (domainId != null && domain.getId() != domainId) {
                continue;
            }
            if (StringUtils.isNotBlank(keyword) && !domain.getDomain().contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            count++;
            final ReverseProxyDomainResponse domainResponse = createReverseProxyDomainResponse(domain, showDetails);
            if (vm != null) {
                try {
                    domainResponse.setDefaultNetworkId(resolveTargetNetwork(vm, null).getUuid());
                } catch (final CloudRuntimeException e) {
                    // Other NICs may still be selected explicitly, even if the shared network setting is stale.
                    logger.debug("No default reverse proxy network for instance " + vm.getUuid(), e);
                }
            }
            responses.add(domainResponse);
        }
        final ListResponse<ReverseProxyDomainResponse> response = new ListResponse<>();
        response.setResponses(responses, count);
        return response;
    }

    protected ReverseProxyDomainVO getDomainByIdOrThrow(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("A reverse proxy domain suffix id is required");
        }
        final ReverseProxyDomainVO domain = reverseProxyDomainDao.findById(id);
        if (domain == null) {
            throw new InvalidParameterValueException(String.format("Unable to find reverse proxy domain suffix with id %s", id));
        }
        return domain;
    }

    /**
     * Replaces the grants of the given domain suffix with the given accounts and shared or isolated networks.
     * Null lists leave the grants unchanged.
     */
    protected void updateGrants(final ReverseProxyDomainVO domain, final List<Long> accountIds, final List<Long> networkIds) {
        if (accountIds == null && networkIds == null) {
            return;
        }
        for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(domain.getId())) {
            reverseProxyDomainMapDao.remove(map.getId());
        }
        if (accountIds != null) {
            for (final Long accountId : new ArrayList<>(new java.util.LinkedHashSet<>(accountIds))) {
                if (accountId == null) {
                    continue;
                }
                final Account account = accountDao.findById(accountId);
                if (account == null || account.getRemoved() != null) {
                    throw new InvalidParameterValueException(String.format("Unable to find account with id %s", accountId));
                }
                reverseProxyDomainMapDao.persist(new ReverseProxyDomainMapVO(domain.getId(), accountId, null));
            }
        }
        if (networkIds != null) {
            for (final Long networkId : new ArrayList<>(new java.util.LinkedHashSet<>(networkIds))) {
                if (networkId == null) {
                    continue;
                }
                final NetworkVO network = networkDao.findById(networkId);
                if (network == null) {
                    throw new InvalidParameterValueException(String.format("Unable to find network with id %s", networkId));
                }
                if (network.getGuestType() != Network.GuestType.Shared && network.getGuestType() != Network.GuestType.Isolated) {
                    throw new InvalidParameterValueException(String.format("The network '%s' is not a shared or isolated network; only "
                            + "shared or isolated networks can be granted access to a reverse proxy domain suffix", network.getName()));
                }
                reverseProxyDomainMapDao.persist(new ReverseProxyDomainMapVO(domain.getId(), null, networkId));
            }
        }
    }

    @Override
    public ReverseProxyDomainResponse createReverseProxyDomainResponse(final ReverseProxyDomainVO domain, final boolean showDetails) {
        final ReverseProxyDomainResponse response = new ReverseProxyDomainResponse();
        response.setObjectName("reverseproxydomain");
        response.setId(domain.getUuid());
        response.setDomain(domain.getDomain());
        response.setAllowCustomName(allowCustomName());
        response.setDescription(domain.getDescription());
        response.setPublic(domain.isPublic());
        response.setNpmCertificateId(domain.getNpmCertificateId());
        response.setCreated(domain.getCreated());
        if (showDetails) {
            final List<String> accounts = new ArrayList<>();
            final List<String> accountIds = new ArrayList<>();
            final List<String> networks = new ArrayList<>();
            final List<String> networkIds = new ArrayList<>();
            for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(domain.getId())) {
                if (map.getAccountId() != null) {
                    final Account account = accountDao.findById(map.getAccountId());
                    if (account != null) {
                        accounts.add(account.getAccountName());
                        accountIds.add(account.getUuid());
                    }
                } else if (map.getNetworkId() != null) {
                    final NetworkVO network = networkDao.findById(map.getNetworkId());
                    if (network != null) {
                        networks.add(network.getName());
                        networkIds.add(network.getUuid());
                    }
                }
            }
            response.setAccounts(accounts);
            response.setAccountIds(accountIds);
            response.setNetworks(networks);
            response.setNetworkIds(networkIds);
            response.setProxyCount(reverseProxyHostDao.countByDomainId(domain.getId()));
        }
        return response;
    }

    @Override
    public ListResponse<InstanceProxyResponse> listInstanceProxies(final ListInstanceProxiesCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Account caller = CallContext.current().getCallingAccount();
        final Long proxyId = cmd.getId();
        final Long vmId = cmd.getVirtualMachineId();
        final String keyword = cmd.getKeyword();

        final List<Long> permittedAccounts = new ArrayList<>();
        final Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, proxyId, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts,
                domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        final Filter searchFilter = new Filter(ReverseProxyHostVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<ReverseProxyHostVO> sb = reverseProxyHostDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vmInstanceId", sb.entity().getVmInstanceId(), SearchCriteria.Op.EQ);
        sb.and("fqdn", sb.entity().getFqdn(), SearchCriteria.Op.LIKE);

        final SearchCriteria<ReverseProxyHostVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (proxyId != null) {
            sc.setParameters("id", proxyId);
        }
        if (vmId != null) {
            sc.setParameters("vmInstanceId", vmId);
        }
        if (keyword != null) {
            sc.setParameters("fqdn", "%" + keyword + "%");
        }

        final Pair<List<ReverseProxyHostVO>, Integer> result = reverseProxyHostDao.searchAndCount(sc, searchFilter);
        final List<InstanceProxyResponse> responses = new ArrayList<>();
        for (final ReverseProxyHostVO proxy : result.first()) {
            responses.add(createInstanceProxyResponse(proxy));
        }
        final ListResponse<InstanceProxyResponse> response = new ListResponse<>();
        response.setResponses(responses, result.second());
        return response;
    }

    @Override
    public ListResponse<InstanceProxyResponse> listReverseProxyHosts(final ListReverseProxyHostsCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Long domainId = cmd.getDomainId();
        final Long vmId = cmd.getVirtualMachineId();
        final String keyword = cmd.getKeyword();

        final Filter searchFilter = new Filter(ReverseProxyHostVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<ReverseProxyHostVO> sb = reverseProxyHostDao.createSearchBuilder();
        sb.and("vmInstanceId", sb.entity().getVmInstanceId(), SearchCriteria.Op.EQ);
        sb.and("fqdn", sb.entity().getFqdn(), SearchCriteria.Op.LIKE);
        sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.IN);
        sb.and("reverseProxyDomainId", sb.entity().getReverseProxyDomainId(), SearchCriteria.Op.EQ);

        final SearchCriteria<ReverseProxyHostVO> sc = sb.create();
        if (vmId != null) {
            sc.setParameters("vmInstanceId", vmId);
        }
        if (StringUtils.isNotBlank(keyword)) {
            sc.setParameters("fqdn", "%" + keyword.trim() + "%");
        }
        final Long reverseProxyDomainId = cmd.getReverseProxyDomainId();
        if (reverseProxyDomainId != null) {
            sc.setParameters("reverseProxyDomainId", reverseProxyDomainId);
        }
        final List<Long> domainIds = resolveDomainIds(domainId, cmd.isRecursive());
        if (!domainIds.isEmpty()) {
            sc.setParameters("domainId", domainIds.toArray(new Long[0]));
        }

        final Pair<List<ReverseProxyHostVO>, Integer> result = reverseProxyHostDao.searchAndCount(sc, searchFilter);
        final List<InstanceProxyResponse> responses = new ArrayList<>();
        for (final ReverseProxyHostVO proxy : result.first()) {
            responses.add(createInstanceProxyResponse(proxy));
        }
        final ListResponse<InstanceProxyResponse> response = new ListResponse<>();
        response.setResponses(responses, result.second());
        return response;
    }

    /**
     * Resolves the domain ids to filter on: when no domain id is given all domains are returned,
     * when recursive is set the sub-domains of the given domain are included as well
     */
    protected List<Long> resolveDomainIds(final Long domainId, final Boolean recursive) {
        if (domainId == null) {
            return new ArrayList<>();
        }
        if (recursive != null && recursive) {
            return domainDao.getDomainAndChildrenIds(domainId);
        }
        return List.of(domainId);
    }

    @Override
    public InstanceProxyResponse createInstanceProxyResponse(final ReverseProxyHost proxy) {
        final InstanceProxyResponse response = new InstanceProxyResponse();
        response.setObjectName("instanceproxy");
        response.setId(proxy.getUuid());
        response.setName(proxy.getName());
        response.setFqdn(proxy.getFqdn());
        // The proxy hosts are always created with a certificate, therefore https is always available
        response.setUrl(String.format("https://%s", proxy.getFqdn()));
        response.setIpAddress(proxy.getIpAddress());
        response.setProtocol(proxy.getForwardScheme());
        response.setPort(proxy.getForwardPort());
        response.setPublicPort(proxy.getPublicPort());
        if (proxy.getPortForwardingRuleId() != null) {
            response.setPortForwardingRuleManaged(proxy.isPortForwardingRuleManaged());
        }
        if (proxy.getPublicIpId() != null) {
            final IPAddressVO publicIp = ipAddressDao.findById(proxy.getPublicIpId());
            if (publicIp != null) {
                response.setPublicIpAddress(publicIp.getAddress().addr());
            }
        }
        if (proxy.getPortForwardingRuleId() != null) {
            final PortForwardingRuleVO rule = portForwardingRulesDao.findById(proxy.getPortForwardingRuleId());
            if (rule != null) {
                response.setPortForwardingRuleId(rule.getUuid());
            }
        }
        response.setState(proxy.getState() != null ? proxy.getState().name() : ReverseProxyHost.State.Active.name());
        response.setCreated(proxy.getCreated());

        final UserVmVO vm = userVmDao.findById(proxy.getVmInstanceId());
        if (vm != null) {
            response.setVirtualMachineId(vm.getUuid());
            response.setVirtualMachineName(vm.getDisplayName() != null ? vm.getDisplayName() : vm.getHostName());
        }
        final Account account = accountDao.findById(proxy.getAccountId());
        if (account != null) {
            response.setAccount(account.getAccountName());
        }
        final com.cloud.domain.DomainVO domain = domainDao.findById(proxy.getDomainId());
        if (domain != null) {
            response.setDomainId(String.valueOf(proxy.getDomainId()));
            response.setDomain(domain.getName());
        }
        return response;
    }

    ///////////////////////////////////////////////////////////
    ////////////////// Plugin configuration ///////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        if (!isEnabled()) {
            return cmdList;
        }
        cmdList.add(AddInstanceProxyCmd.class);
        cmdList.add(ListInstanceProxiesCmd.class);
        cmdList.add(DeleteInstanceProxyCmd.class);
        cmdList.add(CheckInstanceProxyNameCmd.class);
        cmdList.add(ListReverseProxyHostsCmd.class);
        cmdList.add(ListReverseProxyDomainsCmd.class);
        cmdList.add(AddReverseProxyDomainCmd.class);
        cmdList.add(UpdateReverseProxyDomainCmd.class);
        cmdList.add(DeleteReverseProxyDomainCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return ReverseProxyService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                ReverseProxyEnabled,
                ReverseProxyDomain,
                ReverseProxyNetworkId,
                ReverseProxyNpmUrl,
                ReverseProxyNpmUser,
                ReverseProxyNpmPassword,
                ReverseProxyNpmValidateSsl,
                ReverseProxyNpmRequestTimeout,
                ReverseProxyNpmCertificateId,
                ReverseProxyForceHttps,
                ReverseProxyReservedNames,
                ReverseProxyAllowCustomName
        };
    }

    ///////////////////////////////////////////////////////////
    ///////////////// VM expunge cleanup hook //////////////////
    ///////////////////////////////////////////////////////////

    protected class VmExpungeStateListener implements StateListener<VirtualMachine.State, VirtualMachine.Event, VirtualMachine> {
        @Override
        public boolean preStateTransitionEvent(final VirtualMachine.State oldState, final VirtualMachine.Event event,
                final VirtualMachine.State newState, final VirtualMachine vm, final boolean status, final Object opaque) {
            return true;
        }

        @Override
        public boolean postStateTransitionEvent(final StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition,
                final VirtualMachine vm, final boolean status, final Object opaque) {
            if (transition.getToState() == VirtualMachine.State.Expunging && transition.getEvent() == VirtualMachine.Event.ExpungeOperation) {
                try {
                    cleanupProxiesForVm(vm.getId());
                } catch (final Exception e) {
                    logger.warn(String.format("Failed to clean up reverse proxy hosts for the expunged instance id=%s", vm.getId()), e);
                }
            }
            return true;
        }
    }
}
