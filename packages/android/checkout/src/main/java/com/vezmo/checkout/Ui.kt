package com.vezmo.checkout

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The sheet's building blocks, drawn in Compose (no icon font): the Pay button, the segmented choice, the grouped
 * method list, status marks and skeletons. One type scale: 12.5 / 13 / 15 / 17 / 22 / 34. Labels: 13 medium, quiet
 * ink, 8 above their control. Sizes, colors and spacing are the React Native sheet's, point for point.
 */
internal class SheetLook(
    val p: SheetPalette,
    val radius: Dp,
    val font: FontFamily,
    val dark: Boolean,
)

internal val LocalLook = staticCompositionLocalOf<SheetLook> { error("No sheet look") }

@Composable
internal fun ProvideLook(
    p: SheetPalette,
    appearance: VezmoAppearance?,
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val fontRes = appearance?.fontResId
    val font = remember(fontRes) { fontRes?.let { FontFamily(Font(it)) } ?: FontFamily.Default }
    val look = remember(p, appearance, dark, font) { SheetLook(p, (appearance?.cornerRadius ?: DEFAULT_RADIUS).dp, font, dark) }
    CompositionLocalProvider(LocalLook provides look, content = content)
}

/**
 * Text measured as React Native measures it on Android, so line boxes and widths match the React Native sheet: font
 * sizes and line heights rounded up to whole pixels, the font's padding included unless a line height is set (React
 * Native's line height span replaces it).
 */
@Suppress("DEPRECATION")
private val WITH_PADDING = PlatformTextStyle(includeFontPadding = true)

@Suppress("DEPRECATION")
private val WITHOUT_PADDING = PlatformTextStyle(includeFontPadding = false)

/** [sp] rounded up to whole pixels, as React Native sizes text. */
@Composable
internal fun rnSp(sp: Float): TextUnit {
    val d = LocalDensity.current
    val scale = d.density * d.fontScale
    return (ceil(sp * scale) / scale).sp
}

/** One line of the sheet's text. */
@Composable
internal fun Txt(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float? = null,
    letterSpacing: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
    tabular: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    monospace: Boolean = false,
) {
    val look = LocalLook.current
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            color = color,
            fontSize = rnSp(size),
            fontWeight = weight,
            fontFamily = if (monospace) FontFamily.Monospace else look.font,
            letterSpacing = letterSpacing.sp,
            textAlign = align,
            fontFeatureSettings = if (tabular) "tnum" else null,
            platformStyle = if (lineHeight != null) WITHOUT_PADDING else WITH_PADDING,
            lineHeight = lineHeight?.let { rnSp(it) } ?: TextStyle.Default.lineHeight,
            lineHeightStyle = if (lineHeight != null) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) else null,
        ),
    )
}

/** One physical pixel (StyleSheet.hairlineWidth). */
@Composable
internal fun hairline(): Dp = with(LocalDensity.current) { 1f.toDp() }

/** A CSS box-shadow (offset, blur, spread) behind a rounded shape. Drawn on Android 9+, where blur is hardware drawn. */
internal fun Modifier.boxShadow(
    color: Color,
    offsetY: Dp,
    blur: Dp,
    spread: Dp = 0.dp,
    radius: Dp,
): Modifier =
    if (Build.VERSION.SDK_INT < 28 || color.alpha == 0f) {
        this
    } else {
        drawBehind {
            val s = spread.toPx()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                val b = blur.toPx() * 0.866f
                if (b > 0f) maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
            }
            val r = (radius.toPx() + s).coerceAtLeast(0f)
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(-s, offsetY.toPx() - s, size.width + s, size.height + offsetY.toPx() + s, r, r, paint)
            }
        }
    }

/** A CSS linear-gradient at [angleDeg] (0 = to top, 90 = to right) over the shape it fills. */
internal fun cssGradient(
    angleDeg: Float,
    stops: List<Pair<Float, Color>>,
): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = c - Offset(dx * half, dy * half),
                to = c + Offset(dx * half, dy * half),
                colors = stops.map { it.second },
                colorStops = stops.map { it.first },
            )
        }
    }

