package com.vezmo.checkout

internal val INR_OFFER = LocalOffer(
    currency = "INR",
    amountMinor = 420000,
    rate = 85.7,
    country = "IN",
    methods = listOf("upi"),
    token = "offer_sealed",
)

internal fun config(
    enabledMethods: Set<String> = setOf("card", "googlePay", "klarna", "cashapp", "upi"),
    localOffer: LocalOffer? = INR_OFFER,
) = CheckoutConfig(
    publishableKey = "pk_test_x",
    connectedAccountId = "acct_1",
    livemode = false,
    amountMinor = 4900,
    currency = "USD",
    title = "Order",
    merchantName = "Acme",
    enabledMethods = enabledMethods,
    customerName = null,
    customerEmail = null,
    theme = null,
    paymentId = "pay_1",
    localOffer = localOffer,
)
