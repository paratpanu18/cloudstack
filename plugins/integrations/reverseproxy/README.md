# Reverse proxy exposure

The integration publishes HTTP or HTTPS applications through Nginx Proxy Manager
(NPM) under domain suffixes configured by the administrator. NPM terminates the
public HTTPS connection and forwards requests using the selected application
protocol.

## Network selection

- **Shared network:** NPM connects directly to the selected NIC's private IPv4
  address and application port. When `reverseproxy.network.id` is set, shared
  network exposure must use that network.
- **Isolated network:** the Expose form creates a TCP port forwarding rule from
  an allocated public IP and public port to the selected NIC and application
  port. NPM connects to the public IP and port. The network offering must support
  port forwarding. The public IP must belong to the instance account and the
  selected network, or be an unassigned IP in the network's VPC. Static NAT IPs
  cannot be used. NPM must be able to reach this public endpoint.

Select the network in the Expose form. For isolated networks, select an existing
allocated public IP and a public port; IP allocation remains a separate action.
The public port may differ from the application's private port. For example,
public TCP port `18080` can forward to application port `8080` while users access
`https://myapp.apps.example.com` through NPM.

For ordinary isolated networks the integration creates and applies the related
ingress firewall rule. VPC tiers use CloudStack's native VPC port forwarding
behavior instead of a standalone ingress firewall rule. Forwarded traffic is
subject to the network provider's normal port forwarding and access policies.

Administrators can grant a domain suffix to shared or isolated networks, as well
as accounts, or make it public. Existing shared network exposures keep their
current targets.

## Custom names

Configure **`reverseproxy.allow.custom.name`** in Global Settings:

- `true` (default): users may supply a prefix such as `myapp`, or leave it blank
  for a server generated random name.
- `false`: users must omit the prefix. The server generates a random DNS label
  such as `vm-<UUID>`. Supplying a nonempty custom prefix is rejected by the API.

The policy applies to new exposures. Existing names continue to work. Users
always select an administrator configured domain suffix; arbitrary user-owned
full domains are not accepted. Name validation, reserved names, and availability
checks apply to generated names too. Names are reserved under a database-backed
lock shared by management servers.

## API usage

`addInstanceProxy` and `deleteInstanceProxy` are asynchronous. Poll their `jobid`
with `queryAsyncJobResult` before reporting completion. Domain deletion is also
asynchronous because it cleans up associated exposures.

An isolated network request has these parameters (IDs are UUIDs):

```text
command=addInstanceProxy
virtualmachineid=<instance-id>
domainid=<reverse-proxy-domain-id>
networkid=<isolated-network-id>
ipaddressid=<allocated-public-ip-id>
publicport=18080
protocol=http
port=8080
name=myapp
```

Omit `name` for a generated name, including when custom names are disabled. If
`publicport` is omitted it defaults to `port`. Shared network requests omit
`ipaddressid` and `publicport`. When `networkid` is omitted, the configured shared
network is preferred if the VM has a NIC there; otherwise its eligible default
NIC is used.

`listReverseProxyDomains` returns `allowcustomname` and, when an instance is
supplied, `defaultnetworkid`. Pass `virtualmachineid` to both this API and
`checkInstanceProxyName` so network-based domain grants can be checked against an
instance the caller is allowed to operate. Name availability checks are rejected
when custom names are disabled.

Proxy responses retain the private `ipaddress` and application `port`, and add
`publicipaddress`, `publicport`, and `portforwardingruleid` for isolated networks.

## Cleanup and upgrades

Deleting an exposure, deleting its domain suffix, or expunging its VM removes the
NPM host and the forwarding/firewall rules created for that exposure. Existing
user-created rules and allocated public IPs are retained. A failed creation
attempt rolls back the resources it created. An incomplete rollback or deletion
retains an `Error` mapping; retry `deleteInstanceProxy` after resolving the
reported network or NPM failure. A domain whose proxies cannot be cleaned up is
retained so deletion can be retried. Newly created NPM hosts carry the mapping's
UUID in their `meta` object. Recovery after an interrupted create checks that
UUID before deleting a host discovered by name. NPM's [proxy host API schema](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/v2.12.6/backend/schema/components/proxy-host-object.json)
supports this metadata object.

The 4.22.1.0 to 4.22.2.0 schema upgrade adds nullable public IP, public port, and
forwarding rule references. Existing shared network mappings need no backfill.
Run the normal database upgrade before starting the updated management server.
An installation already recorded as 4.22.2.0 will not rerun this upgrade; apply
the three new idempotent column additions to its database before using this code.
The deprecated `reverseproxy.domain` setting is no longer required when domain
suffixes have been configured using the domain management APIs.

## Validation

Run backend tests and their reactor dependencies:

```sh
mvn -pl plugins/integrations/reverseproxy -am test \
  -Dtest='ReverseProxy*Test,NpmClientTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Ddownload.plugin.skip=true
```

Run the Expose form tests from `ui`:

```sh
npm run test:unit -- --runInBand --coverage=false tests/unit/views/compute/AddInstanceProxy.spec.js
```

Before deployment, use a test CloudStack environment and NPM to verify shared
and isolated HTTP/HTTPS traffic, VPC forwarding if used, deletion and expunge
cleanup, and upgrade from an existing database. Unit tests mock network devices
and do not establish connectivity to an actual virtual router or NPM server.
