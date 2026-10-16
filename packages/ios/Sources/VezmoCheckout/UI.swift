import SwiftUI
import UIKit

/// The sheet's building blocks: the Pay button, the segmented choice, the grouped method list, status marks and
/// skeletons. One type scale: 12.5 / 13 / 15 / 17 / 22 / 34. Labels: 13 medium, quiet ink, 8 above their control.
struct Look {
  let p: Palette
  let radius: CGFloat
  let fontFamily: String?
  let dark: Bool
  /// One device pixel.
  let hairline: CGFloat

  func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    if let fontFamily { return Font.custom(fontFamily, fixedSize: size).weight(weight) }
    return Font.system(size: size, weight: weight)
  }

  func c(_ color: UIColor) -> Color { Color(color) }

  /// The engine's rows have their own leading margin inside our group; the sheet's own rows copy it so icons and
  /// titles line up (icon at 28pt, title at 70pt from the group's edge).
  static let rowLead: CGFloat = 13
}

// MARK: - Text styles

extension Text {
  func label(_ l: Look) -> some View { font(l.font(13, .medium)).foregroundColor(l.c(l.p.ink2)) }
}

// MARK: - Buttons

/// The Pay button (and Done): Vezmo's gradient, or ink for a quiet finish; neutral until it can be pressed.
struct PrimaryButton: View {
  let label: String
  let look: Look
  var disabled = false
  var busy = false
  var lock = false
  var ink = false
  let action: () -> Void

  var body: some View {
    let p = look.p
    let off = disabled && !busy
    let color = look.c(off ? p.onDisabled : ink ? p.surface : p.onAccent)
    Button(action: action) {
      HStack(spacing: 9) {
        if busy {
          ProgressView().progressViewStyle(CircularProgressViewStyle(tint: color))
          Text("Processing…").font(look.font(17, .semibold).monospacedDigit()).tracking(-0.2).foregroundColor(color)
        } else {
          if lock && !off { LockGlyph(color: color, size: 13) }
          Text(label).font(look.font(17, .semibold).monospacedDigit()).tracking(-0.2).foregroundColor(color)
        }
      }
      .frame(maxWidth: .infinity)
      .frame(height: 56)
      .background(fill(off: off))
      .contentShape(Capsule())
    }
    .buttonStyle(PressStyle())
    .disabled(disabled || busy)
    .accessibilityLabel(busy ? "Processing" : label)
  }

  @ViewBuilder private func fill(off: Bool) -> some View {
    let p = look.p
    if off {
      Capsule().fill(look.c(p.disabled))
    } else if ink {
      Capsule().fill(look.c(p.ink))
    } else {
      ZStack {
        // The lift: 0 1px 2px, and 0 8px 18px -8px (a blurred copy, smaller than the button).
        if let shadow = p.payShadow {
          Capsule().fill(look.c(shadow)).padding(.horizontal, 8).padding(.vertical, 8)
            .offset(y: 8).blur(radius: 9).opacity(p.accentGradient ? 0.45 : 0.3)
        }
        if p.accentGradient {
          Capsule().fill(LinearGradient(stops: Palette.gradient, startPoint: UnitPoint(x: 0, y: -0.054), endPoint: UnitPoint(x: 1, y: 1.054)))
            .shadow(color: look.c(p.payShadow ?? .clear).opacity(0.22), radius: 1, x: 0, y: 1)
        } else {
          Capsule().fill(look.c(p.accent))
            .shadow(color: look.c(p.payShadow ?? .clear).opacity(0.16), radius: 1, x: 0, y: 1)
        }
      }
    }
  }
}

/// Pressed: a touch smaller and lighter.
struct PressStyle: ButtonStyle {
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .scaleEffect(configuration.isPressed ? 0.985 : 1)
      .opacity(configuration.isPressed ? 0.94 : 1)
  }
}

/// Two choices on one track (pay in rupees or dollars).
struct Segmented: View {
  struct Item: Identifiable {
    let id: String
    let label: String
    let sub: String
  }

  let items: [Item]
  let selected: String
  let disabled: Bool
  let look: Look
  let onChange: (String) -> Void

