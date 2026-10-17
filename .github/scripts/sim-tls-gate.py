#!/usr/bin/env python3
"""The iPhone simulator's TLS gate and watchdog for the end-to-end flows.

On CI Macs the simulator's certificate daemon (trustd) sometimes stops answering for a minute or more, and every app's
HTTPS waits on it. This keeps it answering and tells the flows when it does:

- Every 10 s it makes one HTTPS request to the dev sandbox inside the simulator (e2e/sim-probe, through trustd) and one
  from the Mac itself. When the simulator's fails twice in a row while the Mac's goes through, trustd is stuck: a
  stack sample of it is saved, and it is restarted.
- GET http://127.0.0.1:8732/ answers 200 when the simulator's request goes through right now, 503 otherwise. The flows
  ask before they make a session and before they press Pay (e2e/maestro/api-ready.js).
- Failures and restarts are logged (both sides' results), and the samples kept, in the folder given.

    sim-tls-gate.py <probe binary> <log folder>    (UDID: the booted simulator)
"""
import http.server
import os
import subprocess
import sys
import threading
import time

UDID, PROBE, LOGS = os.environ["UDID"], sys.argv[1], sys.argv[2]
HEALTH = "https://api.dev.vezmo.com/api/v1/health"
lock = threading.Lock()
failures = 0


def log(message):
    with open(os.path.join(LOGS, "gate.log"), "a") as out:
        out.write(f"{time.strftime('%H:%M:%S')} {message}\n")


def run(args, timeout):
    try:
        return subprocess.run(args, timeout=timeout, capture_output=True, text=True)
    except subprocess.TimeoutExpired:
        return None


def simulator_ok():
    done = run(["xcrun", "simctl", "spawn", UDID, PROBE], 20)
    return done is not None and done.returncode == 0


def mac_ok():
    done = run(["curl", "-s", "-o", "/dev/null", "-m", "8", "-w", "%{http_code}", HEALTH], 10)
    return done is not None and done.stdout == "200"


def check():
    """One check of both sides; restarts trustd when the simulator alone fails twice in a row."""
    global failures
    with lock:
        if simulator_ok():
            if failures:
                log("simulator HTTPS ok again")
            failures = 0
            return True
        mac = mac_ok()
        failures = failures + 1 if mac else 0
        log(f"simulator HTTPS failed; the Mac's {'went through' if mac else 'failed too (network or sandbox)'}")
        if failures >= 2:
            pid = run(["pgrep", "-f", "RuntimeRoot/usr/libexec/trustd$"], 5)
            for p in (pid.stdout.split() if pid else []):
                run(["sample", p, "2", "-file", os.path.join(LOGS, f"trustd-{p}-{time.strftime('%H%M%S')}.txt")], 30)
            log("trustd is stuck: restarted it (stack sample saved)")
            run(["xcrun", "simctl", "spawn", UDID, "launchctl", "kickstart", "-k", "system/com.apple.trustd"], 30)
            failures = 0
        return False


def watch():
    while True:
        check()
        time.sleep(10)


class Gate(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if check() else 503)
        self.end_headers()

    def log_message(self, *args):
        pass


os.makedirs(LOGS, exist_ok=True)
threading.Thread(target=watch, daemon=True).start()
http.server.ThreadingHTTPServer(("127.0.0.1", 8732), Gate).serve_forever()
