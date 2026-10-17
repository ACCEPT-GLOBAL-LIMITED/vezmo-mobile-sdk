// Waits until Vezmo's dev sandbox answers its health check (three times in a row once it has failed), for up to three
// minutes. Other teams deploy the sandbox at any time, and it answers 5xx for a minute or so while it restarts: a flow
// waits here instead of making a session or pressing Pay into that minute.
var HEALTH = 'https://api.dev.vezmo.com/api/v1/health';

function healthy() {
  try {
    return http.get(HEALTH).ok;
  } catch (e) {
    return false;
  }
}

// Maestro's scripts have no timer: wait by watching the clock.
function pause(ms) {
  var until = Date.now() + ms;
  while (Date.now() < until) {}
}

var deadline = Date.now() + 180000;
var inARow = healthy() ? 3 : 0;
while (inARow < 3 && Date.now() < deadline) {
  pause(2000);
  inARow = healthy() ? inARow + 1 : 0;
}
output.apiReady = inARow >= 3;
