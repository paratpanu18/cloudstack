# Reverse proxy exposure

The integration publishes HTTP or HTTPS applications through Nginx Proxy Manager
(NPM) under domain suffixes configured by the administrator. NPM terminates the
public HTTPS connection and forwards requests using the selected application
protocol.

## Network selection

- **Shared network:** NPM connects directly to the selected NIC's private IPv4
  address and application port. When `reverseproxy.network.id` is set, shared
  network exposure must use that network.
- **Isolated network:** the Expose form reuses a matching active TCP forwarding
  rule, or creates one from the allocated public IP and public port to the
  selected NIC and application port if no matching rule exists. NPM connects to the public IP and port. The network offering must support
  port forwarding. The public IP must belong to the instance account and the
  selected network, or be an unassigned IP in the network's VPC. Static NAT IPs
  cannot be used. NPM must be able to reach this public endpoint.

Select the network in the Expose form. For isolated networks, select an existing
allocated public IP and a public port; IP allocation remains a separate action.
The public port may differ from the application's private port. For example,
public TCP port `18080` can forward to application port `8080` while users access
`https://myapp.apps.example.com` through NPM.

A reusable rule must be active TCP forwarding on the selected public IP and
public port, targeting the same instance, NIC IPv4 address, and private port.
Rules covering a port range are reusable when the selected public port maps to
the requested private port within that range. An occupied TCP port targeting a
different destination, or a rule still being applied, produces a conflict;
existing rules are never overwritten. Reused rules and their firewall settings
are left unchanged, so their existing access policies must allow NPM to connect.

When a new rule is needed on an ordinary isolated network, the integration
creates and applies the related ingress firewall rule. VPC tiers use CloudStack's native VPC port forwarding
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
`publicipaddress`, `publicport`, `portforwardingruleid`, and
`portforwardingrulemanaged` for isolated networks. The last field is false for
reused manual rules and true for rules created by the integration.

## Editing an exposure

Use **Edit exposed domain** on an active exposure to change its domain prefix,
backend HTTP/HTTPS protocol, or private port. The suffix, instance, and network
remain fixed. The asynchronous `updateInstanceProxy` API accepts `id`, optional
`name`, `protocol`, `port`, and `generatename`. Omitted values retain the existing
settings. `generatename=true` requests a new server-generated prefix and cannot
be combined with `name`. Custom prefixes follow the administrator's custom-name
policy; backend edits remain available when custom names are disabled.

The same CloudStack exposure UUID and NPM host ID are retained. NPM updates only
the domain names and changed backend connection settings, preserving certificates
and other host options. Names are validated and checked for conflicts under the
existing domain lock, and ownership is checked on both the exposure and instance.

For an isolated network port change, an exclusive integration-managed forwarding
rule is updated through the networking service. Manual or shared forwarding rules
are preserved; a separate managed rule on an unused public port is created for
the edited exposure. A missing rule can also be replaced. The new public port is
reported in the exposure list. Failed updates attempt to restore the previous
NPM and forwarding settings and remove any replacement rule. If rollback fails,
the exposure is marked Error and retains its forwarding reference for cleanup;
the API identifies the exposure for administrator follow-up.

## Backend health checks

The Expose form labels backend protocol and private port separately, with an optional
**Test Connection** action, which becomes **Re-test** after a result. Switching
between HTTP and HTTPS changes default ports 80/443 while preserving custom ports. The management
server dispatches an asynchronous job through the existing router diagnostics
channel. The selected network's virtual router sends `GET /` to the instance's
private IPv4 address and application port. The application sees the router's
address, not the management server's address. The management server never opens
an HTTP connection to the application, and there is no fallback to management
probing. Only HTTP 200 is healthy; redirects are reported without following them.

A compact result row shows checking progress or connection status, elapsed time
and an HTTP status badge. Hover over failures for the full explanation;
certificate warnings use a tooltip icon. If the latest
check failed, clicking OK opens a confirmation with **Expose anyway** and Cancel.
No exposure is created unless the user explicitly proceeds. Changing connection
settings, resetting or closing the form clears results and dismisses any pending
confirmation. If the backend has not been checked with the current settings,
clicking OK offers **Test Connection** or **Expose anyway**. Testing runs the
health check without creating an exposure. A successful check needs no warning.

The asynchronous `checkInstanceProxyBackend` API accepts `virtualmachineid`,
`networkid`, `protocol`, and `port`. Legacy `ipaddressid` and `publicport` parameters
remain accepted and validated when supplied, but do not affect the probe.
Instance ownership, network membership, NIC and port validation remain enforced.
Stopped instances return an unhealthy result. The router must be running and
attached to the selected network; redundant routers must be PRIMARY, and shared
network routers must match the instance's pod when it is known. Networks with no
suitable router return an advisory unavailable result.

