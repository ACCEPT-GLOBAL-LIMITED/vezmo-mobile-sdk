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

  private var ready: Bool { !token.trimmingCharacters(in: .whitespaces).isEmpty }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        Text("Northwind Audio")
          .font(.system(size: 28, weight: .bold)).tracking(-0.5)
          .foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity, alignment: .leading)
          .contentShape(Rectangle())
          // Tapping outside the field closes the keyboard.
          .onTapGesture { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }

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
      }
      .padding(.horizontal, 20)
      .padding(.top, 12)
      .padding(.bottom, 20)
    }
    .background(Color(hex: 0xF4F5F8).ignoresSafeArea())
    .vezmoCheckout(vezmo, isPresented: $showing) { result = $0 }
  }

  private var product: some View {
    VStack(alignment: .leading, spacing: 14) {
      Headphones()
        .frame(maxWidth: .infinity)
        .frame(height: 210)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(hex: 0xE9E7F7)))
      VStack(alignment: .leading, spacing: 4) {
        Text("Aurora wireless headphones").font(.system(size: 17, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
        Text("$49.00").font(.system(size: 15)).foregroundColor(Color(hex: 0x5A5F70))
      }
      .padding(.horizontal, 4)
      Button { Task { await pay() } } label: {
        Text("Buy now").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
          .frame(maxWidth: .infinity).frame(height: 52)
          .background(Capsule().fill(Color(hex: 0x0B0C14)))
      }
      .disabled(!ready)
      .opacity(ready ? 1 : 0.35)
      .accessibilityIdentifier("pay")
    }
    .padding(14)
    .background(
      RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.white)
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
        .accessibilityIdentifier("client-token")
      Button { Task { await newSession() } } label: {
        Text("New sandbox session").font(.system(size: 14, weight: .semibold)).foregroundColor(Color(hex: 0x0B0C14))
          .frame(maxWidth: .infinity).frame(height: 44)
          .background(Capsule().fill(Color.white))
          .overlay(Capsule().strokeBorder(Color(hex: 0xE1E3EC), lineWidth: 1))
      }
      .accessibilityIdentifier("new-session")
    }
    .padding(14)
    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Color(hex: 0xE1E3EC), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
  }

  private func newSession() async {
    guard let demo = setting("VezmoDemoURL").flatMap(URL.init(string:)) else {
      status = "Paste a client token, or set VEZMO_DEMO_URL."
      return
    }
    status = "Creating a sandbox session…"
    var request = URLRequest(url: demo)
    request.httpMethod = "POST"
    do {
      let (data, _) = try await URLSession.shared.data(for: request)
      let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
      token = (json?["data"] as? [String: Any])?["clientToken"] as? String ?? ""
      status = token.isEmpty ? "Could not create a session." : "Session ready."
    } catch {
      status = "Could not reach the demo endpoint."
    }
  }

  private func pay() async {
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
    case .succeeded(let paymentId, let transactionId):
      return #"{"status":"succeeded","paymentId":"\#(paymentId)","transactionId":"\#(transactionId ?? "")"}"#
    case .processing(let paymentId):
      return #"{"status":"processing","paymentId":"\#(paymentId)"}"#
    case .canceled:
      return #"{"status":"canceled"}"#
    case .failed(let error):
      return #"{"status":"failed","error":{"code":"\#(error.code.rawValue)","message":"\#(error.message)"}}"#
    }
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
