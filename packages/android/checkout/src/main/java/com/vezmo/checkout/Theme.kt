package com.vezmo.checkout

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.stripe.android.paymentsheet.PaymentSheet

/**
 * The Vezmo checkout's look on the phone: the same tokens as the hosted checkout, opaque where the web uses glass.
 * A merchant may override the accent, colors, corner radius and font.
 */
internal data class SheetPalette(
    val surface: Color,
    /** Quiet fills: the segmented track, the code box, skeletons. */
    val surface2: Color,
    /** The chosen side of a segmented track (lighter than the track in both themes). */
    val raised: Color,
    /** System-grey fill behind small round controls (close). */
    val fill: Color,
    /** The Pay button before a method is chosen, and its text. */
    val disabled: Color,
    val onDisabled: Color,
    val field: Color,
    val fieldLine: Color,
    val line: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    /** The accent at low strength: selected tiles, the waiting status. */
    val accentSoft: Color,
    /** The Pay button's fill is the logo's indigo-to-blue. Dropped when a merchant sets their own accent. */
    val accentGradient: Boolean,
    /** The Pay button's lift: Vezmo's indigo, a neutral one (merchant accent), or none (dark). */
    val payShadow: PayShadow,
    val good: Color,
    val bad: Color,
    val badSoft: Color,
    /** The test-mode badge, behind white text (at least 4.5:1 for it). */
    val warn: Color,
    /** Time running out (the last minute of a UPI or Pix code). */
    val warnInk: Color,
    val warnSoft: Color,
    /** Behind the business's monogram. */
    val logoTile: Color,
    val scrim: Color,
)

internal enum class PayShadow { Brand, Neutral, None }

private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).toInt().coerceIn(0, 255))

/** The Pay button's gradient: 100°, #503AF2 → #4651F7 (52%) → #3866FF. */
internal val PAY_GRADIENT = listOf(0f to Color(0xFF503AF2), 0.52f to Color(0xFF4651F7), 1f to Color(0xFF3866FF))

internal val LIGHT = SheetPalette(
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F5FA),
    raised = Color(0xFFFFFFFF),
    fill = rgba(118, 118, 128, 0.12f),
    disabled = Color(0xFFECECF1),
    onDisabled = Color(0xFFA1A1AA),
    field = Color(0xFFFFFFFF),
    fieldLine = Color(0xFFE1E3EC),
    line = Color(0xFFE8EAF1),
    ink = Color(0xFF0B0C14),
    ink2 = Color(0xFF454A5A),
    ink3 = Color(0xFF6B7083),
    accent = Color(0xFF503AF2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = rgba(80, 58, 242, 0.08f),
    accentGradient = true,
    payShadow = PayShadow.Brand,
    good = Color(0xFF0E8A55),
    bad = Color(0xFFD12C4B),
    badSoft = rgba(209, 44, 75, 0.07f),
    warn = Color(0xFFC2410C),
    warnInk = Color(0xFFB45309),
    warnSoft = Color(0xFFFEF3C7),
    logoTile = Color(0xFFFFFFFF),
    scrim = rgba(11, 12, 20, 0.42f),
)