  var body: some View {
    let p = look.p
    HStack(spacing: 3) {
      ForEach(items) { item in
        let on = item.id == selected
        Button { onChange(item.id) } label: {
          (Text(item.label).font(look.font(15, .semibold).monospacedDigit()).foregroundColor(look.c(on ? p.ink : p.ink2))
            + Text("  \(item.sub)").font(look.font(13, .medium)).foregroundColor(look.c(p.ink3)))
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(height: 38)
            .background(
              RoundedRectangle(cornerRadius: look.radius - 3, style: .continuous)
                .fill(look.c(on ? p.raised : .clear))
                .shadow(color: on ? Color(rgba(16, 24, 64, 0.08)) : .clear, radius: 1, x: 0, y: 1)
                .shadow(color: on ? Color(rgba(16, 24, 64, 0.10)) : .clear, radius: 4, x: 0, y: 3)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityAddTraits(on ? .isSelected : [])
      }
    }
    .padding(3)
    .background(RoundedRectangle(cornerRadius: look.radius, style: .continuous).fill(look.c(p.surface2)))
    .accessibilityElement(children: .contain)
    .accessibilityLabel("Pay in")
  }
}

/// A row of the ways-to-pay group: 52 tall at least, a hairline above when it isn't first.
struct RowFrame<Content: View>: View {
  let look: Look
  var line = false
  var lead: CGFloat = 0
  @ViewBuilder let content: () -> Content

  var body: some View {
    HStack(spacing: 12) { content() }
      .padding(.leading, lead)
      .padding(.vertical, 10)
      .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
      .overlay(alignment: .top) {
        if line { Rectangle().fill(look.c(look.p.line)).frame(height: look.hairline) }
      }
  }
}

/// One of the sheet's own methods (UPI, Pix), drawn like the engine's rows so the group reads as one list.
struct MethodRow: View {
  let mark: Mark
  let title: String
  let sub: String
  let selected: Bool
  let disabled: Bool
  let look: Look
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      RowFrame(look: look, line: true, lead: Look.rowLead) {
        BrandTile(mark: mark, large: true, look: look)
        VStack(alignment: .leading, spacing: 1) {
          Text(title).font(look.font(15, .medium)).foregroundColor(look.c(look.p.ink))
          Text(sub).font(look.font(12.5)).foregroundColor(look.c(look.p.ink3))
        }
        Spacer(minLength: 0)
        if selected { Tick(color: look.c(look.p.accent), size: 16) }
      }
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .disabled(disabled)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(selected ? .isSelected : [])
  }
}

/// The Card row, drawn by the sheet (the engine would show a plain card for any card): the network's own logo once a
/// card is typed, its last four, and Change. Tapping opens the native card form.
struct CardRow: View {
  let card: CardChoice?
  let selected: Bool
  let disabled: Bool
  let look: Look
  let onPress: () -> Void
  let onChange: () -> Void

  var body: some View {
    let p = look.p
    RowFrame(look: look, lead: Look.rowLead) {
      Button(action: onPress) {
        HStack(spacing: 12) {
          if let image = card?.image {
            Image(uiImage: image).resizable().aspectRatio(contentMode: .fit).frame(width: 24, height: 16)
              .frame(width: 30, height: 22)
              .background(RoundedRectangle(cornerRadius: 5).fill(look.c(p.field)))
              .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(look.c(p.fieldLine), lineWidth: look.hairline))
          } else {
            CardGlyph(color: look.c(p.ink)).frame(width: 30, height: 22)
          }
          VStack(alignment: .leading, spacing: 1) {
            Text("Card").font(look.font(15, .medium)).foregroundColor(look.c(p.ink))
            if let card { Text(card.label).font(look.font(12.5)).foregroundColor(look.c(p.ink3)) }
          }
          Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .disabled(disabled)
      .accessibilityElement(children: .ignore)
      .accessibilityLabel(card.map { "Card \($0.label)" } ?? "Card")
      .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
      .accessibilityIdentifier("vezmo-card-row")
      if selected && card != nil {
        Button(action: onChange) {
          Text("Change").font(look.font(14, .medium)).foregroundColor(look.c(p.accent))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel("Change card")
        Tick(color: look.c(p.accent), size: 16)
      }
    }
  }
}

/// A payment brand's mark (Visa, Mastercard, UPI…) on its white tile, as on the web checkout.
struct BrandTile: View {
  let mark: Mark
  var large = false
  let look: Look

  var body: some View {
    // Fit the mark inside the tile with a little room, keeping its shape.
    let box = large ? CGSize(width: 25, height: 16) : CGSize(width: 22, height: 13)
    let k = min(box.width / mark.size.width, box.height / mark.size.height, 1)
    Image(uiImage: mark.image).resizable()
      .frame(width: mark.size.width * k, height: mark.size.height * k)
      .frame(width: large ? 34 : 30, height: large ? 24 : 20)
      .background(RoundedRectangle(cornerRadius: 5).fill(Color.white))
      .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(look.c(look.p.fieldLine), lineWidth: 1))
      .accessibilityHidden(true)
  }
}

/// "Secured by" and the Vezmo logo, as under the embedded checkout (white in dark themes).
struct SecuredBy: View {
  let look: Look

