import SwiftUI
import UIKit

/// What the merchant set for this sheet.
struct SheetProps {
  var merchantDisplayName: String?
  var returnURL: String?
  var style: VezmoCheckout.Style
  var appearance: VezmoCheckout.Appearance?
  var applePay: VezmoCheckout.ApplePay?
}

/// The phone's light or dark setting, from UIKit: known before the sheet is on screen (SwiftUI's own reading can start
/// as light and turn dark a moment later, which would build the engine twice).
@MainActor
final class SystemStyle: ObservableObject {
  @Published var dark: Bool
  init(dark: Bool) { self.dark = dark }
}

/// The sheet's own full-screen layer: our dimmed backdrop and the bottom card, drawn in SwiftUI, with the engine's
/// views hosted inside. Presented over the app's screen (works on iOS 15, no detents needed).
@MainActor
final class SheetController: UIViewController {
  private let session: CheckoutSession
  private let cardFlow = CardFlowHost()
  private let element = MethodsHost()
  private let qrForm = QrFormModel()
  private let system: SystemStyle
  private let props: SheetProps
  private var onClose: ((CheckoutResult) -> Void)?

  init(
    session: CheckoutSession,
    merchantDisplayName: String?,
    configuration: VezmoCheckout.Configuration,
    dark: Bool,
    onClose: @escaping (CheckoutResult) -> Void
  ) {
    self.session = session
    self.system = SystemStyle(dark: dark)
    self.props = SheetProps(
      merchantDisplayName: merchantDisplayName,
      returnURL: configuration.returnURL?.absoluteString,
      style: configuration.style,
      appearance: configuration.appearance,
      applePay: configuration.applePay
    )
    self.onClose = onClose
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .overFullScreen
    modalTransitionStyle = .crossDissolve
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .clear
    view.accessibilityViewIsModal = true
    cardFlow.session = session
    cardFlow.presenter = self
    element.session = session
    element.presenter = self
    session.attachElement(element)
    session.attachCardFlow(cardFlow)

    let root = SheetView(session: session, cardFlow: cardFlow, element: element, qrForm: qrForm, system: system, props: props) { [weak self] result in
      self?.finish(result)
    }
    let host = UIHostingController(rootView: root)
    host.view.backgroundColor = .clear
    addChild(host)
    host.view.frame = view.bounds
    host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    view.addSubview(host.view)
    host.didMove(toParent: self)
  }

  override func traitCollectionDidChange(_ previous: UITraitCollection?) {
    super.traitCollectionDidChange(previous)
    // The phone switched between light and dark while the sheet is open.
    guard view.window != nil, traitCollection.userInterfaceStyle != .unspecified else { return }
    let dark = traitCollection.userInterfaceStyle == .dark
    if system.dark != dark { system.dark = dark }
  }

  /// The sheet is finished: the buyer closed it, or tapped Done on the result.
  private func finish(_ result: CheckoutResult) {
    guard let onClose else { return }
    self.onClose = nil
    session.attachElement(nil)
    session.attachCardFlow(nil)
    session.dispose()
    cardFlow.teardown()
    element.teardown()
    guard presentingViewController != nil else { return onClose(result) }
    dismiss(animated: true) { onClose(result) }
  }
}

