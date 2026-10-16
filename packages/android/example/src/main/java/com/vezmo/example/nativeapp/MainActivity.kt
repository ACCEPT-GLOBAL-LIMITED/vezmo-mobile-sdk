package com.vezmo.example.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vezmo.checkout.CheckoutResult
import com.vezmo.checkout.SubscriptionResult
import com.vezmo.checkout.VezmoCheckout
import com.vezmo.checkout.VezmoConfiguration
import com.vezmo.checkout.rememberVezmoCheckout
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Vezmo SDK example: a small shop with one product and a Buy button that opens the Vezmo sheet. A real app gets
 * the session's client token from its own server; here it comes from the sandbox demo endpoint or is pasted in under
 * Sandbox.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Shop() }
    }
}

private val INK = Color(0xFF0B0C14)
private val GREY = Color(0xFF5A5F70)
private val LINE = Color(0xFFE1E3EC)
private val DARK = Color(0xFF2B2D3A)

@Suppress("DEPRECATION")
private fun style(size: Float, color: Color = INK, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, mono: Boolean = false) =
    TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shop() {
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CheckoutResult?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val configuration = remember {
        VezmoConfiguration(apiBase = BuildConfig.VEZMO_API_BASE)
    }
    val vezmo = rememberVezmoCheckout(configuration) { result = it }

    // `mor`: a session on the sandbox's merchant-of-record business (it has Cash App, Klarna and Amazon Pay on).
    val newSession: (String?) -> Unit = { track ->
        scope.launch {
            status = "Creating a sandbox session…"
            val url = BuildConfig.VEZMO_DEMO_URL + (track?.let { "?track=$it" } ?: "")
            val created = withContext(Dispatchers.IO) { runCatching { demoToken(url, "clientToken") }.getOrNull() }
            if (created == null) {
                status = "Could not reach the demo endpoint."
            } else {
                token = created
                status = "Session ready."
            }
        }
    }
    val pay: () -> Unit = {
        focus.clearFocus()
        scope.launch {
            result = null
            status = "Loading checkout…"
            val error = vezmo.prepare(token.trim())
            if (error != null) {
                status = "Could not load: ${error.message}"
            } else {
                status = ""
                vezmo.present()
            }
        }
    }
    // A real app's server creates the subscription (POST /merchant/subscription) and returns its checkoutToken.
    val subscribe: (String) -> Unit = { query ->
        focus.clearFocus()
        scope.launch {
            result = null
            status = "Loading checkout…"
            val subscriptionToken = withContext(Dispatchers.IO) { runCatching { demoToken(SUBSCRIPTION_URL + query, "subscriptionToken") }.getOrNull() }
            if (subscriptionToken == null) {
                status = "Could not reach the demo endpoint."
                return@launch
            }
            val error = vezmo.prepare(subscriptionToken = subscriptionToken)
            if (error != null) {
                status = "Could not load: ${error.message}"
            } else {
                status = ""
                vezmo.present()
            }
        }
    }
    val ready = token.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F5F8))
            .semantics { testTagsAsResourceId = true }
            // Tapping outside the field closes the keyboard.
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
            .verticalScroll(rememberScrollState())
            // Below the status bar, as the React Native example lays it out.
            .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A focusable heading first: when focus comes back without a touch (keyboard navigation, the e2e tests), it
        // lands here, not in the token field with its keyboard.
        BasicText(
            "Northwind Audio",
            Modifier
                .semantics { heading() }
                .focusable(),
            style = style(28f, weight = FontWeight.Bold, spacing = -0.5f),
        )

        ProductCard {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFE9E7F7)),
                contentAlignment = Alignment.Center,
            ) { Headphones() }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Aurora wireless headphones", style = style(17f, weight = FontWeight.SemiBold))
                BasicText("$49.00", style = style(15f, GREY))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(if (ready) 1f else 0.35f)
                    .clip(CircleShape)
                    .background(INK)
                    .testTag("pay")
                    .clickable(enabled = ready, onClick = pay),
                contentAlignment = Alignment.Center,
            ) { BasicText("Buy now", style = style(16f, Color.White, FontWeight.SemiBold)) }
        }

        if (status.isNotEmpty()) {
            BasicText(status, Modifier.fillMaxWidth().testTag("status"), style = style(14f, GREY).copy(textAlign = TextAlign.Center))
        }
        result?.let {
            BasicText("Result: ${it.toJson()}", Modifier.testTag("result"), style = style(13f, mono = true))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .dashedBorder(LINE, 16.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("SANDBOX · SDK ${VezmoCheckout.VERSION}", style = style(12f, Color(0xFF8A8FA0), FontWeight.SemiBold, 0.4f))
            BasicTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                textStyle = style(14f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("client-token"),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, LINE, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (token.isEmpty()) BasicText("Client token from your server", style = style(14f, Color(0xFFA1A1AA)))
                        inner()
                    }
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, LINE, CircleShape)
                    .testTag("new-session")
                    .clickable { newSession(null) },
                contentAlignment = Alignment.Center,
            ) { BasicText("New sandbox session", style = style(14f, weight = FontWeight.SemiBold)) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, LINE, CircleShape)
                    .testTag("new-session-mor")
                    .clickable { newSession("mor") },
                contentAlignment = Alignment.Center,
            ) { BasicText("New session · merchant of record", style = style(14f, weight = FontWeight.SemiBold)) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, LINE, CircleShape)
                    .testTag("subscribe-now")
                    .clickable { subscribe("?track=mor") },
                contentAlignment = Alignment.Center,
            ) { BasicText("Subscribe, no trial · merchant of record", style = style(14f, weight = FontWeight.SemiBold)) }
        }

        // A subscription with a free trial (the demo business's Club plan).
        ProductCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE9E7F7)),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(26.dp).border(7.dp, DARK, CircleShape)) }
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Northwind Audio Club", style = style(17f, weight = FontWeight.SemiBold))
                    BasicText("$9.99 / month · first 7 days free", style = style(15f, GREY))
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(INK)
                    .testTag("subscribe")
                    .clickable { subscribe("?trial=1") },
                contentAlignment = Alignment.Center,
            ) { BasicText("Try 7 days free", style = style(16f, Color.White, FontWeight.SemiBold)) }
        }
    }
}