private val PAY_BRUSH = cssGradient(100f, PAY_GRADIENT)

/** The Pay button (and Done): Vezmo's gradient, or ink for a quiet finish; neutral until it can be pressed. */
@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    busy: Boolean = false,
    lock: Boolean = false,
    ink: Boolean = false,
    tag: String? = null,
) {
    val look = LocalLook.current
    val p = look.p
    val off = disabled && !busy
    val color = if (off) p.onDisabled else if (ink) p.surface else p.onAccent
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow = !off && !ink
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(if (pressed) 0.985f else 1f)
            .alpha(if (pressed) 0.94f else 1f)
            .then(
                if (shadow && p.payShadow != PayShadow.None) {
                    val tint = if (p.payShadow == PayShadow.Brand) Color(80, 58, 242) else Color(16, 24, 64)
                    val (a1, a2) = if (p.payShadow == PayShadow.Brand) 0.22f to 0.45f else 0.16f to 0.3f
                    Modifier
                        .boxShadow(tint.copy(alpha = a2), 8.dp, 18.dp, (-8).dp, 28.dp)
                        .boxShadow(tint.copy(alpha = a1), 1.dp, 2.dp, 0.dp, 28.dp)
                } else {
                    Modifier
                },
            ).clip(CircleShape)
            .then(
                when {
                    off -> Modifier.background(p.disabled)
                    ink -> Modifier.background(p.ink)
                    p.accentGradient -> Modifier.background(PAY_BRUSH)
                    else -> Modifier.background(p.accent)
                },
            ).clickable(enabled = !disabled && !busy, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics { if (busy) contentDescription = "Processing" },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (busy) {
                Spinner(color, 20.dp)
                Txt("Processing…", 17f, color, weight = FontWeight.SemiBold, letterSpacing = -0.2f, tabular = true)
            } else {
                if (lock && !off) Lock(color, 13.dp)
                Txt(label, 17f, color, weight = FontWeight.SemiBold, letterSpacing = -0.2f, tabular = true)
            }
        }
    }
}

/** A secondary action on a quiet fill (Check status). */
@Composable
internal fun GhostButton(label: String, onClick: () -> Unit) {
    val p = LocalLook.current.p
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(p.surface2)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Txt(label, 15f, p.ink, weight = FontWeight.SemiBold) }
}

/** A text link in the accent (Use another way to pay). */
@Composable
internal fun LinkText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Float = 14f, weight: FontWeight = FontWeight.SemiBold) {
    val p = LocalLook.current.p
    Txt(
        label,
        size,
        p.accent,
        weight = weight,
        align = TextAlign.Center,
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
    )
}

/** Two choices on one track (pay in rupees or dollars). */
@Composable
internal fun <K> Segmented(
    items: List<Triple<K, String, String?>>,
    value: K,
    onChange: (K) -> Unit,
    disabled: Boolean,
    /** Test tags, one per item. */
    tags: List<String> = emptyList(),
) {
    val look = LocalLook.current
    val p = look.p
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(look.radius))
            .background(p.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEachIndexed { i, (key, label, sub) ->
            val on = key == value
            val r = look.radius - 3.dp
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .then(
                        if (on) {
                            Modifier
                                .boxShadow(Color(16, 24, 64).copy(alpha = 0.14f), 3.dp, 10.dp, (-4).dp, r)
                                .boxShadow(Color(16, 24, 64).copy(alpha = 0.08f), 1.dp, 2.dp, 0.dp, r)
                                .clip(RoundedCornerShape(r))
                                .background(p.raised)
                        } else {
                            Modifier.clip(RoundedCornerShape(r))
                        },
                    ).semantics { selected = on }
                    .clickable(enabled = !disabled, role = Role.RadioButton) { onChange(key) }
                    .then(tags.getOrNull(i)?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(label, 15f, if (on) p.ink else p.ink2, weight = FontWeight.SemiBold, tabular = true, maxLines = 1)
                    if (sub != null) Txt("  $sub", 13f, p.ink3, weight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
                }
            }
        }
    }
}