private struct HeightKey: PreferenceKey {
  static var defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct WalletsHeightKey: PreferenceKey {
  static var defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct ScrollFrameKey: PreferenceKey {
  static var defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// What the engine is built from; a change rebuilds or updates it.
private struct EngineInputs: Equatable {
  var settings: EngineSettings
  var intent: EngineIntent
}

extension View {
  fileprivate func measure<K: PreferenceKey>(_ key: K.Type) -> some View where K.Value == CGFloat {
    background(GeometryReader { Color.clear.preference(key: key, value: $0.size.height) })
  }
}

/// The Vezmo payment sheet: the business and the total, the buyer's currency, one list of ways to pay (the engine's
/// rows and our own UPI or Pix), our Pay button and our result screens.
struct SheetView: View {
  @ObservedObject var session: CheckoutSession
  @ObservedObject var cardFlow: CardFlowHost
  @ObservedObject var element: MethodsHost
  @ObservedObject var qrForm: QrFormModel
  @ObservedObject var system: SystemStyle
  let props: SheetProps
  let close: (CheckoutResult) -> Void

  @Environment(\.pixelLength) private var pixel
  @Environment(\.sizeCategory) private var sizeCategory
  @State private var entered = false
  @State private var walletsHeight: CGFloat = 0
  @State private var contentHeight: CGFloat = 0
  @State private var scrollHeight: CGFloat = 0
  /// Bumped to bring what went wrong into view (it shows under the form or the list, maybe below the fold).
  @State private var revealRequest = 0

  /// Room for the home indicator under the sheet.
  // ponytail: fixed inset as in the React Native sheet; read the real one if a merchant app needs it to the point.
  private let bottomInset: CGFloat = 30

  private var snap: SessionSnapshot { session.snapshot }

  private var dark: Bool {
    switch props.style {
    case .light: return false
    case .dark: return true
    case .automatic:
      switch snap.config?.theme {
      case "light": return false
      case "dark": return true
      default: return system.dark
      }
    }
  }

  private var look: Look {
    Look(
      p: Palette.resolve(dark: dark, appearance: props.appearance),
      radius: props.appearance?.cornerRadius ?? defaultRadius,
      fontFamily: props.appearance?.fontFamily,
      dark: dark,
      hairline: pixel,
      textScale: Look.textScale(sizeCategory)
    )
  }

  private var merchant: String { props.merchantDisplayName ?? snap.config?.merchantName ?? "" }

  private var amount: String { snap.charge.map { formatAmount($0.amountMinor, $0.currency) } ?? "" }

  private var inputs: EngineInputs? {
    guard let config = snap.config, let charge = snap.charge, let methods = snap.methods else { return nil }
    let look = self.look
    return EngineInputs(
      settings: EngineSettings(
        publishableKey: config.publishableKey,
        account: config.connectedAccountId,
        merchantDisplayName: merchant,
        returnURL: props.returnURL,
        dark: dark,
        palette: look.p,
        radius: look.radius,
        fontFamily: look.fontFamily,
        applePay: methods.applePay ? props.applePay : nil,
        applePayRecurring: ApplePayRecurring(config: config, charge: charge, merchant: merchant),
        billingName: config.customerName,
        billingEmail: config.customerEmail,
        attempt: snap.elementAttempt
      ),
      intent: EngineIntent(charge: charge, methods: methods, plan: config.subscription)
    )
  }

  private func push(_ inputs: EngineInputs?) {
    guard let inputs else { return }
    cardFlow.configure(inputs.settings, inputs.intent)
    element.configure(inputs.settings, inputs.intent)
  }

  var body: some View {
    let look = self.look
    GeometryReader { geo in
      ZStack(alignment: .bottom) {
        Color(look.p.scrim).opacity(entered ? 1 : 0).ignoresSafeArea().accessibilityHidden(true)
        sheet(look)
          .frame(maxHeight: geo.size.height * 0.92, alignment: .bottom)
          .offset(y: entered ? 0 : 80)
      }
      .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
    }
    .ignoresSafeArea(.container, edges: [.top, .bottom])
    .onAppear {
      push(inputs)
      withAnimation(.timingCurve(0.33, 1, 0.68, 1, duration: 0.32)) { entered = true }
    }
    .onChange(of: inputs) { push($0) }
  }

  // MARK: The card

  private var finished: Bool {
    switch snap.phase {
    case .done, .closed: return true
    default: return false
    }
  }

  private var paying: Bool { snap.phase == .paying }

  /// The last try's problem, while the sheet is ready for another.
  private var phaseError: String? {
    if case .ready(let error) = snap.phase { return error?.message }
    return nil
  }

  @ViewBuilder private func sheet(_ look: Look) -> some View {
    let p = look.p
    VStack(spacing: 0) {
      RoundedRectangle(cornerRadius: 3).fill(look.c(p.fieldLine)).frame(width: 36, height: 5).padding(.top, 8)
      switch snap.phase {
      case .loading:
        SheetSkeleton(look: look)
      case .done(let result), .closed(let result):
        ResultView(
          result: result,
          amount: amount,
          merchant: merchant,
          paidWith: snap.paidWith,
          paidMethod: snap.paidMethod,
          plan: snap.config?.subscription,
          look: look
        ) { close(result) }
      default:
        if let config = snap.config, let methods = snap.methods {
          checkout(config, methods, look)
        }
      }
    }
    .padding(.bottom, bottomInset)
    .frame(maxWidth: .infinity)
    .background(alignment: .top) {
      ZStack(alignment: .top) {
        look.c(p.surface)
        // A faint wash of the brand behind the business and the total.
        if !finished {
          LinearGradient(colors: [look.c(p.accentSoft), look.c(p.accentSoft).opacity(0)], startPoint: .top, endPoint: .bottom)
            .frame(height: 160)
            .opacity(0.6)
        }
      }
    }
    .clipShape(TopRounded(radius: 28))
    .background(TopRounded(radius: 28).fill(look.c(p.surface)).shadow(color: Color(rgba(11, 12, 20, 0.18)), radius: 20, x: 0, y: -10))
    .overlay(alignment: .topTrailing) {
      // The result has its own way out.
      if !finished { closeButton(look) }
    }
    .opacity(snap.phase == .loading || finished || contentHeight > 0 ? 1 : 0)
  }

  private func closeButton(_ look: Look) -> some View {
    Button { close(.canceled) } label: {
      Cross(color: look.c(look.p.ink2), size: 11)
        .frame(width: 30, height: 30)
        .background(Circle().fill(look.c(look.p.fill)))
        .padding(12)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .disabled(paying)
    .opacity(paying ? 0.4 : 1)
    .accessibilityLabel("Close")
    .padding(.top, 33 - 12)
    .padding(.trailing, 16 - 12)
  }

  // MARK: Ready, paying, waiting

  @ViewBuilder private func checkout(_ config: CheckoutConfig, _ methods: SheetMethods, _ look: Look) -> some View {
    ScrollViewReader { proxy in
      ScrollView(.vertical, showsIndicators: true) {
        VStack(alignment: .leading, spacing: 0) {
          header(config, look)
          content(config, methods, look)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .measure(HeightKey.self)
        .id(Self.contentID)
      }
      .measure(ScrollFrameKey.self)
      .frame(maxHeight: contentHeight)
      .modifier(NoBounceWhenFits())
      .onPreferenceChange(HeightKey.self) { contentHeight = $0 }
      .onPreferenceChange(ScrollFrameKey.self) { scrollHeight = $0 }
      .onChange(of: phaseError) { if $0 != nil { revealEnd(proxy) } }
      .onChange(of: revealRequest) { _ in revealEnd(proxy) }
    }

    dock(look)
  }

  private static let contentID = "vezmo-content"

  /// Scrolls to the end of the content (where a problem or a failed try shows), once this update is laid out.
  private func revealEnd(_ proxy: ScrollViewProxy) {
    DispatchQueue.main.async {
      withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(Self.contentID, anchor: .bottom) }
    }
  }

  private func header(_ config: CheckoutConfig, _ look: Look) -> some View {
    let p = look.p
    let initial = merchant.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "•"
    return VStack(alignment: .leading, spacing: 18) {
      HStack(spacing: 12) {
        Text(initial).font(look.font(17, .bold)).foregroundColor(look.c(p.accent))
          .frame(width: 42, height: 42)
          .background(
            RoundedRectangle(cornerRadius: 12).fill(look.c(p.logoTile))
              .shadow(color: Color(rgba(16, 24, 64, 0.06)), radius: 1, x: 0, y: 1)
          )
          .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(look.c(p.fieldLine), lineWidth: look.hairline))
          .accessibilityHidden(true)
        VStack(alignment: .leading, spacing: 1) {
          HStack(spacing: 8) {
            Text(merchant).font(look.font(15, .semibold)).foregroundColor(look.c(p.ink)).lineLimit(1)
            if !config.livemode {
              Text("TEST MODE").font(look.font(10, .bold)).tracking(0.6).foregroundColor(.white)
                .padding(.horizontal, 7)
                .frame(minHeight: 20)
                .background(Capsule(style: .circular).fill(look.c(p.warn)))
            }
          }
          if let title = config.title, !title.isEmpty {
            Text(title).font(look.font(13)).foregroundColor(look.c(p.ink3)).lineLimit(1)
          }
        }
        Spacer(minLength: 0)
      }
      .padding(.trailing, 44)
      VStack(alignment: .leading, spacing: 2) {
        Text(config.subscription != nil ? "Due today" : "Total").label(look)
        Text(amount).font(look.font(34, .bold).monospacedDigit()).tracking(-0.9).foregroundColor(look.c(p.ink))
      }
      if let plan = config.subscription { PlanSummary(plan: plan, look: look) }
    }
    .padding(.horizontal, 20)
    .padding(.top, 14)
  }

  @ViewBuilder private func content(_ config: CheckoutConfig, _ methods: SheetMethods, _ look: Look) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      if case .waiting(let method, let qr) = snap.phase {
        QrWaiting(session: session, method: method, qr: qr, amount: amount, look: look)
      } else {
        if let offer = config.localOffer { payIn(config, offer, look) }
        wallets(config, look)
        ways(config, methods, look)
          .padding(.top, config.localOffer != nil || walletsHeight > 1 ? 24 : 0)
        if let qr = snap.method.qr {
          QrFormView(
            model: qrForm,
            method: qr,
            busy: paying,
            error: phaseError,
            look: look
          )
          .padding(.top, 24)
          .onAppear { qrForm.seed(name: config.customerName ?? "") }
        } else if case .ready(let error?) = snap.phase {
          Banner(text: error.message, look: look).padding(.top, 24)
        }
      }
    }
    .padding(.horizontal, 20)
    .padding(.top, 22)
    .padding(.bottom, 16)
  }

  private func payIn(_ config: CheckoutConfig, _ offer: LocalOffer, _ look: Look) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("Pay in").label(look)
      Segmented(
        items: [
          .init(id: "local", label: formatAmount(offer.amountMinor, offer.currency), sub: offer.currency.uppercased()),
          .init(id: "price", label: formatAmount(config.amountMinor, config.currency), sub: config.currency.uppercased()),
        ],
        selected: snap.payLocal ? "local" : "price",
        disabled: paying,
        look: look
      ) { session.choosePayLocal($0 == "local") }
      if snap.payLocal {
        Text("1 \(config.currency.uppercased()) = \(String(format: "%.4f", offer.rate)) \(offer.currency.uppercased())")
          .font(look.font(12.5)).foregroundColor(look.c(look.p.ink3))
      }
    }
  }

  /// Express: the official Apple Pay button, when this phone and the business have it.
  @ViewBuilder private func wallets(_ config: CheckoutConfig, _ look: Look) -> some View {
    if let flow = cardFlow.walletFlow {
      if #available(iOS 16.0, *) {
        VStack(spacing: 8) {
          WalletButtons(host: cardFlow, flow: flow)
            .measure(WalletsHeightKey.self)
          if walletsHeight > 1 {
            HStack(spacing: 12) {
              Rectangle().fill(look.c(look.p.fieldLine)).frame(height: look.hairline)
              Text("or pay another way").font(look.font(13, .medium)).foregroundColor(look.c(look.p.ink3)).fixedSize()
              Rectangle().fill(look.c(look.p.fieldLine)).frame(height: look.hairline)
            }
            .padding(.top, 6)
          }
        }
        .padding(.top, config.localOffer != nil && walletsHeight > 1 ? 24 : 0)
        .onPreferenceChange(WalletsHeightKey.self) { walletsHeight = $0 }
      }
    }
  }

  /// The ways to pay: one rounded group (our Card row, the engine's rows, then our own UPI or Pix row).
  private func ways(_ config: CheckoutConfig, _ methods: SheetMethods, _ look: Look) -> some View {
    let p = look.p
    let error = snap.elementError != nil
    let loading = !(cardFlow.loaded && element.loaded) && !error
    let cardOn = methods.elementTypes.contains("card")
    // Our own UPI or Pix row, when paying in rupees or reais.
    let own: (method: PayMethod, mark: Mark)? = methods.upi ? (.upi, .upi) : methods.pix ? (.pix, .pix) : nil
    return VStack(alignment: .leading, spacing: 8) {
      HStack(spacing: 12) {
        Text("Payment method").label(look)
        Spacer(minLength: 0)
        // The card networks, as their real marks (the card row itself shows a plain card).
        HStack(spacing: 4) {
          BrandTile(mark: .visa, look: look)
          BrandTile(mark: .mastercard, look: look)
          BrandTile(mark: .amex, look: look)
          BrandTile(mark: .discover, look: look)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Visa, Mastercard, American Express and Discover accepted")
      }
      VStack(spacing: 0) {
        if loading {
          RowsSkeleton(look: look, rows: max(1, methods.elementTypes.count))
        }
        if cardOn && !loading && !error {
          CardRow(
            card: snap.card,
            selected: snap.method == .card,
            disabled: paying,
            look: look,
            onPress: { session.chooseCard() },
            onChange: { session.changeCard() }
          )
        }
        if element.element != nil && !loading && !error {
          VStack(spacing: 0) {
            if cardOn { Rectangle().fill(look.c(p.line)).frame(height: look.hairline) }
            ElementView(host: element).frame(height: max(element.height, 1))
          }
        }
        if error {
          VStack(spacing: 4) {
            Text("Couldn’t load payment options").font(look.font(15, .semibold)).foregroundColor(look.c(p.ink))
              .accessibilityIdentifier("vezmo-element-error")
            Text("Check your connection and try again.").font(look.font(13)).foregroundColor(look.c(p.ink3))
          }
          .multilineTextAlignment(.center)
          .frame(maxWidth: .infinity)
          .padding(.vertical, 22)
        }
        if let own {
          MethodRow(
            mark: own.mark,
            title: own.method == .upi ? "UPI" : "Pix",
            sub: own.method == .upi ? "Pay from your UPI app" : "Instant transfer from your bank app",
            selected: snap.method == own.method,
            disabled: paying,
            look: look
          ) { session.chooseMethod(own.method) }
        }
      }
      .padding(.horizontal, 14)
      .methodGroup(look)
    }
  }

  /// The main button's words: what it pays for, or what it starts (a subscription).
  private var payLabel: String {
    guard let plan = snap.config?.subscription else { return "Pay \(amount)" }
    return plan.trial ? "Start free trial" : "Subscribe · \(amount)"
  }

  /// The pinned bottom: the main button and "Secured by", always in reach however long the content above.
  private func dock(_ look: Look) -> some View {
    VStack(spacing: 14) {
      if !snap.phase.isWaiting {
        if let qr = snap.method.qr {
          PrimaryButton(
            label: qr == .upi ? "Pay \(amount) with UPI" : "Get Pix code for \(amount)",
            look: look,
            busy: paying,
            lock: true
          ) {
            guard let config = snap.config, let billing = qrForm.billing(qr, country: config.localOffer?.country) else {
              revealRequest += 1
              return
            }
            Task { await session.payWithQr(qr, billing: billing) }
          }
        } else if snap.elementError != nil {
          PrimaryButton(label: "Try again", look: look) { session.retryElement() }
            .accessibilityIdentifier("vezmo-retry")
        } else {
          PrimaryButton(
            label: payLabel,
            look: look,
            disabled: !CheckoutSession.canPay(snap),
            busy: paying,
            lock: true
          ) { session.pay() }
            .accessibilityIdentifier("vezmo-pay")
        }
      }
      // The recurring authorization, small, right above the button that gives it.
      if let plan = snap.config?.subscription, !snap.phase.isWaiting {
        Text(plan.consentText).font(look.font(12)).foregroundColor(look.c(look.p.ink3)).lineSpacing(1.7)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)
          .frame(maxWidth: .infinity)
      }
      SecuredBy(look: look)
    }
    .padding(.horizontal, 20)
    .padding(.top, 12)
    .background(look.c(look.p.surface))
    .overlay(alignment: .top) {
      // A hairline over the pinned bottom only while content scrolls under it.
      if contentHeight > scrollHeight + 1 {
        Rectangle().fill(look.c(look.p.line)).frame(height: look.hairline)
      }
    }
  }
}

