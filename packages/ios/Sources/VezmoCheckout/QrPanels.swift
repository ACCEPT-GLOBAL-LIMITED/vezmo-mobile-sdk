import SwiftUI
import UIKit

/// Who pays, for UPI or Pix: name and address (and the CPF or CNPJ for Pix), as the web checkout asks. The sheet's
/// pinned button checks it and starts the payment.
@MainActor
final class QrFormModel: ObservableObject {
  @Published var name = ""
  @Published var line1 = ""
  @Published var city = ""
  @Published var postalCode = ""
  @Published var taxId = ""
  /// The buyer pressed Pay once: problems show from now on.
  @Published var tried = false
  private var seeded = false

  func seed(name: String) {
    guard !seeded else { return }
    seeded = true
    if self.name.isEmpty { self.name = name }
  }

  func problem(_ method: QrMethod) -> String? {
    let digits = taxId.filter { $0.isASCII && $0.isNumber }.count
    if name.trimmed.isEmpty { return "Enter your name." }
    if line1.trimmed.isEmpty { return "Enter your address." }
    if method == .pix && digits != 11 && digits != 14 { return "Enter your CPF (11 digits) or CNPJ (14 digits)." }
    return nil
  }

  /// The details to pay with, or nil (and the problem shown) when something is missing.
  func billing(_ method: QrMethod, country: String?) -> QrBilling? {
    tried = true
    guard problem(method) == nil else { return nil }
    return QrBilling(
      name: name.trimmed,
      line1: line1.trimmed,
      city: city.trimmed.nonEmpty,
      postalCode: postalCode.trimmed.nonEmpty,
      country: country,
      taxId: method == .pix ? taxId.trimmed : nil
    )
  }
}

private extension String {
  var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
  var nonEmpty: String? { isEmpty ? nil : self }
}

struct QrFormView: View {
  @ObservedObject var model: QrFormModel
  let method: QrMethod
  let busy: Bool
  /// The last try failed.
  let error: String?
  let look: Look

  var body: some View {
    let shown = (model.tried ? model.problem(method) : nil) ?? error
    VStack(alignment: .leading, spacing: 14) {
      field("Full name", $model.name, placeholder: "First and last name", type: .name)
      field("Address", $model.line1, placeholder: "Street and number", type: .fullStreetAddress)
      HStack(alignment: .top, spacing: 10) {
        field("City", $model.city, placeholder: "City", type: .addressCity)
        field("Postal code", $model.postalCode, placeholder: method == .upi ? "PIN code" : "CEP", type: .postalCode, keyboard: .numberPad)
      }
      if method == .pix {
        field("CPF or CNPJ", $model.taxId, placeholder: "000.000.000-00", type: nil, keyboard: .numberPad)
      }
      if let shown { Banner(text: shown, look: look) }
    }
  }

