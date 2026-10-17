// Runs inside the iPhone simulator (`xcrun simctl spawn`): one HTTPS request to the dev sandbox through the simulator's
// own TLS and certificate checks (trustd). Exits 0 when it answered 200 within 10 s, 1 otherwise.
import Foundation

let url = URL(string: CommandLine.arguments.dropFirst().first ?? "https://api.dev.vezmo.com/api/v1/health")!
let session = URLSession(configuration: .ephemeral)
let done = DispatchSemaphore(value: 0)
var ok = false
session.dataTask(with: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)) { _, response, _ in
  ok = (response as? HTTPURLResponse)?.statusCode == 200
  done.signal()
}.resume()
_ = done.wait(timeout: .now() + 12)
exit(ok ? 0 : 1)