  var body: some View {
    HStack(spacing: 6) {
      Text("Secured by").font(look.font(12.5, .medium)).foregroundColor(look.c(look.p.ink3))
      if look.dark {
        Image(uiImage: VezmoLogo.image).renderingMode(.template).resizable()
          .foregroundColor(.white).opacity(0.85)
          .frame(width: VezmoLogo.size.width, height: VezmoLogo.size.height)
      } else {
        Image(uiImage: VezmoLogo.image).resizable()
          .frame(width: VezmoLogo.size.width, height: VezmoLogo.size.height)
      }
    }
    .frame(maxWidth: .infinity)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Secured by Vezmo")
  }
}

/// Something went wrong with the payment, said kindly.
struct Banner: View {
  let text: String
  let look: Look

  var body: some View {
    HStack(spacing: 10) {
      StatusMark(kind: .bad, size: 20, p: look.p)
      Text(text).font(look.font(14)).foregroundColor(look.c(look.p.ink)).lineSpacing(2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }
    .padding(.vertical, 11)
    .padding(.horizontal, 12)
    .background(RoundedRectangle(cornerRadius: look.radius, style: .continuous).fill(look.c(look.p.badSoft)))
    .accessibilityElement(children: .combine)
  }
}

// MARK: - Glyphs (drawn, so the app bundles nothing)

/// A plain card: the Card row's icon before a card is typed.
struct CardGlyph: View {
  let color: Color

  var body: some View {
    ZStack(alignment: .top) {
      RoundedRectangle(cornerRadius: 3).strokeBorder(color, lineWidth: 1.6)
      Rectangle().fill(color).frame(height: 3).padding(.top, 4.6).padding(.horizontal, 1.6)
    }
    .frame(width: 22, height: 16)
    .accessibilityHidden(true)
  }
}

/// A padlock: body and shackle.
struct LockGlyph: View {
  let color: Color
  let size: CGFloat

  var body: some View {
    let w = size * 0.8
    VStack(spacing: -size * 0.02) {
      Shackle(lineWidth: size * 0.13).stroke(color, lineWidth: size * 0.13).frame(width: w * 0.62, height: size * 0.5)
      RoundedRectangle(cornerRadius: size * 0.14).fill(color).frame(width: w, height: size * 0.52)
    }
    .frame(width: w, height: size)
    .accessibilityHidden(true)
  }

  private struct Shackle: Shape {
    let lineWidth: CGFloat

    func path(in rect: CGRect) -> Path {
      // The stroke's centre line, inside the box: up the left side, over the top in a half circle, down the right
      // side and on under the body.
      let box = rect.insetBy(dx: lineWidth / 2, dy: lineWidth / 2)
      let r = box.width / 2
      var path = Path()
      path.move(to: CGPoint(x: box.minX, y: rect.maxY + 1))
      path.addLine(to: CGPoint(x: box.minX, y: box.minY + r))
      path.addArc(center: CGPoint(x: box.midX, y: box.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
      path.addLine(to: CGPoint(x: box.maxX, y: rect.maxY + 1))
      return path
    }
  }
}

/// A close cross.
struct Cross: View {
  let color: Color
  let size: CGFloat