/// No rubber band when everything fits (iOS 16.4 and later).
private struct NoBounceWhenFits: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 16.4, *) {
      content.scrollBounceBehavior(.basedOnSize)
    } else {
      content
    }
  }
}

// MARK: - Result

struct ResultView: View {
  let result: CheckoutResult
  let amount: String
  let merchant: String
  let paidWith: String?
  let paidMethod: PaidMethod?
  /// A subscription checkout's plan.
  let plan: SubscriptionPlan?
  let look: Look
  let onDone: () -> Void

  /// The subscription the checkout started, if it was one.
  private var subscription: SubscriptionResult? {
    switch result {
    case .succeeded(_, _, let s), .processing(_, let s): return s
    default: return nil
    }
  }

  private var trialing: Bool { subscription?.status == .trialing }

  private struct Copy {
    let title: String
    let body: String
    let kind: MarkKind
    let action: String
  }

  private var copy: Copy {
    let next = dateText(plan?.nextChargeAt)
    switch result {
    case .succeeded where trialing:
      return Copy(title: "Your free trial has started", body: next.isEmpty ? "" : "Nothing is charged until \(next).", kind: .good, action: "Done")
    case .succeeded where subscription != nil:
      return Copy(title: "You’re subscribed", body: "", kind: .good, action: "Done")
    case .succeeded:
      return Copy(title: "Payment successful", body: "", kind: .good, action: "Done")
    case .processing:
      return Copy(
        title: subscription != nil ? "Subscription received" : "Payment received",
        body: "Your payment is on its way. You’ll get a confirmation when it clears.",
        kind: .wait,
        action: "Done"
      )
    case .failed(let error) where error.code == .expired:
      return Copy(title: "Checkout expired", body: "Return to \(merchant.isEmpty ? "the app" : merchant) to start again.", kind: .neutral, action: "Close")
    case .failed(let error) where error.code == .alreadyPaid:
      return Copy(title: "Already paid", body: error.message, kind: .good, action: "Done")
    case .failed(let error):
      return Copy(title: "Payment not completed", body: error.message, kind: .bad, action: "Close")
    case .canceled:
      return Copy(title: "Payment canceled", body: "", kind: .neutral, action: "Close")
    }
  }

