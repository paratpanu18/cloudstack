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

import contextlib
import http.server
import importlib.util
import io
import json
import pathlib
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "debian/opt/cloud/bin/check_proxy_backend.py"
spec = importlib.util.spec_from_file_location("check_proxy_backend", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Handler(http.server.BaseHTTPRequestHandler):
    status = 200
    delay = 0
    streaming = False
    requests = []
    release = threading.Event()

    def log_message(self, *args):
        pass

    def do_GET(self):
        type(self).requests.append((self.path, dict(self.headers)))
        time.sleep(type(self).delay)
        self.send_response(type(self).status)
        self.send_header("Location", "/redirected")
        self.send_header("Set-Cookie", "session=secret")
        self.end_headers()
        if type(self).streaming:
            type(self).release.wait(4)


class BackendProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.certs = tempfile.TemporaryDirectory()
        cls.cert = str(pathlib.Path(cls.certs.name) / "cert.pem")
        cls.key = str(pathlib.Path(cls.certs.name) / "key.pem")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                        "-keyout", cls.key, "-out", cls.cert], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    @classmethod
    def tearDownClass(cls):
        cls.certs.cleanup()

    def setUp(self):
        Handler.status = 200
        Handler.delay = 0
        Handler.streaming = False
        Handler.requests = []
        Handler.release.clear()
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = None

    def tearDown(self):
        Handler.release.set()
        if self.thread:
            self.server.shutdown()
            self.thread.join()
        self.server.server_close()

    def start(self, tls=False):
        if tls:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(self.cert, self.key)
            self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def check_server(self, protocol="http"):
        request = probe.request
        # Emulate the VM's private NIC routing to this test's loopback server.
        with mock.patch.object(probe, "request", side_effect=lambda protocol, address, port, verify=True:
                               request(protocol, "127.0.0.1", port, verify)):
            return probe.check(protocol, "10.0.0.10", self.server.server_port)

    def test_http_200_and_no_credentials_or_cookies(self):
        self.start()
        for _ in range(2):
            result = self.check_server()
            self.assertTrue(result["healthy"])
            self.assertEqual(200, result["httpstatus"])
            self.assertNotIn("tlsverified", result)
        for path, headers in Handler.requests:
            self.assertEqual("/", path)
            self.assertNotIn("Authorization", headers)
            self.assertNotIn("Cookie", headers)

    def test_redirects_and_non_200_success_are_unhealthy(self):
        self.start()
        for status in (204, 302, 401, 404, 503):
            Handler.status = status
            result = self.check_server()
            self.assertFalse(result["healthy"])
            self.assertEqual(status, result["httpstatus"])
        self.assertEqual(["/"] * 5, [path for path, _ in Handler.requests])

    def test_response_body_is_not_consumed(self):
        Handler.streaming = True
        self.start()
        result = self.check_server()
        self.assertTrue(result["healthy"])
        self.assertLess(result["elapsedms"], 2000)

    def test_header_timeout(self):
        Handler.delay = 5.5
        self.start()
        result = self.check_server()
        self.assertFalse(result["healthy"])
        self.assertIn("timed out", result["message"])

    def test_connection_refused(self):
        port = self.server.server_port
        self.server.server_close()
        request = probe.request
        with mock.patch.object(probe, "request", side_effect=lambda *args, **kwargs: request("http", "127.0.0.1", port)):
            result = probe.check("http", "10.0.0.10", port)
        self.assertFalse(result["healthy"])
        self.assertNotIn("httpstatus", result)

    def test_untrusted_https_retries_with_warning(self):
        self.start(tls=True)
        result = self.check_server("https")
        self.assertTrue(result["healthy"])
        self.assertFalse(result["tlsverified"])

    def test_trusted_https_verifies_certificate_and_hostname(self):
        self.start(tls=True)
        trusted = ssl.create_default_context(cafile=self.cert)
        with mock.patch.object(probe.ssl, "create_default_context", return_value=trusted):
            result = self.check_server("https")
        self.assertTrue(result["healthy"])
        self.assertTrue(result["tlsverified"])

    def test_other_tls_failures_do_not_retry(self):
        with mock.patch.object(probe, "request", side_effect=ssl.SSLError("unsupported TLS")) as request:
            result = probe.check("https", "10.0.0.10", 443)
        self.assertFalse(result["healthy"])
        request.assert_called_once()

    def test_invalid_destinations_never_open_a_connection(self):
        with mock.patch.object(probe, "request") as request:
            for address in ("127.0.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1", "10.0.0.1;id", "example.com"):
                self.assertFalse(probe.check("http", address, 80)["healthy"])
            self.assertFalse(probe.check("ftp", "10.0.0.10", 80)["healthy"])
            self.assertFalse(probe.check("http", "10.0.0.10", 65536)["healthy"])
            request.assert_not_called()

    def test_deadline_reports_unhealthy(self):
        with mock.patch.object(probe, "request", side_effect=TimeoutError):
            result = probe.check("http", "10.0.0.10", 80)
        self.assertFalse(result["healthy"])
        self.assertIn("timed out", result["message"])

    def test_cli_bounds_entire_probe_and_returns_json(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "http", "10.0.0.10", "80"]), \
                mock.patch.object(probe.signal, "signal"), mock.patch.object(probe.signal, "alarm") as alarm, \
                mock.patch.object(probe, "check", return_value={"healthy": True}), contextlib.redirect_stdout(io.StringIO()) as output:
            probe.main()
        self.assertEqual([mock.call(15), mock.call(0)], alarm.call_args_list)
        self.assertTrue(json.loads(output.getvalue())["healthy"])

    def test_diagnostics_dispatch_uses_argument_array_without_shell(self):
        spec = importlib.util.spec_from_file_location("diagnostics", SCRIPT.parent / "diagnostics.py")
        diagnostics = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(diagnostics)
        with mock.patch.object(sys, "argv", ["diagnostics.py", "backend-health", "http", "10.0.0.10", "80"]):
            command = diagnostics.get_command()
        self.assertIsInstance(command, list)
        self.assertEqual(["http", "10.0.0.10", "80"], command[-3:])
        with mock.patch.object(diagnostics.subprocess, "Popen") as popen, contextlib.redirect_stdout(io.StringIO()):
            popen.return_value.communicate.return_value = (b'{"healthy":true}', b"")
            popen.return_value.returncode = 0
            diagnostics.run_cmd(command)
        self.assertEqual(command, popen.call_args.args[0])
        self.assertNotIn("shell", popen.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