  var body: some View {
    // Two bars `size` long, turned 45° about the centre.
    let r: CGFloat = size / 2 * 0.7071
    let c: CGFloat = size / 2
    Path { path in
      path.move(to: CGPoint(x: c - r, y: c - r))
      path.addLine(to: CGPoint(x: c + r, y: c + r))
      path.move(to: CGPoint(x: c + r, y: c - r))
      path.addLine(to: CGPoint(x: c - r, y: c + r))
    }
    .stroke(color, style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
    .frame(width: size, height: size)
    .accessibilityHidden(true)
  }
}

/// A checkmark stroke: the short and long arms of an L turned 45°.
struct Tick: View {
  let color: Color
  let size: CGFloat
  var weight: CGFloat?

  var body: some View {
    let w: CGFloat = weight ?? max(2, size * 0.14)
    Path { path in path.addLines(Tick.points(size: size, weight: w)) }
      .stroke(color, style: StrokeStyle(lineWidth: w, lineCap: .butt, lineJoin: .miter))
      .frame(width: size, height: size)
      .accessibilityHidden(true)
  }

  /// The L's corner points (inside its border), turned 45° about its centre and lifted a little.
  static func points(size: CGFloat, weight w: CGFloat) -> [CGPoint] {
    let a: CGFloat = size * 0.36
    let b: CGFloat = size * 0.68
    let corners: [CGPoint] = [
      CGPoint(x: -a / 2, y: b / 2 - w / 2),
      CGPoint(x: a / 2 - w / 2, y: b / 2 - w / 2),
      CGPoint(x: a / 2 - w / 2, y: -b / 2),
    ]
    let k: CGFloat = 0.7071
    let lift: CGFloat = size * 0.08
    return corners.map { (p: CGPoint) -> CGPoint in
      let x: CGFloat = (p.x - p.y) * k + size / 2
      let y: CGFloat = (p.x + p.y) * k + size / 2 - lift
      return CGPoint(x: x, y: y)
    }
  }
}

/// A clock face: a ring and two hands.
struct ClockGlyph: View {
  let color: Color
  let size: CGFloat

  var body: some View {
    let w = max(2, size * 0.13)
    ZStack {
      Circle().strokeBorder(color, lineWidth: w)
      Path { path in
        let c = CGPoint(x: size / 2, y: size / 2)
        path.move(to: CGPoint(x: c.x, y: size * 0.16 + w / 2))
        path.addLine(to: c)
        path.addLine(to: CGPoint(x: c.x + size * 0.3 - w / 2, y: c.y))
      }
      .stroke(color, style: StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round))
    }
    .frame(width: size, height: size)
    .accessibilityHidden(true)
  }
}

enum MarkKind {
  case good, bad, wait, neutral
}

/// A flat status disc: a check (paid), a mark (problem), a clock (on its way, or ran out of time).
struct StatusMark: View {
  let kind: MarkKind
  let size: CGFloat
  let p: Palette

  var body: some View {
    let fill: UIColor = kind == .good ? p.good : kind == .bad ? p.bad : kind == .wait ? p.accent : p.surface2
    let ink = Color(kind == .neutral ? p.ink2 : .white)
    let bar = max(2, size * 0.09)
    ZStack {
      Circle().fill(Color(fill))
      switch kind {
      case .good:
        Tick(color: ink, size: size * 0.56, weight: max(2, size * 0.075))
      case .bad:
        VStack(spacing: size * 0.07) {
          Capsule().fill(ink).frame(width: bar, height: size * 0.3)
          Circle().fill(ink).frame(width: bar, height: bar)
        }
      case .wait, .neutral:
        ClockGlyph(color: ink, size: size * 0.46)
      }
    }
    .frame(width: size, height: size)
    .accessibilityHidden(true)
  }
}

/// The result's mark, arriving with a small spring inside a soft halo; one geometry for every outcome.
struct ResultMark: View {
  let kind: MarkKind
  let p: Palette
  @State private var shown = false