  private var paymentId: String {
    switch result {
    case .succeeded(let id, _, _), .processing(let id, _): return id ?? ""
    default: return ""
    }
  }

  /// Receipt rows: Paid to, Paid with (the network's mark and last four from Vezmo's server, else what the buyer
  /// chose, or UPI / Pix), and the reference the buyer can quote: the subscription's, else the transaction's, else the
  /// payment's own id. A subscription's receipt shows the plan and its next charge instead of who was paid.
  private var rows: [(key: String, value: String, mark: Mark?)] {
    var reference = paymentId
    if case .succeeded(_, let transactionId?, _) = result, !transactionId.isEmpty { reference = transactionId }
    if let ref = subscription?.ref, !ref.isEmpty { reference = ref }
    let mark = Mark.forBrand(paidMethod?.brand) ?? (paidWith == "UPI" ? .upi : paidWith == "Pix" ? .pix : nil)
    let paidText: String
    if let last4 = paidMethod?.last4, !last4.isEmpty {
      paidText = "•••• \(last4)"
    } else {
      paidText = mark == .upi || mark == .pix ? "" : (paidWith ?? "")
    }
    let paid = paidText.isEmpty ? (mark?.rawValue ?? "") : paidText
    var rows: [(key: String, value: String, mark: Mark?)] = []
    if subscription != nil {
      let planText: String = plan.map { "\($0.planName) · \(planPriceText($0))" } ?? ""
      rows.append(("Plan", planText, nil))
      rows.append((trialing ? "First charge" : "Next charge", dateText(plan?.nextChargeAt), nil))
      rows.append((trialing ? "Card" : "Paid with", paid, mark))
    } else {
      rows.append(("Paid to", merchant, nil))
      rows.append(("Paid with", paid, mark))
    }
    rows.append(("Reference", reference, nil))
    return rows.filter { !$0.value.isEmpty }
  }

