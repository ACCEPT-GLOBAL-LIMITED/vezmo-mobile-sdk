import SwiftUI
import VezmoCheckout

/// The Vezmo SDK example: a small shop with one product and a Buy button that opens the Vezmo sheet. A real app gets
/// the session's client token from its own server; here it comes from the sandbox demo endpoint (`VEZMO_DEMO_URL`)
/// or is pasted in under Sandbox.
@main
struct VezmoExampleApp: App {
  var body: some Scene {
    WindowGroup {
      Shop()
        // Back from 3D Secure, a bank app or a pay-later provider.
        .onOpenURL { VezmoCheckout.handleURL($0) }
    }
  }
}

/// A launch argument the end-to-end runs pass (`-name value`), or nil.
private func launchArgument(_ name: String) -> String? {
  if let value = UserDefaults.standard.string(forKey: name), !value.isEmpty { return value }
  let args = ProcessInfo.processInfo.arguments
  guard let i = args.firstIndex(where: { $0.trimmingCharacters(in: CharacterSet(charactersIn: "-")) == name }),
        i + 1 < args.count else { return nil }
  return args[i + 1]
}

/// The end-to-end runs launch the app with `offerCurrency` (e.g. AED): the sandbox then offers the buyer that currency,
/// as it would abroad (the CI runners are in the US).
private let offerCurrency = launchArgument("offerCurrency")

/// The end-to-end runs launch the app with `track` (`mor`, the sandbox's merchant-of-record business, or `connect`):
/// the sandbox buttons that name no business use it.
private let launchTrack = launchArgument("track")?.lowercased()

/// A demo endpoint's address on a business (`mor` or not), with the offer currency the run asked for.
private func demoURL(_ string: String, track: String?) -> URL? {
  let query = [track == "mor" ? "track=mor" : nil, offerCurrency.map { "offerCurrency=\($0)" }].compactMap { $0 }
  guard !query.isEmpty else { return URL(string: string) }
  return URL(string: string + (string.contains("?") ? "&" : "?") + query.joined(separator: "&"))
}

