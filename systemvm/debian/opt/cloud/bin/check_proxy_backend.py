#!/usr/bin/env python
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Bounded HTTP header probe, executed inside the selected network's virtual router."""
import argparse
import http.client
import ipaddress
import json
import signal
import socket
import ssl
import time

# Bound parsing of untrusted application response headers.
http.client._MAXLINE = 8192
http.client._MAXHEADERS = 64


def request(protocol, address, port, verify=True):
    if protocol == "https":
        context = ssl.create_default_context() if verify else ssl._create_unverified_context()
        connection = http.client.HTTPSConnection(address, port, timeout=5, context=context)
    else:
        connection = http.client.HTTPConnection(address, port, timeout=5)
    try:
        connection.request("GET", "/", headers={"User-Agent": "CloudStack-Backend-Health", "Connection": "close"})
        # No redirects, credentials, cookies, retries, or reads of the response body.
        response = connection.getresponse()
        return response.status
    finally:
        connection.close()


def check(protocol, address, port):
    result = {"healthy": False, "cleanupcomplete": True}
    started = time.monotonic()
    try:
        ip = ipaddress.IPv4Address(address)
        if ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            raise ValueError("Invalid backend address")
        if protocol not in ("http", "https") or not 1 <= port <= 65535:
            raise ValueError("Invalid backend protocol or port")
        try:
            status = request(protocol, address, port)
            if protocol == "https":
                result["tlsverified"] = True
        except ssl.SSLCertVerificationError:
            if protocol != "https":
                raise
            result["tlsverified"] = False
            status = request(protocol, address, port, verify=False)
        result["httpstatus"] = status
        result["healthy"] = status == 200
        result["message"] = "HTTP 200 OK" if status == 200 else "Backend returned HTTP %d" % status
    except (socket.timeout, TimeoutError):
        result["message"] = "Backend connection or response timed out"
    except ssl.SSLError:
        result["message"] = "TLS negotiation with the backend failed"
    except (OSError, http.client.HTTPException):
        result["message"] = "Unable to connect to the backend from the virtual router"
    except ValueError:
        result["message"] = "Invalid backend connection settings"
    finally:
        result["elapsedms"] = int((time.monotonic() - started) * 1000)
    return result


def deadline_expired(signum, frame):
    raise TimeoutError("Backend check deadline exceeded")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", choices=("http", "https"))
    parser.add_argument("address")
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    # Applies across connect, TLS, response headers and the optional certificate retry.
    signal.signal(signal.SIGALRM, deadline_expired)
    signal.alarm(15)
    try:
        result = check(args.protocol, args.address, args.port)
    finally:
        signal.alarm(0)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
