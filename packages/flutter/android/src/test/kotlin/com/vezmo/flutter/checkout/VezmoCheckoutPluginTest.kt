package com.vezmo.flutter.checkout

import android.app.Activity
import android.os.Looper
import androidx.activity.ComponentActivity
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.lang.reflect.Proxy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The plugin's activity lifecycle, with real activities and the real native SDK paying against a local server: a
 * session prepared before the activity is recreated still opens, and a checkout that can't be set up says why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VezmoCheckoutPluginTest {
    private val server = MockWebServer()
    private val plugin = VezmoCheckoutPlugin()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    @Test
    fun `a configuration change between prepare and present prepares again on the new activity, then opens the sheet`() {
        server.enqueue(session())
        server.enqueue(session())
        plugin.onAttachedToActivity(binding(created()))
        assertNull(call("prepare", prepareArgs()))

        // The activity is recreated (a configuration change it doesn't handle itself) before the app presents.
        plugin.onDetachedFromActivityForConfigChanges()
        val recreated = created()
        plugin.onReattachedToActivityForConfigChanges(binding(recreated))

        val presenting = Answer()
        plugin.onMethodCall(MethodCall("present", null), presenting)
        val sheet = waitFor { shadowOf(recreated).nextStartedActivityForResult }
        assertEquals("com.vezmo.checkout.VezmoCheckoutActivity", sheet.intent.component?.className)
        assertEquals(2, server.requestCount)
        assertTrue("the sheet is open: no result yet", !presenting.answered)
    }

    @Test
    fun `when the session can't be prepared again, present answers why instead of hanging`() {
        server.enqueue(session())
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"success":false,"message":"This checkout has expired."}"""))
        plugin.onAttachedToActivity(binding(created()))
        assertNull(call("prepare", prepareArgs()))

        plugin.onDetachedFromActivityForConfigChanges()
        plugin.onReattachedToActivityForConfigChanges(binding(created()))

        val result = call("present", null) as Map<*, *>
        assertEquals("failed", result["status"])
        assertEquals("expired", (result["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `an engine attached after the activity started is told so, not to use a FlutterFragmentActivity`() {
        val started = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().get()
        plugin.onAttachedToActivity(binding(started))
        val error = call("prepare", prepareArgs()) as Map<*, *>
        assertEquals("config", error["code"])
        assertEquals(VezmoCheckoutPlugin.ATTACHED_LATE, error["message"])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an activity without a result registry is asked to be a FlutterFragmentActivity`() {
        plugin.onAttachedToActivity(binding(Robolectric.buildActivity(Activity::class.java).create().get()))
        val error = call("prepare", prepareArgs()) as Map<*, *>
        assertEquals(VezmoCheckoutPlugin.NOT_FRAGMENT_ACTIVITY, error["message"])
        val result = call("present", null) as Map<*, *>
        assertEquals(VezmoCheckoutPlugin.NOT_FRAGMENT_ACTIVITY, (result["error"] as Map<*, *>)["message"])
    }

    // ── helpers ──

    /** A created activity, not yet started: where Flutter attaches the engine. */
    private fun created() = Robolectric.buildActivity(ComponentActivity::class.java).create().get()

    private fun binding(activity: Activity) =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ActivityPluginBinding::class.java)) { _, method, _ ->
            if (method.name == "getActivity") activity else null
        } as ActivityPluginBinding

    private fun session() =
        MockResponse().setBody(
            """{"success":true,"data":{"publishableKey":"pk_test_x","livemode":false,"amount":49,"amountMinor":4900,""" +
                """"currency":"USD","title":"Order","merchantName":"Acme","paymentId":"pay_1","enabledMethods":{"card":true}}}""",
        )

    private fun prepareArgs() =
        mapOf(
            "configuration" to mapOf("apiBase" to server.url("/api/v1").toString().trimEnd('/')),
            "clientToken" to "tok_1",
            "subscriptionToken" to null,
            "merchantDisplayName" to null,
        )

    /** Calls the plugin as Dart does and waits for its answer. */
    private fun call(method: String, args: Any?): Any? {
        val answer = Answer()
        plugin.onMethodCall(MethodCall(method, args), answer)
        waitFor { answer.takeIf { it.answered } }
        return answer.value
    }

    /** Runs the main looper (where the plugin works) until [read] has something, while the network answers. */
    private fun <T> waitFor(read: () -> T?): T {
        repeat(1000) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no answer in 10 s")
    }

    private class Answer : MethodChannel.Result {
        var answered = false
        var value: Any? = null

        override fun success(result: Any?) {
            answered = true
            value = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
            throw AssertionError("error: $errorCode $errorMessage")

        override fun notImplemented() = throw AssertionError("not implemented")
    }
}