/// A build setting passed through Info.plist (empty when not set).
private func setting(_ key: String) -> String? {
  (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
}

struct Shop: View {
  @State private var vezmo = VezmoCheckout(configuration: .init(
    returnURL: URL(string: "vezmoexample://vezmo"),
    // The app's own Apple Merchant ID (Apple Developer → Identifiers → Merchant IDs): Apple Pay shows only with it.
    applePay: .init(merchantId: "merchant.com.vezmo.example"),
    apiBase: setting("VezmoAPIBase").flatMap(URL.init(string:)) ?? URL(string: "https://api.vezmo.com/api/v1")!
  ))
  @State private var token = ""
  @State private var status = ""
  @State private var result: CheckoutResult?
  @State private var showing = false
  @FocusState private var typingToken: Bool

  private var ready: Bool { !token.trimmingCharacters(in: .whitespaces).isEmpty }
  /// "Subscribe, no trial" is on the merchant-of-record business unless the run picks another.
  private var noTrialTrack: String { launchTrack ?? "mor" }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        Text("Northwind Audio")
          .font(.system(size: 28, weight: .bold)).tracking(-0.5)
          .foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity, alignment: .leading)
          .contentShape(Rectangle())
          // Tapping outside the field closes the keyboard.
          .onTapGesture { typingToken = false }

        product

        if !status.isEmpty {
          Text(status).font(.system(size: 14)).foregroundColor(Color(hex: 0x5A5F70))
            .multilineTextAlignment(.center).frame(maxWidth: .infinity)
            .accessibilityIdentifier("status")
        }
        if let result {
          Text("Result: \(describe(result))").font(.custom("Menlo", size: 13)).foregroundColor(Color(hex: 0x0B0C14))
            .accessibilityIdentifier("result")
        }

        sandbox

        club
      }
      .padding(.horizontal, 20)
      .padding(.top, 12)
      .padding(.bottom, 20)
    }
    // An opaque strip behind the status bar: the page scrolls under it, never under the clock.
    .safeAreaInset(edge: .top, spacing: 0) {
      Color.clear.frame(height: 0).background(Color(hex: 0xF4F5F8), ignoresSafeAreaEdges: .top)
    }
    .background(Color(hex: 0xF4F5F8).ignoresSafeArea())
    .vezmoCheckout(vezmo, isPresented: $showing) { result = $0 }
  }

  private var product: some View {
    VStack(alignment: .leading, spacing: 14) {
      Headphones()
        .frame(maxWidth: .infinity)
        .frame(height: 150)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(hex: 0xE9E7F7)))
      VStack(alignment: .leading, spacing: 4) {
        Text("Aurora wireless headphones").font(.system(size: 17, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
        Text("$49.00").font(.system(size: 15)).foregroundColor(Color(hex: 0x5A5F70))
      }
      .padding(.horizontal, 4)
      Button { Task { await pay() } } label: {
        Text("Buy now").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
          .frame(maxWidth: .infinity).frame(height: 52)
          .background(Capsule(style: .circular).fill(Color(hex: 0x0B0C14)))
      }
      .disabled(!ready)
      .opacity(ready ? 1 : 0.35)
      .accessibilityIdentifier("pay")
    }
    .padding(14)
    .background(
      RoundedRectangle(cornerRadius: 22).fill(Color.white)
        .shadow(color: Color(red: 16 / 255, green: 24 / 255, blue: 64 / 255).opacity(0.06), radius: 1, x: 0, y: 1)
    )
  }

  private var sandbox: some View {
    VStack(alignment: .leading, spacing: 10) {
      Text("SANDBOX · SDK \(VezmoCheckout.version)")
        .font(.system(size: 12, weight: .semibold)).tracking(0.4).foregroundColor(Color(hex: 0x8A8FA0))
      TextField("Client token from your server", text: $token)
        .font(.system(size: 14))
        .autocapitalization(.none)
        .disableAutocorrection(true)
        .padding(.horizontal, 12)
        .frame(height: 44)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.white))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color(hex: 0xE1E3EC), lineWidth: 1))
        .focused($typingToken)
        .accessibilityIdentifier("client-token")
      Button { Task { await newSession(track: launchTrack) } } label: {
        Text(launchTrack == "mor" ? "New sandbox session · merchant of record" : "New sandbox session").font(.system(size: 14, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity).frame(height: 44)
          .background(Capsule(style: .circular).fill(Color.white))
          .overlay(Capsule(style: .circular).strokeBorder(Color(hex: 0xE1E3EC), lineWidth: 1))
      }
      .accessibilityIdentifier("new-session")
      // The sandbox's merchant-of-record business (Cash App, Klarna and Amazon Pay on).
      Button { Task { await newSession(track: "mor") } } label: {
        Text("New session · merchant of record").font(.system(size: 14, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity).frame(height: 44)
          .background(Capsule(style: .circular).fill(Color.white))
          .overlay(Capsule(style: .circular).strokeBorder(Color(hex: 0xE1E3EC), lineWidth: 1))
      }
      .accessibilityIdentifier("new-session-mor")
      Button { Task { await subscribe(track: noTrialTrack, trial: false) } } label: {
        Text(noTrialTrack == "mor" ? "Subscribe, no trial · merchant of record" : "Subscribe, no trial").font(.system(size: 14, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity).frame(height: 44)
          .background(Capsule(style: .circular).fill(Color.white))
          .overlay(Capsule(style: .circular).strokeBorder(Color(hex: 0xE1E3EC), lineWidth: 1))
      }
      .accessibilityIdentifier("subscribe-now")
    }
    .padding(14)
    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Color(hex: 0xE1E3EC), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
  }

  private func newSession(track: String?) async {
    guard let demo = setting("VezmoDemoURL") else {
      status = "Paste a client token, or set VEZMO_DEMO_URL."
      return
    }
    guard let url = demoURL(demo, track: track) else { return }
    let created = await demoToken(url, field: "clientToken", label: "Creating a sandbox session…")
    if let created { token = created }
    status = created == nil ? "Could not reach the demo endpoint." : "Session ready."
  }

  /// The sandbox's demo endpoints, with a short timeout and up to three tries (a CI runner's first requests can stall,
  /// and the sandbox restarts now and then), each try on screen. The token in `field`, or nil.
  private func demoToken(_ url: URL, field: String, label: String) async -> String? {
    for attempt in 1...3 {
      status = attempt == 1 ? label : "\(label) (try \(attempt) of 3)"
      var request = URLRequest(url: url, timeoutInterval: 15)
      request.httpMethod = "POST"
      if let answer = try? await URLSession.shared.data(for: request),
         let http = answer.1 as? HTTPURLResponse, (200..<300).contains(http.statusCode),
         let json = try? JSONSerialization.jsonObject(with: answer.0) as? [String: Any],
         let value = (json["data"] as? [String: Any])?[field] as? String, !value.isEmpty {
        return value
      }
      if attempt < 3 { try? await Task.sleep(nanoseconds: UInt64(attempt) * 3_000_000_000) }
    }
    return nil
  }

  /// A subscription: the club with a 7-day free trial.
  private var club: some View {
    VStack(alignment: .leading, spacing: 14) {
      HStack(spacing: 12) {
        Circle().strokeBorder(Color(hex: 0x2B2D3A), lineWidth: 7).frame(width: 26, height: 26)
          .frame(width: 56, height: 56)
          .background(RoundedRectangle(cornerRadius: 14).fill(Color(hex: 0xE9E7F7)))
        VStack(alignment: .leading, spacing: 4) {
          Text("Northwind Audio Club").font(.system(size: 17, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
          Text("$9.99 / month · first 7 days free").font(.system(size: 15)).foregroundColor(Color(hex: 0x5A5F70))
        }
        .padding(.horizontal, 4)
      }
      Button { Task { await subscribe(track: launchTrack, trial: true) } } label: {
        Text("Try 7 days free").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
          .frame(maxWidth: .infinity).frame(height: 52)
          .background(Capsule(style: .circular).fill(Color(hex: 0x0B0C14)))
      }
      .accessibilityIdentifier("subscribe")
    }
    .padding(14)
    .background(
      RoundedRectangle(cornerRadius: 22).fill(Color.white)
        .shadow(color: Color(red: 16 / 255, green: 24 / 255, blue: 64 / 255).opacity(0.06), radius: 1, x: 0, y: 1)
    )
  }

  /// A real app's server creates the subscription (POST /merchant/subscription) and returns its checkoutToken; here
  /// the sandbox's demo endpoint does.
  private func subscribe(track: String?, trial: Bool) async {
    let endpoint = setting("VezmoDemoURL")?.replacingOccurrences(of: "mobile-session", with: "mobile-subscription")
    guard let endpoint, let url = demoURL(endpoint + (trial ? "?trial=1" : ""), track: track) else {
      status = "Set VEZMO_DEMO_URL."
      return
    }
    typingToken = false
    result = nil
    guard let subscriptionToken = await demoToken(url, field: "subscriptionToken", label: "Loading checkout…") else {
      status = "Could not reach the demo endpoint."
      return
    }
    if let error = await vezmo.prepare(subscriptionToken: subscriptionToken) {
      status = "Could not load: \(error.message)"
      return
    }
    status = ""
    showing = true
  }

  private func pay() async {
    typingToken = false
    result = nil
    status = "Loading checkout…"
    if let error = await vezmo.prepare(clientToken: token) {
      status = "Could not load: \(error.message)"
      return
    }
    status = ""
    showing = true
  }

  private func describe(_ result: CheckoutResult) -> String {
    switch result {
    case .succeeded(let paymentId, let transactionId, let subscription):
      return #"{"status":"succeeded","paymentId":"\#(paymentId ?? "")","transactionId":"\#(transactionId ?? "")"\#(describe(subscription))}"#
    case .processing(let paymentId, let subscription):
      return #"{"status":"processing","paymentId":"\#(paymentId ?? "")"\#(describe(subscription))}"#
    case .canceled:
      return #"{"status":"canceled"}"#
    case .failed(let error):
      return #"{"status":"failed","error":{"code":"\#(error.code.rawValue)","message":"\#(error.message)"}}"#
    }
  }

  private func describe(_ subscription: SubscriptionResult?) -> String {
    guard let subscription else { return "" }
    return #","subscription":{"ref":"\#(subscription.ref)","status":"\#(subscription.status.rawValue)"}"#
  }
}

/// A pair of headphones, drawn: the band and two cups.
private struct Headphones: View {
  var body: some View {
    let ink = Color(hex: 0x2B2D3A)
    VStack(spacing: -16) {
      Band().stroke(ink, lineWidth: 9).frame(width: 112, height: 70)
      HStack(spacing: 74) {
        RoundedRectangle(cornerRadius: 12).fill(ink).frame(width: 28, height: 48)
        RoundedRectangle(cornerRadius: 12).fill(ink).frame(width: 28, height: 48)
      }
    }
  }

  private struct Band: Shape {
    func path(in rect: CGRect) -> Path {
      let box = rect.insetBy(dx: 4.5, dy: 4.5)
      let r = box.width / 2
      var path = Path()
      path.move(to: CGPoint(x: box.minX, y: rect.maxY))
      path.addLine(to: CGPoint(x: box.minX, y: box.minY + r))
      path.addArc(center: CGPoint(x: box.midX, y: box.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
      path.addLine(to: CGPoint(x: box.maxX, y: rect.maxY))
      return path
    }
  }
}

private extension Color {
  init(hex: UInt32) {
    self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
  }
}
