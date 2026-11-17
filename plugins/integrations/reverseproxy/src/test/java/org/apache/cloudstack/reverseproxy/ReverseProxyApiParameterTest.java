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

import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.reverseproxy.ListReverseProxyHostsCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.AddInstanceProxyCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListInstanceProxiesCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import com.cloud.network.rules.FirewallRule;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ReverseProxyApiParameterTest {
    @Test
    public void everyLoadBalancerUuidParameterCanBeResolvedByTheApiDispatcher() throws Exception {
        for (Class<?> command : new Class<?>[] { AddInstanceProxyCmd.class, CheckInstanceProxyNameCmd.class,
                ListInstanceProxiesCmd.class, ListReverseProxyDomainsCmd.class, ListReverseProxyHostsCmd.class }) {
            Parameter parameter = command.getDeclaredField("loadBalancerRuleId").getAnnotation(Parameter.class);
            assertEquals(command.getName(), BaseCmd.CommandType.UUID, parameter.type());
            EntityReference reference = parameter.entityType()[0].getAnnotation(EntityReference.class);
            assertNotNull(command.getName() + " must provide an EntityReference for UUID resolution", reference);
            assertArrayEquals(command.getName(), new Class<?>[] { FirewallRule.class }, reference.value());
        }
    }
}