  private func field(
    _ label: String,
    _ text: Binding<String>,
    placeholder: String,
    type: UITextContentType?,
    keyboard: UIKeyboardType = .default
  ) -> some View {
    let p = look.p
    let shape = RoundedRectangle(cornerRadius: look.radius, style: .continuous)
    return VStack(alignment: .leading, spacing: 6) {
      Text(label).label(look)
      TextField("", text: text, prompt: Text(placeholder).foregroundColor(look.c(p.onDisabled)))
        .font(look.font(16))
        .foregroundColor(look.c(p.ink))
        .textContentType(type)
        .keyboardType(keyboard)
        .disabled(busy)
        .padding(.horizontal, 14)
        .frame(height: 50)
        .background(shape.fill(look.c(p.field)))
        .overlay(shape.strokeBorder(look.c(p.fieldLine), lineWidth: 1))
        .accessibilityLabel(label)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}

/// The buyer pays in their bank app; we open it (UPI), show the code (Pix), and check when they come back.
struct QrWaiting: View {
  let session: CheckoutSession
  let method: QrMethod
  let qr: QrPayment
  let amount: String
  let look: Look
  @State private var copied = false

  private var steps: [String] {
    method == .pix
      ? ["Tap Copy Pix code.", "In your bank app, choose Pix Copia e Cola and paste it.", "Confirm there. This screen updates by itself."]
      : ["Approve the \(amount) request in your UPI app.", "Come back here. This screen updates by itself."]
  }

  var body: some View {
    let p = look.p
    VStack(alignment: .leading, spacing: 20) {
      TimelineView(.periodic(from: Date(), by: 1)) { context in
        status(at: context.date)
      }

      if method == .pix, let code = qr.code {
        VStack(spacing: 10) {
          VStack(alignment: .leading, spacing: 4) {
            Text("Pix code").label(look)
            Text(code).font(.custom("Menlo", fixedSize: 13)).foregroundColor(look.c(p.ink2))
              .lineLimit(1).truncationMode(.middle)
              .textSelection(.enabled)
          }
          .padding(.horizontal, 14)
          .padding(.vertical, 12)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(RoundedRectangle(cornerRadius: look.radius, style: .continuous).fill(look.c(p.surface2)))
          PrimaryButton(label: copied ? "Copied" : "Copy Pix code", look: look) {
            UIPasteboard.general.string = code
            copied = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
          }
        }
      }
      if method == .upi, let link = qr.link {
        PrimaryButton(label: "Open UPI app", look: look) { open(link) }
      }

      VStack(alignment: .leading, spacing: 10) {
        ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
          HStack(alignment: .top, spacing: 12) {
            Text("\(i + 1)").font(look.font(12, .bold)).foregroundColor(look.c(p.accent))
              .frame(width: 24, height: 24)
              .background(Circle().fill(look.c(p.accentSoft)))
            Text(step).font(look.font(14)).foregroundColor(look.c(p.ink2)).lineSpacing(3)
              .padding(.top, 2)
              .fixedSize(horizontal: false, vertical: true)
          }
        }
      }

      if let png = qr.qr?.png {
        HStack(spacing: 16) {
          QrImage(source: png)
            .frame(width: 100, height: 100)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(look.c(p.fieldLine), lineWidth: 1))
            .accessibilityLabel("\(method == .upi ? "UPI" : "Pix") QR code")
          Text("Paying from another phone? Scan this code in its \(method == .upi ? "UPI" : "bank") app.")
            .font(look.font(13.5)).foregroundColor(look.c(p.ink3)).lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
        }
      }

      VStack(spacing: 14) {
        Button { Task { await session.checkNow() } } label: {
          Text("Check status").font(look.font(15, .semibold)).foregroundColor(look.c(p.ink))
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Capsule().fill(look.c(p.surface2)))
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
        Button { session.stopWaiting() } label: {
          Text("Use another way to pay").font(look.font(14, .semibold)).foregroundColor(look.c(p.accent))
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
      }
    }
    .onAppear { if method == .upi, let link = qr.link { open(link) } }
    .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
      Task { await session.checkNow() }
    }
  }

  /// "Waiting for your payment · 9:41 left"; the last minute turns amber.
  private func status(at now: Date) -> some View {
    let p = look.p
    let left = qr.qr?.expiresAt.map { max(0, Int(($0 - now.timeIntervalSince1970).rounded())) }
    let hurry = left.map { $0 < 60 } ?? false
    let text = left == 0
      ? "Code expired"
      : "Waiting for your payment" + (left.map { " · \($0 / 60):\(String(format: "%02d", $0 % 60)) left" } ?? "")
    let ink = look.c(hurry ? p.warnInk : p.accent)
    return HStack(spacing: 8) {
      PulseDot(color: ink)
      Text(text).font(look.font(13.5, .semibold).monospacedDigit()).foregroundColor(ink)
    }
    .padding(.horizontal, 12)
    .frame(height: 30)
    .background(Capsule().fill(look.c(hurry ? p.warnSoft : p.accentSoft)))
    .accessibilityElement(children: .combine)
  }

  private func open(_ link: String) {
    guard let url = URL(string: link) else { return }
    UIApplication.shared.open(url)
  }
}

/// A QR code from the API: a PNG data URI, or a link to one.
private struct QrImage: View {
  let source: String

  var body: some View {
    if source.hasPrefix("data:"), let comma = source.firstIndex(of: ","),
       let data = Data(base64Encoded: String(source[source.index(after: comma)...])),
       let image = UIImage(data: data) {
      Image(uiImage: image).resizable().interpolation(.none)
    } else if let url = URL(string: source) {
      AsyncImage(url: url) { image in image.resizable().interpolation(.none) } placeholder: { Color.clear }
    }
  }
}