/** A white card on the page, as the React Native example's products. */
@Composable
private fun ProductCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x0F101840), spotColor = Color(0x0F101840))
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** The sandbox's subscription checkout (same demo business): …/demo/mobile-subscription. */
private val SUBSCRIPTION_URL = BuildConfig.VEZMO_DEMO_URL.replace(Regex("mobile-session$"), "mobile-subscription")

/** A pair of headphones, drawn: the band and two cups. */
@Composable
private fun Headphones() {
    // The cups overlap the band by 16: 70 + 48 - 16 tall.
    Box(Modifier.size(130.dp, 102.dp)) {
        Canvas(Modifier.size(112.dp, 70.dp).align(Alignment.TopCenter)) {
            val w = 9.dp.toPx()
            val r = 56.dp.toPx() - w / 2
            val c = Offset(size.width / 2, 56.dp.toPx())
            val path = Path().apply {
                moveTo(w / 2, size.height)
                lineTo(w / 2, c.y)
                arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), 180f, 180f, false)
                lineTo(size.width - w / 2, size.height)
            }
            drawPath(path, DARK, style = Stroke(w))
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(74.dp)) {
            repeat(2) {
                Spacer(
                    Modifier
                        .width(28.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DARK),
                )
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp) =
    drawBehind {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        )
    }

/** The sandbox demo endpoints make a TEST-mode session (or subscription) and return its token. */
private fun demoToken(url: String, field: String): String? {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        conn.outputStream.close()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        JSONObject(body).optJSONObject("data")?.optString(field)?.ifEmpty { null }
    } finally {
        conn.disconnect()
    }
}

/** The result as JSON, as the React Native example shows it. */
private fun CheckoutResult.toJson(): String =
    when (this) {
        is CheckoutResult.Succeeded -> JSONObject()
            .put("status", "succeeded")
            .put("paymentId", paymentId ?: JSONObject.NULL)
            .put("transactionId", transactionId ?: JSONObject.NULL)
            .apply { subscription?.let { put("subscription", it.toJson()) } }
        is CheckoutResult.Processing -> JSONObject()
            .put("status", "processing")
            .put("paymentId", paymentId ?: JSONObject.NULL)
            .apply { subscription?.let { put("subscription", it.toJson()) } }
        CheckoutResult.Canceled -> JSONObject().put("status", "canceled")
        is CheckoutResult.Failed -> JSONObject().put("status", "failed").put(
            "error",
            JSONObject()
                .put("code", error.code.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase())
                .put("message", error.message),
        )
    }.toString()

private fun SubscriptionResult.toJson() = JSONObject().put("ref", ref).put("status", status.name.lowercase())
