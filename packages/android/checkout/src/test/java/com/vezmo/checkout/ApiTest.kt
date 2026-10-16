package com.vezmo.checkout

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiTest {
    private class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private fun api(status: Int, body: String, calls: MutableList<Recorded> = mutableListOf()) =
        VezmoApi(
            "https://api.test/v1",
            { method, url, headers, b ->
                calls += Recorded(method, url, headers, b)
                VezmoApi.Response(status, body)
            },
            Dispatchers.Unconfined,
        )

    private suspend fun failure(block: suspend () -> Unit): VezmoApiError {
        try {
            block()
        } catch (e: VezmoApiError) {
            return e
        }
        fail("expected a VezmoApiError")
        throw IllegalStateException()
    }

    @Test
    fun `pays with the token, our integration and SDK header, and unwraps the data`() = runTest {
        val calls = mutableListOf<Recorded>()
        val res = api(200, """{"success":true,"data":{"state":"processing","paymentId":"p","clientSecret":null}}""", calls)
            .pay("tok/1", "ctoken_1", "o", "acme://vezmo")
        assertEquals("processing", res.state)
        assertEquals(null, res.clientSecret)
        val call = calls.single()
        assertEquals("https://api.test/v1/secure-payments/tok%2F1/pay", call.url)
        assertEquals("POST", call.method)
        assertTrue(call.headers["Vezmo-SDK"]!!.matches(Regex("""^android/\d+\.\d+\.\d+.*""")))
        val body = JSONObject(call.body!!)
        // No returnUrl: the confirmation token's own return link brings the buyer back into the sheet.
        assertEquals(setOf("confirmationToken", "integration", "localOffer"), body.keys().asSequence().toSet())
        assertEquals("ctoken_1", body.getString("confirmationToken"))
        assertEquals("android", body.getString("integration"))
        assertEquals("o", body.getString("localOffer"))
    }

    @Test
    fun `a Vezmo wrapper names itself in the SDK header`() = runTest {
        val calls = mutableListOf<Recorded>()
        val wrapped = VezmoApi(
            "https://api.test/v1",
            { method, url, headers, b ->
                calls += Recorded(method, url, headers, b)
                VezmoApi.Response(410, "{}")
            },
            Dispatchers.Unconfined,
            sdk = "flutter/0.1.0",
        )
        failure { wrapped.status("t") }
        assertEquals("flutter/0.1.0", calls.single().headers["Vezmo-SDK"])
    }

    @Test
    fun `turns failures into Vezmo's own codes and words`() = runTest {
        assertEquals(
            VezmoError.Code.Expired,
            failure { api(410, """{"message":"Secure-payments token has expired"}""").status("t") }.code,
        )
        val declined = failure { api(400, """{"message":"Your bank declined this card."}""").pay("t", "c", null, null) }
        assertEquals(VezmoError.Code.Declined, declined.code)
        assertEquals("Your bank declined this card.", declined.message)
        val priceChanged = failure {
            api(409, """{"message":"Price changed","errors":[{"code":"local_price_changed","message":"x"}]}""").pay("t", "c", null, null)
        }
        assertEquals("local_price_changed", priceChanged.reason)
        assertEquals(VezmoError.Code.Unknown, priceChanged.code)
        assertEquals(VezmoError.Code.AlreadyPaid, failure { api(409, "{}").status("t") }.code)
        assertEquals(VezmoError.Code.Config, failure { api(404, "not json").status("t") }.code)
        val offline = VezmoApi("https://api.test/v1", { _, _, _, _ -> throw IOException("x") }, Dispatchers.Unconfined)
        assertEquals(VezmoError.Code.Network, failure { offline.status("t") }.code)
    }

    @Test
    fun `a merchant-of-record session - JSON nulls stay null, never the text null`() = runTest {
        val config = api(
            200,
            """{"success":true,"data":{"publishableKey":"pk_test_x","connectedAccountId":null,"livemode":false,
            "amountMinor":4900,"currency":"USD","title":"Order","merchantName":null,"enabledMethods":{"card":true},
            "customerName":null,"customerEmail":"","theme":null,"paymentId":"pay_1",
            "localOffer":{"currency":"INR","amountMinor":420000,"rate":85.7,"country":null,"methods":["upi"],"token":"o"},
            "appReturnUrl":null}}""",
        ).checkout("tok")
        assertEquals(null, config.connectedAccountId)
        assertEquals(null, config.merchantName)
        assertEquals(null, config.customerName)
        assertEquals(null, config.customerEmail)
        assertEquals(null, config.theme)
        assertEquals(null, config.localOffer!!.country)
        val intent = api(200, """{"success":true,"data":{"id":"pi_1","clientSecret":"pi_1_secret_x","connectedAccountId":null,"livemode":false}}""")
            .intent("tok", null)
        assertEquals(null, intent.connectedAccountId)
        val paid = api(200, """{"success":true,"data":{"state":"succeeded","paymentId":"p","transactionId":null,"clientSecret":null,"method":{"type":"card","brand":null,"last4":null,"wallet":null}}}""")
            .pay("tok", "c", null, null)
        assertEquals(null, paid.transactionId)
        assertEquals(PaidMethod("card", null, null, null), paid.method)
    }

    @Test
    fun `reads the checkout session and sends no body to load it`() = runTest {
        val calls = mutableListOf<Recorded>()
        val json = """{"success":true,"data":{"publishableKey":"pk_test_x","connectedAccountId":null,"livemode":false,
            "amount":49,"amountMinor":4900,"currency":"usd","title":"Order","merchantName":"Acme",
            "enabledMethods":{"card":true,"klarna":false,"googlePay":true},"customerName":null,"customerEmail":null,
            "theme":null,"paymentId":"pay_1","localOffer":null,"appReturnUrl":null}}"""
        val config = api(200, json, calls).checkout("tok")
        assertEquals("https://api.test/v1/secure-payments/tok/checkout?redirectMethods=1", calls.single().url)
        assertEquals(null, calls.single().body)
        assertEquals(null, config.connectedAccountId)
        assertEquals(null, config.customerName)
        assertEquals(setOf("card", "googlePay"), config.enabledMethods)
        assertEquals(4900L, config.amountMinor)
    }
}
