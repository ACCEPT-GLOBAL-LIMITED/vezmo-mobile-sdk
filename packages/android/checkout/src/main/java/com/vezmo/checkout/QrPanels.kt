package com.vezmo.checkout

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

/** Who pays, for UPI or Pix: name and address (and the CPF or CNPJ for Pix), as the web checkout asks. */
internal class QrFormState(defaultName: String) {
    var name by mutableStateOf(defaultName)
    var line1 by mutableStateOf("")
    var city by mutableStateOf("")
    var postalCode by mutableStateOf("")
    var taxId by mutableStateOf("")

    /** Pay was tapped once: show what's missing. */
    var tried by mutableStateOf(false)

    fun problem(method: QrMethod): String? {
        val digits = taxId.filter { it.isDigit() }
        return when {
            name.isBlank() -> "Enter your name."
            line1.isBlank() -> "Enter your address."
            method == QrMethod.Pix && digits.length != 11 && digits.length != 14 -> "Enter your CPF (11 digits) or CNPJ (14 digits)."
            else -> null
        }
    }

    /** Checks the details; the billing to pay with, or null (the problem shows under the form). */
    fun submit(method: QrMethod, country: String?): QrBilling? {
        tried = true
        if (problem(method) != null) return null
        return QrBilling(
            name = name.trim(),
            line1 = line1.trim(),
            city = city.trim().ifEmpty { null },
            postalCode = postalCode.trim().ifEmpty { null },
            country = country,
            taxId = if (method == QrMethod.Pix) taxId.trim() else null,
        )
    }
}

@Composable
internal fun QrForm(
    state: QrFormState,
    method: QrMethod,
    busy: Boolean,
    /** The last try failed. */
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Field("Full name", state.name, { state.name = it }, "First and last name", !busy, capitalization = KeyboardCapitalization.Words)
        Field("Address", state.line1, { state.line1 = it }, "Street and number", !busy, capitalization = KeyboardCapitalization.Words)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("City", state.city, { state.city = it }, "City", !busy, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            Field(
                "Postal code",
                state.postalCode,
                { state.postalCode = it },
                if (method == QrMethod.Upi) "PIN code" else "CEP",
                !busy,
                Modifier.weight(1f),
                keyboard = KeyboardType.Number,
            )
        }
        if (method == QrMethod.Pix) {
            Field("CPF or CNPJ", state.taxId, { state.taxId = it }, "000.000.000-00", !busy, keyboard = KeyboardType.Number)
        }
        val shown = (if (state.tried) state.problem(method) else null) ?: error
        if (shown != null) Banner(shown)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val look = LocalLook.current
    val p = look.p
    @Suppress("DEPRECATION")
    val style = TextStyle(color = p.ink, fontSize = rnSp(16f), fontFamily = look.font, platformStyle = PlatformTextStyle(includeFontPadding = true))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(RoundedCornerShape(look.radius))
                        .background(p.field)
                        .border(1.dp, p.fieldLine, RoundedCornerShape(look.radius))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Txt(placeholder, 16f, p.onDisabled, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** The buyer pays in their bank app; we open it (UPI), show the code (Pix), and check when they come back. */
@Composable
internal fun QrWaiting(
    session: CheckoutSession,
    method: QrMethod,
    qr: QrPayment,
    amount: String,
) {
    val p = LocalLook.current.p
    val context = LocalContext.current
    val expiresAt = qr.expiresAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (expiresAt != null) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = expiresAt?.let { maxOf(0L, Math.round(it - now / 1000.0)) }
    val hurry = left != null && left < 60
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    LaunchedEffect(method, qr.link) { if (method == QrMethod.Upi && qr.link != null) openLink(context, qr.link) }
    // Back from the bank app: check at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { session.post { session.checkNow() } }

    val steps = if (method == QrMethod.Pix) {
        listOf("Tap Copy Pix code.", "In your bank app, choose Pix Copia e Cola and paste it.", "Confirm there. This screen updates by itself.")
    } else {
        listOf("Approve the $amount request in your UPI app.", "Come back here. This screen updates by itself.")
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // The last minute turns amber.
        Row(
            Modifier
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(if (hurry) p.warnSoft else p.accentSoft)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pulse(if (hurry) p.warnInk else p.accent)
            val text = if (left == 0L) {
                "Code expired"
            } else {
                "Waiting for your payment" + (left?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')} left" } ?: "")
            }
            Txt(text, 13.5f, if (hurry) p.warnInk else p.accent, weight = FontWeight.SemiBold, tabular = true)
        }

        if (method == QrMethod.Pix && qr.code != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(LocalLook.current.radius))
                        .background(p.surface2)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Label("Pix code")
                    Txt(qr.code, 13f, p.ink2, lineHeight = 18f, maxLines = 1, overflow = TextOverflow.Ellipsis, monospace = true)
                }
                PrimaryButton(if (copied) "Copied" else "Copy Pix code", onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pix code", qr.code))
                    copied = true
                })
            }
        }
        if (method == QrMethod.Upi && qr.link != null) {
            PrimaryButton("Open UPI app", onClick = { openLink(context, qr.link) })
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(p.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) { Txt("${i + 1}", 12f, p.accent, weight = FontWeight.Bold) }
                    Txt(t, 14f, p.ink2, lineHeight = 20f, modifier = Modifier.weight(1f).padding(top = 2.dp))
                }
            }
        }

        qr.qrPng?.let { png ->
            val painter = rememberDataUriPainter(png)
            if (painter != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // A white tile with a quiet zone, so any phone can read it in either theme.
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, p.fieldLine, RoundedCornerShape(14.dp))
                            .padding(10.dp),
                    ) {
                        Image(painter, contentDescription = "${method.title} QR code", modifier = Modifier.size(100.dp))
                    }
                    Txt(
                        "Paying from another phone? Scan this code in its ${if (method == QrMethod.Upi) "UPI" else "bank"} app.",
                        13.5f,
                        p.ink3,
                        lineHeight = 19f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GhostButton("Check status", onClick = { session.post { session.checkNow() } })
            LinkText("Use another way to pay", onClick = { session.stopWaiting() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun openLink(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
    } catch (_: Exception) {
        // No app for the link: the buyer can scan the code instead.
    }
}
