import StripePaymentSheet
import SwiftUI
import UIKit

/// The Vezmo checkout's look on the phone: the same tokens as the hosted checkout, opaque where the web uses glass. A
/// merchant may override the colours, corner radius and font (`VezmoCheckout.Appearance`).
struct Palette: Equatable {
  var page, surface, surface2, raised, fill, disabled, onDisabled, field, fieldLine, line: UIColor
  var ink, ink2, ink3, accent, onAccent, accentSoft, good, bad, badSoft, warn, warnInk, warnSoft, logoTile, scrim: UIColor
  /// The Pay button's fill is the logo's indigo-to-blue gradient. Dropped when a merchant sets their own accent.
  var accentGradient = true
  /// The colour of the Pay button's lift (none in dark, where a glow reads as cheap).
  var payShadow: UIColor?

  static let light = Palette(
    page: hex(0xF3F5FA), surface: hex(0xFFFFFF), surface2: hex(0xF4F5FA), raised: hex(0xFFFFFF),
    fill: rgba(118, 118, 128, 0.12), disabled: hex(0xECECF1), onDisabled: hex(0xA1A1AA), field: hex(0xFFFFFF),
    fieldLine: hex(0xE1E3EC), line: hex(0xE8EAF1), ink: hex(0x0B0C14), ink2: hex(0x454A5A), ink3: hex(0x6B7083),
    accent: hex(0x503AF2), onAccent: hex(0xFFFFFF), accentSoft: rgba(80, 58, 242, 0.08), good: hex(0x0E8A55),
    bad: hex(0xD12C4B), badSoft: rgba(209, 44, 75, 0.07), warn: hex(0xEA580C), warnInk: hex(0xB45309),
    warnSoft: hex(0xFEF3C7), logoTile: hex(0xFFFFFF), scrim: rgba(11, 12, 20, 0.42),
    payShadow: hex(0x503AF2)
  )

  static let dark = Palette(
    page: hex(0x07080E), surface: hex(0x14151F), surface2: hex(0x1C1D29), raised: hex(0x2C2D3C),
    fill: rgba(118, 118, 128, 0.24), disabled: hex(0x26262F), onDisabled: hex(0x6B6B76), field: hex(0x1B1C27),
    fieldLine: hex(0x2A2B37), line: hex(0x24252F), ink: hex(0xF4F4F7), ink2: hex(0xB9BBC7), ink3: hex(0x8C8FA0),
    accent: hex(0x7B6BFF), onAccent: hex(0xFFFFFF), accentSoft: rgba(123, 107, 255, 0.16), good: hex(0x3DD68C),
    bad: hex(0xFF6B85), badSoft: rgba(255, 107, 133, 0.1), warn: hex(0xEA580C), warnInk: hex(0xFBBF24),
    warnSoft: rgba(251, 191, 36, 0.14), logoTile: hex(0x1D1D28), scrim: rgba(0, 0, 0, 0.62),
    payShadow: nil
  )

  /// The Pay button's gradient stops (100°, as on the web).
  static let gradient: [Gradient.Stop] = [
    .init(color: Color(hex(0x503AF2)), location: 0),
    .init(color: Color(hex(0x4651F7)), location: 0.52),
    .init(color: Color(hex(0x3866FF)), location: 1),
  ]

  static func resolve(dark: Bool, appearance: VezmoCheckout.Appearance?) -> Palette {
    var out = dark ? Palette.dark : Palette.light
    let overrides = appearance?.colors ?? [:]
    // A merchant's own accent replaces the Vezmo gradient (and its indigo lift) on the Pay button.
    if overrides[.accent] != nil {
      out.accentGradient = false
      if out.payShadow != nil { out.payShadow = rgba(16, 24, 64, 1) }
    }
    let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
    for (key, color) in overrides {
      out[keyPath: key.keyPath] = color.resolvedColor(with: traits)
    }
    return out
  }
}

extension VezmoCheckout.Appearance.ColorKey {
  var keyPath: WritableKeyPath<Palette, UIColor> {
    switch self {
    case .page: return \.page
    case .surface: return \.surface
    case .surface2: return \.surface2
    case .raised: return \.raised
    case .fill: return \.fill
    case .disabled: return \.disabled
    case .onDisabled: return \.onDisabled
    case .field: return \.field
    case .fieldLine: return \.fieldLine
    case .line: return \.line
    case .ink: return \.ink
    case .ink2: return \.ink2
    case .ink3: return \.ink3
    case .accent: return \.accent
    case .onAccent: return \.onAccent
    case .accentSoft: return \.accentSoft
    case .good: return \.good
    case .bad: return \.bad
    case .badSoft: return \.badSoft
    case .warn: return \.warn
    case .warnInk: return \.warnInk
    case .warnSoft: return \.warnSoft
    case .logoTile: return \.logoTile
    case .scrim: return \.scrim
    }
  }
}

/// The default corner radius of fields and payment method rows, in points.
let defaultRadius: CGFloat = 14

extension Palette {
  /// The engine's look (card form, method rows), from the sheet's palette.
  func engineAppearance(radius: CGFloat, fontFamily: String?) -> PaymentSheet.Appearance {
    var a = PaymentSheet.Appearance()
    a.colors.primary = accent
    // The rows sit in the sheet's group, on the field colour.
    a.colors.background = field
    a.colors.componentBackground = field
    a.colors.componentBorder = fieldLine
    a.colors.componentDivider = line
    a.colors.text = ink
    a.colors.textSecondary = ink3
    a.colors.componentText = ink
    a.colors.componentPlaceholderText = ink3
    a.colors.icon = ink2
    a.colors.danger = bad
    a.cornerRadius = radius
    a.borderWidth = 1
    // A flat list with a checkmark; the sheet draws the rounded group around it.
    a.embeddedPaymentElement.row.style = .flatWithCheckmark
    a.embeddedPaymentElement.row.flat.topSeparatorEnabled = false
    a.embeddedPaymentElement.row.flat.bottomSeparatorEnabled = false
    a.embeddedPaymentElement.row.flat.separatorColor = line
    a.embeddedPaymentElement.row.additionalInsets = 6
    if let fontFamily, let font = UIFont(name: fontFamily, size: UIFont.systemFontSize) {
      a.font.base = font
    }
    return a
  }
}

func hex(_ value: UInt32) -> UIColor {
  UIColor(
    red: CGFloat((value >> 16) & 0xFF) / 255,
    green: CGFloat((value >> 8) & 0xFF) / 255,
    blue: CGFloat(value & 0xFF) / 255,
    alpha: 1
  )
}

func rgba(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat) -> UIColor {
  UIColor(red: r / 255, green: g / 255, blue: b / 255, alpha: a)
}