/** A field label: 13 medium, quiet ink. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    Txt(text, 13f, LocalLook.current.p.ink2, weight = FontWeight.Medium, modifier = modifier)
}

/** A payment row's frame: 52 tall at least, 12 between icon and text, a hairline above all but the first. */
@Composable
internal fun RowFrame(
    first: Boolean,
    modifier: Modifier = Modifier,
    lead: Dp = ROW_LEAD,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalLook.current.p
    val hair = hairline()
    Row(
        modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.topLine(p.line, hair))
            .heightIn(min = 52.dp)
            .padding(start = lead, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * The engine's rows have their own leading margin inside our group; the sheet's own rows copy it so icons and titles
 * line up (measured on device: icon centred at 30dp, title at 58dp from the group's edge).
 */
internal val ROW_LEAD = 1.dp

internal fun Modifier.topLine(color: Color, width: Dp) = drawBehind { drawRect(color, size = Size(size.width, width.toPx())) }

/** One of the sheet's own methods (UPI, Pix), drawn like the engine's rows so the group reads as one list. */
@Composable
internal fun MethodRow(
    icon: @Composable () -> Unit,
    title: String,
    sub: String,
    selected: Boolean,
    disabled: Boolean,
    first: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalLook.current.p
    RowFrame(
        first,
        Modifier
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(enabled = !disabled, role = Role.RadioButton, onClick = onClick),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Txt(title, 15f, p.ink, weight = FontWeight.Medium)
            Txt(sub, 12.5f, p.ink3, modifier = Modifier.padding(top = 1.dp))
        }
        if (selected) Tick(p.accent, 16.dp)
    }
}

internal enum class Mark(val res: Int, val width: Float, val height: Float) {
    Visa(R.drawable.vezmo_mark_visa, 18f, 16f),
    Mastercard(R.drawable.vezmo_mark_mastercard, 18f, 16f),
    Amex(R.drawable.vezmo_mark_amex, 18f, 16f),
    Discover(R.drawable.vezmo_mark_discover, 18f, 16f),
    Upi(R.drawable.vezmo_mark_upi, 24f, 8.33f),
    Pix(R.drawable.vezmo_mark_pix, 24f, 8.67f),
    ;

    companion object {
        /** The network's mark for a card brand as Vezmo's server names it. */
        fun forBrand(brand: String?): Mark? =
            when ((brand ?: "").trim().lowercase().replace(Regex("[\\s_-]+"), "")) {
                "visa" -> Visa
                "mastercard", "master" -> Mastercard
                "amex", "americanexpress" -> Amex
                "discover" -> Discover
                else -> null
            }
    }
}

/** A payment brand's mark on its white tile, as on the web checkout (white in both themes: not recoloured). */
@Composable
internal fun BrandTile(mark: Mark, large: Boolean = false) {
    val p = LocalLook.current.p
    // Fit the mark inside the tile with a little room, keeping its shape.
    val (bw, bh) = if (large) 25f to 16f else 22f to 13f
    val k = min(min(bw / mark.width, bh / mark.height), 1f)
    Box(
        Modifier
            .size(if (large) 34.dp else 30.dp, if (large) 24.dp else 20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White)
            .border(1.dp, p.fieldLine, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(mark.res),
            contentDescription = null,
            modifier = Modifier.size((mark.width * k).dp, (mark.height * k).dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/** "Secured by" and the Vezmo logo, as under the embedded checkout (white in dark themes). */
@Composable
internal fun SecuredBy() {
    val look = LocalLook.current
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Secured by Vezmo" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("Secured by", 12.5f, look.p.ink3, weight = FontWeight.Medium)
        Image(
            painterResource(R.drawable.vezmo_logo),
            contentDescription = null,
            modifier = Modifier
                .size(50.7.dp, 14.dp)
                .alpha(if (look.dark) 0.85f else 1f),
            contentScale = ContentScale.Fit,
            colorFilter = if (look.dark) ColorFilter.tint(Color.White) else null,
        )
    }
}

/** A plain card, drawn: the Card row's icon before a card is typed. */
@Composable
private fun CardGlyph(color: Color) {
    Canvas(Modifier.size(22.dp, 16.dp)) {
        val w = 1.6.dp.toPx()
        drawRoundRect(color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(3.dp.toPx() - w / 2), style = Stroke(w))
        // The magnetic band: 3 tall, 3 below the inner top edge.
        drawRect(color, topLeft = Offset(w, w + 3.dp.toPx()), size = Size(size.width - 2 * w, 3.dp.toPx()))
    }
}

/** A bank: a pediment over three columns on a base. Instant ACH's icon, on its row and the receipt. */
@Composable
private fun BankGlyph(color: Color, scale: Float = 1f) {
    Canvas(Modifier.size(22.dp * scale, 16.dp * scale)) {
        val u = size.width / 22f
        val w = 1.6f * u
        val roof = Path().apply {
            moveTo(1.8f * u, 6.2f * u)
            lineTo(11f * u, 1f * u)
            lineTo(20.2f * u, 6.2f * u)
            close()
        }
        drawPath(roof, color, style = Stroke(w, join = StrokeJoin.Round))
        for (i in 0..2) {
            drawRoundRect(color, topLeft = Offset((4.2f + i * 5.8f) * u, 7.6f * u), size = Size(2f * u, 5.6f * u), cornerRadius = CornerRadius(0.5f * u))
        }
        drawRoundRect(color, topLeft = Offset(1f * u, 14.2f * u), size = Size(20f * u, 1.8f * u), cornerRadius = CornerRadius(0.8f * u))
    }
}

/** Instant ACH's bank on a tile, beside "Instant ACH" on the receipt (as a card network's mark sits beside its card). */
@Composable
internal fun BankTile() {
    val p = LocalLook.current.p
    Box(
        Modifier
            .size(30.dp, 20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(p.field)
            .border(1.dp, p.fieldLine, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) { BankGlyph(p.ink, scale = 0.8f) }
}

/**
 * The Card row, drawn by the sheet (the engine would show a plain card for any card): the network's own logo once a
 * card is typed, its last four, and Change. Tapping opens the engine's card form. The Instant ACH row is the same row
 * with a bank ([bank]): the linked account's last four, and Change to link another.
 */
@Composable
internal fun CardRow(
    card: CardChoice?,
    selected: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
    onChange: () -> Unit,
    bank: Boolean = false,
    first: Boolean = true,
) {
    val p = LocalLook.current.p
    val hair = hairline()
    val title = if (bank) INSTANT_ACH else "Card"
    RowFrame(
        first = first,
        modifier = Modifier
            .testTag(if (bank) "vezmo-instant-ach-row" else "vezmo-card-row")
            .semantics(mergeDescendants = true) {
                contentDescription = if (card != null) "$title ${card.label}" else title
                this.selected = selected
            }.clickable(enabled = !disabled, role = Role.RadioButton, onClick = onClick),
    ) {
        if (card?.image != null) {
            Box(
                Modifier
                    .size(30.dp, 22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(p.field)
                    .border(hair, p.fieldLine, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(card.image, contentDescription = null, modifier = Modifier.size(24.dp, 16.dp), contentScale = ContentScale.Fit)
            }
        } else {
            Box(Modifier.size(30.dp, 22.dp), contentAlignment = Alignment.Center) { if (bank) BankGlyph(p.ink) else CardGlyph(p.ink) }
        }
        Column(Modifier.weight(1f)) {
            Txt(title, 15f, p.ink, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Before a bank is linked, what Instant ACH is.
            val sub = card?.label ?: if (bank) "US bank account" else null
            if (sub != null) Txt(sub, 12.5f, p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        if (selected && card != null) {
            LinkText(
                "Change",
                onClick = { if (!disabled) onChange() },
                size = 14f,
                weight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = if (bank) "Change bank account" else "Change card" },
            )
            Tick(p.accent, 16.dp)
        }
    }
}

/** Something went wrong with the payment, said kindly. */
@Composable
internal fun Banner(text: String, tag: String? = null) {
    val look = LocalLook.current
    Row(
        Modifier
            .fillMaxWidth()
            // TalkBack reads what went wrong as it appears.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(look.radius))
            .background(look.p.badSoft)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusMark(MarkKind.Bad, 20.dp)
        Txt(text, 14f, look.p.ink, lineHeight = 19f, modifier = Modifier.weight(1f).then(if (tag != null) Modifier.testTag(tag) else Modifier))
    }
}

/** A padlock: body and shackle. */
@Composable
internal fun Lock(color: Color, size: Dp) {
    Canvas(Modifier.size(size * 0.8f, size)) {
        val s = size.toPx()
        val w = s * 0.8f
        val stroke = s * 0.13f
        val shackleW = w * 0.62f
        val shackleH = s * 0.5f
        val left = (w - shackleW) / 2f + stroke / 2f
        val right = (w + shackleW) / 2f - stroke / 2f
        val r = (right - left) / 2f
        val path = Path().apply {
            moveTo(left, shackleH)
            lineTo(left, stroke / 2f + r)
            arcTo(androidx.compose.ui.geometry.Rect(left, stroke / 2f, right, stroke / 2f + 2 * r), 180f, 180f, false)
            lineTo(right, shackleH)
        }
        drawPath(path, color, style = Stroke(stroke))
        val bodyTop = shackleH - s * 0.02f
        drawRoundRect(color, topLeft = Offset(0f, bodyTop), size = Size(w, s * 0.52f), cornerRadius = CornerRadius(s * 0.14f))
    }
}

/** A close cross. */
@Composable
internal fun Cross(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val bar = 1.8.dp.toPx()
        val c = this.size.width / 2f
        val h = this.size.width / 2f
        val d = h * 0.7071f
        drawLine(color, Offset(c - d, c - d), Offset(c + d, c + d), bar, StrokeCap.Round)
        drawLine(color, Offset(c - d, c + d), Offset(c + d, c - d), bar, StrokeCap.Round)
    }
}

/** A checkmark stroke: the two sides of a rotated L. */
@Composable
internal fun Tick(color: Color, size: Dp, weight: Dp? = null) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val w = weight?.toPx() ?: max(2.dp.toPx(), s * 0.14f)
        val hw = s * 0.18f
        val hh = s * 0.34f
        val cx = s / 2f
        val cy = s / 2f - s * 0.08f
        fun pt(x: Float, y: Float): Offset {
            val a = Math.toRadians(45.0)
            val c = cos(a).toFloat()
            val n = sin(a).toFloat()
            return Offset(cx + x * c - y * n, cy + x * n + y * c)
        }
        val a = pt(hw - w / 2f, -hh)
        val b = pt(hw - w / 2f, hh - w / 2f)
        val e = pt(-hw, hh - w / 2f)
        val path = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(e.x, e.y)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
    }
}

/** A clock face: a ring and two hands. */
@Composable
private fun Clock(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val w = max(2.dp.toPx(), s * 0.13f)
        drawCircle(color, radius = (s - w) / 2f, style = Stroke(w))
        val c = Offset(s / 2f, s / 2f)
        // Hands from the inner box (inside the ring): up to 16% and right to 30% of it.
        val inner = s - 2 * w
        drawLine(color, Offset(c.x, w + inner * 0.16f), Offset(c.x, w + inner * 0.54f), w, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y), Offset(c.x + inner * 0.3f, c.y), w, StrokeCap.Round)
    }
}

internal enum class MarkKind { Good, Bad, Wait, Neutral }

/** A flat status disc: a check (paid), a mark (problem), a clock (on its way, or ran out of time). */
@Composable
internal fun StatusMark(kind: MarkKind, size: Dp) {
    val p = LocalLook.current.p
    val fill = when (kind) {
        MarkKind.Good -> p.good
        MarkKind.Bad -> p.bad
        MarkKind.Wait -> p.accent
        MarkKind.Neutral -> p.surface2
    }
    val ink = if (kind == MarkKind.Neutral) p.ink2 else Color.White
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            MarkKind.Good -> Tick(ink, size * 0.56f, max(2f, size.value * 0.075f).dp)
            MarkKind.Bad -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(size * 0.07f)) {
                val bw = max(2f, size.value * 0.09f).dp
                Box(Modifier.size(bw, size * 0.3f).clip(CircleShape).background(ink))
                Box(Modifier.size(bw, bw).clip(CircleShape).background(ink))
            }
            else -> Clock(ink, size * 0.46f)
        }
    }
}

/** The result's mark, arriving with a small spring inside a soft halo; one geometry for every outcome. */
@Composable
internal fun ResultMark(kind: MarkKind) {
    val p = LocalLook.current.p
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
    val halo = when (kind) {
        MarkKind.Good -> p.good
        MarkKind.Bad -> p.bad
        MarkKind.Wait -> p.accent
        MarkKind.Neutral -> Color(0xFF767680)
    }
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer {
                    val v = t.value
                    alpha = (v * if (kind == MarkKind.Neutral) 0.08f else 0.12f).coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * v
                    scaleY = 0.6f + 0.4f * v
                }.clip(CircleShape)
                .background(halo),
        )
        Box(
            Modifier.graphicsLayer {
                val v = t.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.5f + 0.5f * v
                scaleY = 0.5f + 0.5f * v
            },
        ) { StatusMark(kind, 64.dp) }
    }
}

/** A dot that breathes while we wait for the bank. */
@Composable
internal fun Pulse(color: Color) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Box(
        Modifier
            .size(8.dp)
            .alpha(a)
            .clip(CircleShape)
            .background(color),
    )
}

