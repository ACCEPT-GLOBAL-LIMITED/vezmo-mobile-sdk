// Waits until the flow can safely make a session or press Pay, for up to six minutes:
// - Vezmo's dev sandbox answers its health check (three times in a row once it has failed). Other teams deploy it at
//   any time, and it answers 5xx for a minute or so while it restarts.
// - On an iPhone simulator, an HTTPS request made inside it gets through (SIM_TLS_GATE, .github/scripts/sim-tls-gate.py,
//   which restarts the simulator's certificate checks when they stop answering).
var HEALTH = 'https://api.dev.vezmo.com/api/v1/health';
var GATE = typeof SIM_TLS_GATE === 'undefined' ? '' : SIM_TLS_GATE;

function answers(url) {
  try {
    return http.get(url).ok;
  } catch (e) {
    return false;
  }
}

// Maestro's scripts have no timer: wait by watching the clock.
function pause(ms) {
  var until = Date.now() + ms;
  while (Date.now() < until) {}
}

var deadline = Date.now() + 360000;
var inARow = answers(HEALTH) ? 3 : 0;
var simulator = !GATE || answers(GATE);
while ((inARow < 3 || !simulator) && Date.now() < deadline) {
  pause(2000);
  inARow = answers(HEALTH) ? inARow + 1 : 0;
  simulator = !GATE || answers(GATE);
}
output.apiReady = inARow >= 3 && simulator;
