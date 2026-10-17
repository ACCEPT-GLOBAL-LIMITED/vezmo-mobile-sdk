#!/usr/bin/env python3
"""The iPhone simulator's TLS gate for the end-to-end flows.

GET http://127.0.0.1:8732/ answers 200 when an HTTPS request made inside the simulator (through its certificate checks,
trustd) reaches the dev sandbox right now, 503 when it doesn't. The flows ask before they make a session and before
they press Pay (e2e/maestro/api-ready.js). On CI Macs the simulator's trustd sometimes stops answering for a minute or
more, and every app's HTTPS waits on it: after three failed checks in a row the gate restarts trustd.

    sim-tls-gate.py <probe binary>    (UDID: the booted simulator)
"""
import http.server
import os
import subprocess
import sys
import time

UDID, PROBE = os.environ["UDID"], sys.argv[1]
failures = 0


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def tls_ok():
    try:
        return subprocess.run(["xcrun", "simctl", "spawn", UDID, PROBE], timeout=20, capture_output=True).returncode == 0
    except subprocess.TimeoutExpired:
        return False


class Gate(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global failures
        ok = tls_ok()
        failures = 0 if ok else failures + 1
        log(f"TLS inside the simulator: {'ok' if ok else f'failed ({failures} in a row)'}")
        if failures >= 3:
            log("trustd isn't answering: restarting it")
            subprocess.run(["xcrun", "simctl", "spawn", UDID, "launchctl", "kickstart", "-k", "system/com.apple.trustd"])
            failures = 0
        self.send_response(200 if ok else 503)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", 8732), Gate).serve_forever()