internal val DARK = SheetPalette(
    surface = Color(0xFF14151F),
    surface2 = Color(0xFF1C1D29),
    raised = Color(0xFF2C2D3C),
    fill = rgba(118, 118, 128, 0.24f),
    disabled = Color(0xFF26262F),
    onDisabled = Color(0xFF6B6B76),
    field = Color(0xFF1B1C27),
    fieldLine = Color(0xFF2A2B37),
    line = Color(0xFF24252F),
    ink = Color(0xFFF4F4F7),
    ink2 = Color(0xFFB9BBC7),
    ink3 = Color(0xFF8C8FA0),
    accent = Color(0xFF7B6BFF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = rgba(123, 107, 255, 0.16f),
    accentGradient = true,
    payShadow = PayShadow.None,
    good = Color(0xFF3DD68C),
    bad = Color(0xFFFF6B85),
    badSoft = rgba(255, 107, 133, 0.1f),
    warn = Color(0xFFC2410C),
    warnInk = Color(0xFFFBBF24),
    warnSoft = rgba(251, 191, 36, 0.14f),
    logoTile = Color(0xFF1D1D28),
    scrim = rgba(0, 0, 0, 0.62f),
)

internal const val DEFAULT_RADIUS = 14f

internal fun paletteFor(dark: Boolean, appearance: VezmoAppearance?): SheetPalette {
    val base = if (dark) DARK else LIGHT
    val o = appearance?.colors ?: return base
    fun pick(c: VezmoColor?, fallback: Color) = c?.let { Color(if (dark) it.dark else it.light) } ?: fallback
    return base.copy(
        // A merchant's own accent replaces the Vezmo gradient (and its indigo lift) on the Pay button.
        accentGradient = o.accent == null,
        payShadow = if (o.accent == null || base.payShadow == PayShadow.None) base.payShadow else PayShadow.Neutral,
        accent = pick(o.accent, base.accent),
        onAccent = pick(o.onAccent, base.onAccent),
        surface = pick(o.surface, base.surface),
        surface2 = pick(o.surface2, base.surface2),
        raised = pick(o.raised, base.raised),
        fill = pick(o.fill, base.fill),
        disabled = pick(o.disabled, base.disabled),
        onDisabled = pick(o.onDisabled, base.onDisabled),
        field = pick(o.field, base.field),
        fieldLine = pick(o.fieldLine, base.fieldLine),
        line = pick(o.line, base.line),
        ink = pick(o.ink, base.ink),
        ink2 = pick(o.ink2, base.ink2),
        ink3 = pick(o.ink3, base.ink3),
        accentSoft = pick(o.accentSoft, base.accentSoft),
        good = pick(o.good, base.good),
        bad = pick(o.bad, base.bad),
        badSoft = pick(o.badSoft, base.badSoft),
        warn = pick(o.warn, base.warn),
        warnInk = pick(o.warnInk, base.warnInk),
        warnSoft = pick(o.warnSoft, base.warnSoft),
        logoTile = pick(o.logoTile, base.logoTile),
        scrim = pick(o.scrim, base.scrim),
    )
}

/**
 * The engine's appearance (the card form, the element's rows), from the sheet's palette: one palette, already chosen
 * for light or dark, used for both.
 */
@SuppressLint("RestrictedApi")
internal fun engineAppearance(p: SheetPalette, appearance: VezmoAppearance?): PaymentSheet.Appearance {
    fun palette(base: PaymentSheet.Colors.Builder): PaymentSheet.Colors =
        base
            .primary(p.accent.toArgb())
            // The rows sit in the sheet's group, on the field colour.
            .surface(p.field.toArgb())
            .component(p.field.toArgb())
            .componentBorder(p.fieldLine.toArgb())
            .componentDivider(p.line.toArgb())
            .onComponent(p.ink.toArgb())
            .onSurface(p.ink.toArgb())
            .subtitle(p.ink3.toArgb())
            .placeholderText(p.ink3.toArgb())
            .appBarIcon(p.ink2.toArgb())
            .error(p.bad.toArgb())
            .build()

    fun rowColors(base: PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder) =
        base.checkmarkColor(p.accent.toArgb()).separatorColor(p.line.toArgb()).build()

    val builder = PaymentSheet.Appearance
        .Builder()
        .colorsLight(palette(PaymentSheet.Colors.Builder.light()))
        .colorsDark(palette(PaymentSheet.Colors.Builder.dark()))
        .shapes(
            PaymentSheet.Shapes.Builder()
                .cornerRadiusDp(appearance?.cornerRadius ?: DEFAULT_RADIUS)
                .borderStrokeWidthDp(1f)
                .build(),
        )
        // A flat list with a checkmark; the sheet draws the rounded group around it.
        .embeddedAppearance(
            PaymentSheet.Appearance.Embedded(
                PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark
                    .Builder()
                    .topSeparatorEnabled(false)
                    .bottomSeparatorEnabled(false)
                    .horizontalInsetsDp(0f)
                    .additionalVerticalInsetsDp(6f)
                    .colorsLight(rowColors(PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder.light()))
                    .colorsDark(rowColors(PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder.dark()))
                    .build(),
            ),
        )
    appearance?.fontResId?.let { builder.typography(PaymentSheet.Typography.Builder().fontResId(it).build()) }
    return builder.build()
}
