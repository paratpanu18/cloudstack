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

package org.apache.cloudstack.api.command.user.reverseproxy;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.InstanceProxyBackendHealthResponse;
import org.apache.cloudstack.api.response.IPAddressResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@APICommand(name = "checkInstanceProxyBackend",
        description = "Checks whether the selected instance backend returns HTTP 200 at / from its virtual router, "
                + "without creating public forwarding or a domain exposure.",
        responseObject = InstanceProxyBackendHealthResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.3.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CheckInstanceProxyBackendCmd extends BaseAsyncCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    @Inject
    protected UserVmDao userVmDao;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @ACL(accessType = SecurityChecker.AccessType.OperateEntry)
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, required = true, entityType = UserVmResponse.class,
            description = "The ID of the instance whose backend to check")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.PROTOCOL, type = CommandType.STRING, required = true,
            description = "The protocol used to forward requests to the instance: http or https")
    private String protocol;

    @Parameter(name = ApiConstants.PORT, type = CommandType.INTEGER, required = true,
            description = "The private application port on the instance")
    private Integer port;

    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class,
            description = "The instance network whose running virtual router will check the private backend")
    private Long networkId;

    @ACL(accessType = SecurityChecker.AccessType.OperateEntry)
    @Parameter(name = ApiConstants.IP_ADDRESS_ID, type = CommandType.UUID, entityType = IPAddressResponse.class,
            description = "Optional legacy public IP parameter; validated if supplied but not used for the private backend check")
    private Long publicIpId;

    @Parameter(name = ApiConstants.PUBLIC_PORT, type = CommandType.INTEGER,
            description = "Optional legacy public port parameter; validated if supplied but not used by the router check")
    private Integer publicPort;

    @Override
    public String getEventType() {
        return EventTypes.EVENT_NET_RULE_ADD;
    }

    @Override
    public String getEventDescription() {
        return "Checking reverse proxy backend for instance " + virtualMachineId;
    }

    @Override
    public String getSyncObjType() {
        return publicIpId == null ? null : ipAddressSyncObject;
    }

    @Override
    public Long getSyncObjId() {
        return publicIpId;
    }

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getProtocol() {
        return protocol;
    }

    public Integer getPort() {
        return port;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public long getEntityOwnerId() {
        final UserVmVO vm = virtualMachineId != null ? userVmDao.findById(virtualMachineId) : null;
        // bad id given, parent this command to SYSTEM so ERROR events are tracked
        return vm != null ? vm.getAccountId() : Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        if (virtualMachineId == null) {
            throw new InvalidParameterValueException("Instance ID is required");
        }
        final InstanceProxyBackendHealthResponse response = reverseProxyService.checkInstanceProxyBackend(
                virtualMachineId, protocol, port, networkId, publicIpId, publicPort);
        response.setResponseName(getCommandName());
        response.setObjectName("instanceproxybackendhealth");
        setResponseObject(response);
    }
}