/** The busy Pay button's spinner. */
@Composable
internal fun Spinner(color: Color, size: Dp) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
    Canvas(
        Modifier
            .size(size)
            .rotate(turn),
    ) {
        val w = 2.5.dp.toPx()
        drawArc(color, 0f, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A gentle pulse for placeholders. */
@Composable
private fun skeletonAlpha(): Float {
    val a by rememberInfiniteTransition(label = "skel").animateFloat(1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    return a
}

@Composable
private fun Block(width: Dp?, height: Dp, radius: Dp = 8.dp) {
    Box(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(LocalLook.current.p.line),
    )
}

/** Placeholder method rows in the group while the engine loads. */
@Composable
internal fun RowsSkeleton(rows: Int, still: Boolean = false) {
    val a = if (still) 1f else skeletonAlpha()
    Column(
        Modifier
            .alpha(a)
            .semantics { contentDescription = "Loading payment methods" },
    ) {
        repeat(rows) { i ->
            RowFrame(first = i == 0, lead = 0.dp) {
                Block(32.dp, 22.dp, 5.dp)
                Block((92 + (i % 2) * 40).dp, 12.dp)
            }
        }
    }
}

/** The whole sheet in placeholder shapes while the checkout loads, the same height as the sheet it becomes. */
@Composable
internal fun Skeleton() {
    val look = LocalLook.current
    Column(
        Modifier
            .alpha(skeletonAlpha())
            .semantics { contentDescription = "Loading" },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.padding(end = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Block(42.dp, 42.dp, 12.dp)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Block(140.dp, 13.dp)
                    Block(96.dp, 11.dp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Block(40.dp, 11.dp)
                Block(150.dp, 32.dp)
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Block(110.dp, 12.dp)
                Group { RowsSkeleton(5, still = true) }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Block(null, 56.dp, 28.dp)
            Block(120.dp, 12.dp)
        }
    }
}

/** The ways to pay: one rounded group (the Card row, the engine's rows, then the sheet's own UPI or Pix row). */
@Composable
internal fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalLook.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(look.radius))
            .background(look.p.field)
            .border(1.dp, look.p.fieldLine, RoundedCornerShape(look.radius))
            .padding(horizontal = 15.dp, vertical = 1.dp),
    ) { content() }
}

@Composable
internal fun VSpace(height: Dp) = Spacer(Modifier.height(height))

/** A remembered painter for a PNG data URI (the UPI or Pix QR from Vezmo's server). */
@Composable
internal fun rememberDataUriPainter(uri: String): Painter? =
    remember(uri) {
        runCatching {
            val bytes = android.util.Base64.decode(uri.substringAfter("base64,"), android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                androidx.compose.ui.graphics.painter.BitmapPainter(it.asImageBitmap())
            }
        }.getOrNull()
    }