Checking creates no forwarding, firewall rule, domain mapping or NPM host. It
does not modify existing manual or managed forwarding rules, and does not require
a public IP or NPM credentials. The check tests the application on the private
network, not public forwarding, public DNS or reachability from NPM/the Internet.
Normal exposure still creates or reuses forwarding as described above.

The router probe uses Python 3's standard HTTP client without credentials,
cookies, redirects, proxy environment settings or automatic retries. Connection
and read timeouts are five seconds; a 15-second total deadline also covers TLS
and the certificate fallback. Header lines are limited to 8 KiB, with at most 64
headers, and application bodies are not consumed. Loopback, link-local,
unspecified and multicast addresses are rejected. HTTPS first verifies the
certificate and hostname. Certificate verification failures alone allow another
attempt without verification, with a UI warning. Other TLS failures fail the
check. The management-to-agent command wait is 30 seconds.

Responses include `healthy`, `httpstatus` (when received), `message`, `checkedat`,
`elapsedms`, `tlsverified` (when available for HTTPS), and `cleanupcomplete` (true
because these checks create no resources). Older `rphc-` marked temporary rules
from management-originated checks remain protected from adoption by normal
exposure. The existing worker runs every minute, even when the integration is
disabled, to retry removal of those legacy rules older than five minutes.

### Router script deployment

Deploy the updated reverse proxy plugin/UI and both router files:

- `/opt/cloud/bin/diagnostics.py`
- `/opt/cloud/bin/check_proxy_backend.py`

Both scripts are included by the normal System VM `cloud-scripts.tgz` packaging
from `systemvm/debian/`. Existing routers must receive the updated System VM
scripts through the normal upgrade/patch process before checks work. The existing
agent diagnostics command is reused; no new agent command is required. Missing
or outdated scripts produce an advisory unavailable result. Never re-enable
management-originated HTTP as a compatibility fallback.

## Cleanup and upgrades

Deleting an exposure, deleting its domain suffix, or expunging its VM removes the
NPM host and the forwarding/firewall rules created for that exposure. Existing
user-created rules and allocated public IPs are retained, including when an
exposure reused an existing manual forwarding rule. Multiple exposures can share
one forwarding rule. A rule created by the integration is removed only when its
last referencing exposure is removed; manual rules are always retained. A failed creation
attempt rolls back the resources it created. An incomplete rollback or deletion
retains an `Error` mapping; retry `deleteInstanceProxy` after resolving the
reported network or NPM failure. A domain whose proxies cannot be cleaned up is
retained so deletion can be retried. Newly created NPM hosts carry the mapping's
UUID in their `meta` object. Recovery after an interrupted create checks that
UUID before deleting a host discovered by name. NPM's [proxy host API schema](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/v2.12.6/backend/schema/components/proxy-host-object.json)
supports this metadata object.

The 4.22.1.0 to 4.22.2.0 schema upgrade adds nullable public IP, public port, and
forwarding rule references. The 4.22.2.0 to 4.22.3.0 upgrade adds the forwarding
ownership flag. Existing mappings default to managed ownership because previous
versions only created new rules. Existing shared network mappings need no backfill.
Run the normal database upgrade before starting the updated management server.
The 4.23 upgrade also adds all four forwarding columns when missing, including
for installations already recorded as 4.22.3.0. It preserves existing values and
skips databases without the optional reverse proxy host table. No manual column
addition is needed when upgrading to this version.

The deprecated `reverseproxy.domain` setting is no longer required when domain
suffixes have been configured using the domain management APIs.

## Validation

Run backend tests and their reactor dependencies:

```sh
mvn -pl plugins/integrations/reverseproxy -am test \
  -Dtest='ReverseProxy*Test,VirtualRouterBackendProbeTest,NpmClientTest' \
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

Run the router probe tests (Python 3 and OpenSSL required):

```sh
python3 -m unittest discover -s systemvm/test -p test_check_proxy_backend.py -v
```

For live validation, use a test environment with the updated router scripts.
Verify shared, isolated and VPC private-backend checks, PRIMARY router selection,
missing/stopped routers, and trusted/untrusted HTTPS. Check that the application
sees the router as the request source, that no forwarding/firewall/NPM resources
are created or changed, and that failed-check confirmation only exposes after
**Expose anyway**. Java tests mock agent dispatch; Python tests use local HTTP/TLS
servers rather than a running CloudStack router.
