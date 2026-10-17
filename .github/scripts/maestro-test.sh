#!/usr/bin/env bash
# Runs a folder of Maestro flows on one business model, on the device the job set up.
#
#   maestro-test.sh <connect|mor> <output folder> <flows folder> [more `maestro test` options]
#
# - The flows get TRACK (the example apps launch on that business); the flows tagged for the other model only
#   (`connect-only`, `mor-only`) are left out.
# - On an iPhone simulator (UDID set) the test driver the warm-up started is reused, and the flows get the simulator's
#   TLS gate (.github/actions/ios-simulator).
# - When Maestro's driver doesn't start, no flow has run: that is infrastructure, so the run starts once more. A flow
#   that ran and failed is never run again.
set -uo pipefail

track=$1 out=$2
shift 2
case "$track" in
  connect) other=mor-only ;;
  mor) other=connect-only ;;
  *) echo "Unknown business model: $track" >&2; exit 2 ;;
esac

options=(test -e "TRACK=$track" --exclude-tags "$other" --format junit --output "$out/report.xml" --test-output-dir "$out")
if [ -n "${UDID:-}" ]; then
  options=(--device "$UDID" "${options[@]}" --no-reinstall-driver)
  export MAESTRO_DRIVER_STARTUP_TIMEOUT=${MAESTRO_DRIVER_STARTUP_TIMEOUT:-300000}
fi
# The simulator's TLS gate (.github/actions/ios-simulator): the flows wait for it before a session and before Pay.
[ -n "${SIM_TLS_GATE:-}" ] && options+=(-e "SIM_TLS_GATE=$SIM_TLS_GATE")
# What the gate saw go wrong during this run (failed checks, trustd restarts), at the end of the step's log.
gate_report() { [ -n "${SIM_TLS_GATE_LOG:-}" ] && grep -v ': ok$' "$SIM_TLS_GATE_LOG" | sed 's/^/TLS gate: /' || true; }
trap gate_report EXIT

mkdir -p "$out"
for attempt in 1 2; do
  maestro "${options[@]}" "$@" 2>&1 | tee "$out/maestro.txt"
  status=${PIPESTATUS[0]}
  [ "$status" -eq 0 ] && exit 0
  if grep -qE '^\[(Passed|Failed)\]' "$out/maestro.txt" || ! grep -qiE 'driver not ready|driver did not start' "$out/maestro.txt"; then
    exit "$status"
  fi
  echo "::warning::Maestro's driver did not start (no flow ran); starting it once more."
  pkill -f 'xcodebuild.*maestro' || true
done
exit "$status"
