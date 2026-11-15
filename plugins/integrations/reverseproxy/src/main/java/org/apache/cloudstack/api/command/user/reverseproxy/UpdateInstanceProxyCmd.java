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
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import com.cloud.event.EventTypes;
import org.apache.cloudstack.reverseproxy.ReverseProxyHostVO;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyHostDao;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = "updateInstanceProxy",
        description = "Updates the domain prefix and backend protocol or private port of an existing exposure",
        responseObject = InstanceProxyResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateInstanceProxyCmd extends BaseAsyncCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    @Inject
    private ReverseProxyHostDao reverseProxyHostDao;

    private ReverseProxyHostVO getProxy() {
        return id == null ? null : reverseProxyHostDao.findById(id);
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_NET_RULE_MODIFY;
    }

    @Override
    public String getEventDescription() {
        return "Updating instance reverse proxy " + id;
    }

    @Override
    public String getSyncObjType() {
        return getSyncObjId() == null ? null : ipAddressSyncObject;
    }

    @Override
    public Long getSyncObjId() {
        final ReverseProxyHostVO proxy = getProxy();
        return proxy == null ? null : proxy.getPublicIpId();
    }

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, required = true, entityType = InstanceProxyResponse.class,
            description = "The ID of the instance proxy to update")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING,
            description = "New domain prefix; omit to keep the current name. Custom names must be allowed by the administrator")
    private String name;

    @Parameter(name = ApiConstants.PROTOCOL, type = CommandType.STRING, description = "Backend protocol: http or https; omit to keep the current protocol")
    private String protocol;

    @Parameter(name = ApiConstants.PORT, type = CommandType.INTEGER, description = "Backend private port; omit to keep the current port")
    private Integer port;

    @Parameter(name = "generatename", type = CommandType.BOOLEAN, description = "Generate a new random domain prefix; cannot be combined with name")
    private Boolean generateName;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public long getEntityOwnerId() {
        final ReverseProxyHostVO proxy = getProxy();
        return proxy == null ? Account.ACCOUNT_ID_SYSTEM : proxy.getAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final InstanceProxyResponse response = reverseProxyService.createInstanceProxyResponse(
                    reverseProxyService.updateInstanceProxy(id, name, protocol, port, generateName));
            response.setResponseName(getCommandName());
            response.setObjectName("instanceproxy");
            setResponseObject(response);
        } catch (final CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}