  var body: some View {
    let halo: UIColor = kind == .good ? p.good : kind == .bad ? p.bad : kind == .wait ? p.accent : rgba(118, 118, 128, 1)
    ZStack {
      Circle().fill(Color(halo))
        .frame(width: 96, height: 96)
        .opacity(shown ? (kind == .neutral ? 0.08 : 0.12) : 0)
        .scaleEffect(shown ? 1 : 0.6)
      StatusMark(kind: kind, size: 64, p: p)
        .opacity(shown ? 1 : 0)
        .scaleEffect(shown ? 1 : 0.5)
    }
    .frame(width: 96, height: 96)
    .onAppear {
      withAnimation(.spring(response: 0.31, dampingFraction: 0.47)) { shown = true }
    }
  }
}

/// A dot that breathes while we wait for the bank.
struct PulseDot: View {
  let color: Color
  @State private var dim = false

  var body: some View {
    Circle().fill(color).frame(width: 8, height: 8)
      .opacity(dim ? 0.3 : 1)
      .onAppear {
        withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { dim = true }
      }
  }
}

/// A gentle pulse for placeholders.
struct Pulsing: ViewModifier {
  var still = false
  @State private var dim = false

  func body(content: Content) -> some View {
    content
      .opacity(dim ? 0.5 : 1)
      .onAppear {
        guard !still else { return }
        withAnimation(.linear(duration: 0.8).repeatForever(autoreverses: true)) { dim = true }
      }
  }
}

struct SkeletonBlock: View {
  let look: Look
  var width: CGFloat?
  let height: CGFloat
  var radius: CGFloat = 8

  var body: some View {
    RoundedRectangle(cornerRadius: radius).fill(look.c(look.p.line)).frame(width: width, height: height)
  }
}

/// Placeholder method rows in the group while the engine loads.
struct RowsSkeleton: View {
  let look: Look
  let rows: Int
  var still = false

  var body: some View {
    VStack(spacing: 0) {
      ForEach(0..<rows, id: \.self) { i in
        RowFrame(look: look, line: i > 0) {
          SkeletonBlock(look: look, width: 32, height: 22, radius: 5)
          SkeletonBlock(look: look, width: CGFloat(92 + (i % 2) * 40), height: 12)
        }
      }
    }
    .modifier(Pulsing(still: still))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Loading payment methods")
  }
}

/// The whole sheet in placeholder shapes while the checkout loads, the same height as the sheet it becomes.
struct SheetSkeleton: View {
  let look: Look

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      VStack(alignment: .leading, spacing: 18) {
        HStack(spacing: 12) {
          SkeletonBlock(look: look, width: 42, height: 42, radius: 12)
          VStack(alignment: .leading, spacing: 7) {
            SkeletonBlock(look: look, width: 140, height: 13)
            SkeletonBlock(look: look, width: 96, height: 11)
          }
        }
        VStack(alignment: .leading, spacing: 9) {
          SkeletonBlock(look: look, width: 40, height: 11)
          SkeletonBlock(look: look, width: 150, height: 32)
        }
      }
      .padding(.horizontal, 20)
      .padding(.top, 14)
      VStack(alignment: .leading, spacing: 8) {
        SkeletonBlock(look: look, width: 110, height: 12)
        RowsSkeleton(look: look, rows: 5, still: true).padding(.horizontal, 14).methodGroup(look)
      }
      .padding(.horizontal, 20)
      .padding(.top, 22)
      .padding(.bottom, 16)
      VStack(spacing: 14) {
        SkeletonBlock(look: look, height: 56, radius: 28)
        SkeletonBlock(look: look, width: 120, height: 12)
      }
      .padding(.horizontal, 20)
      .padding(.top, 12)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .modifier(Pulsing())
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Loading")
  }
}

extension View {
  /// The ways to pay: one rounded group on the field colour, its 1pt border outside the rows (as a CSS border).
  func methodGroup(_ look: Look) -> some View {
    padding(1)
      .background(RoundedRectangle(cornerRadius: look.radius, style: .continuous).fill(look.c(look.p.field)))
      .clipShape(RoundedRectangle(cornerRadius: look.radius, style: .continuous))
      .overlay(RoundedRectangle(cornerRadius: look.radius, style: .continuous).strokeBorder(look.c(look.p.fieldLine), lineWidth: 1))
  }
}

/// The sheet's top corners.
struct TopRounded: Shape {
  let radius: CGFloat

  func path(in rect: CGRect) -> Path {
    Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight], cornerRadii: CGSize(width: radius, height: radius)).cgPath)
  }
}