  var body: some View {
    let p = look.p
    let copy = self.copy
    VStack(spacing: 6) {
      ResultMark(kind: copy.kind, p: p)
      Text(copy.title).font(look.font(22, .bold)).tracking(-0.3).foregroundColor(look.c(p.ink))
        .multilineTextAlignment(.center)
        .padding(.top, 14)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("vezmo-result-title")
      if case .succeeded = result, !amount.isEmpty, !trialing {
        Text(amount).font(look.font(34, .bold).monospacedDigit()).tracking(-0.9).foregroundColor(look.c(p.ink))
      }
      if !copy.body.isEmpty {
        Text(copy.body).font(look.font(15)).foregroundColor(look.c(p.ink2)).lineSpacing(3)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)
      }
      if !paymentId.isEmpty || subscription != nil {
        VStack(spacing: 0) {
          ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
            HStack(spacing: 12) {
              Text(row.key).font(look.font(14)).foregroundColor(look.c(p.ink3))
              Spacer(minLength: 0)
              HStack(spacing: 8) {
                if let mark = row.mark { BrandTile(mark: mark, look: look) }
                if !(row.mark != nil && row.value == row.mark?.rawValue) {
                  Text(row.value).font(look.font(14, .medium).monospacedDigit()).foregroundColor(look.c(p.ink))
                    .lineLimit(1).truncationMode(.middle)
                    .textSelection(.enabled)
                }
              }
            }
            .padding(.vertical, 13)
            .overlay(alignment: .top) {
              if i > 0 { Rectangle().fill(look.c(p.line)).frame(height: look.hairline) }
            }
            .accessibilityElement(children: .combine)
          }
        }
        .padding(.horizontal, 16)
        .background(RoundedRectangle(cornerRadius: look.radius + 2).fill(look.c(p.surface2)))
        .padding(.top, 18)
      }
      PrimaryButton(label: copy.action, look: look, ink: true, action: onDone)
        .accessibilityIdentifier("vezmo-done")
        .padding(.top, 24)
    }
    .frame(maxWidth: .infinity)
    .padding(.horizontal, 20)
    .padding(.top, 30)
    .padding(.bottom, 8)
  }
}
