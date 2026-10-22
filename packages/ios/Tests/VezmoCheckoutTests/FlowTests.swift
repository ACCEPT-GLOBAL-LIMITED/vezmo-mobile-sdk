import UIKit
import XCTest
@testable import VezmoCheckout

let indiaOffer = LocalOffer(currency: "INR", amountMinor: 420000, rate: 85.7, country: "IN", methods: ["upi"], token: "offer_sealed")

func makeConfig(
  enabled: [String: Bool?] = ["card": true, "applePay": true, "googlePay": true, "klarna": true, "cashapp": true, "upi": true],
  localOffer: LocalOffer? = indiaOffer
) -> CheckoutConfig {
  CheckoutConfig(
    publishableKey: "pk_test_x",
    connectedAccountId: "acct_1",
    livemode: false,
    amountMinor: 4900,
    currency: "USD",
    title: "Order",
    merchantName: "Acme",
    enabledMethods: enabled,
    customerName: nil,
    customerEmail: nil,
    theme: nil,
    paymentId: "pay_1",
    localOffer: localOffer
  )
}

final class FlowTests: XCTestCase {
  func testApplePaysMerchantCountryIsTheAppsElseTheSessionsElseUS() {
    XCTAssertEqual(merchantCountry(nil, session: "ae"), "AE")
    XCTAssertEqual(merchantCountry("GB", session: "AE"), "GB")
    XCTAssertEqual(merchantCountry("usa", session: "AE"), "AE")
    XCTAssertEqual(merchantCountry(" ", session: nil), "US")
    XCTAssertEqual(merchantCountry(nil, session: nil), "US")
  }

  func testTheProcessingCountryIsReadFromTheCheckoutAndTheSubscription() throws {
    let config = try JSONDecoder().decode(CheckoutConfig.self, from: Data(#"""
      {"publishableKey":"pk","livemode":false,"amountMinor":100,"currency":"AED","paymentId":"p","processingCountry":"AE"}
      """#.utf8))
    XCTAssertEqual(config.processingCountry, "AE")
    let older = try JSONDecoder().decode(CheckoutConfig.self, from: Data(#"""
      {"publishableKey":"pk","livemode":false,"amountMinor":100,"currency":"USD","paymentId":"p"}
      """#.utf8))
    XCTAssertNil(older.processingCountry)
  }

  func testChargeIsThePriceOrTheBuyersOwnCurrency() {
    XCTAssertEqual(chargeOf(makeConfig(), payLocal: false), Charge(amountMinor: 4900, currency: "USD", localOffer: nil))
    XCTAssertEqual(chargeOf(makeConfig(), payLocal: true), Charge(amountMinor: 420000, currency: "INR", localOffer: "offer_sealed"))
    XCTAssertNil(chargeOf(makeConfig(localOffer: nil), payLocal: true).localOffer)
  }

  func testInDollarsCardPayLaterCashAppAndWalletsButNoUPI() {
    let config = makeConfig()
    XCTAssertEqual(
      sheetMethods(config, chargeOf(config, payLocal: false)),
      SheetMethods(elementTypes: ["card", "klarna", "cashapp"], applePay: true, googlePay: true, upi: false, pix: false)
    )
  }

  func testInRupeesCardOnlyPlusOurUPIPanel() {
    let config = makeConfig()
    let methods = sheetMethods(config, chargeOf(config, payLocal: true))
    XCTAssertEqual(methods.elementTypes, ["card"])
    XCTAssertTrue(methods.upi)
  }

  func testUPINeedsTheBusinessToOfferItAndTheOfferToCarryIt() {
    let off = makeConfig(enabled: ["card": true])
    XCTAssertFalse(sheetMethods(off, chargeOf(off, payLocal: true)).upi)
    var offer = indiaOffer
    offer.methods = []
    let noOfferMethod = makeConfig(localOffer: offer)
    XCTAssertFalse(sheetMethods(noOfferMethod, chargeOf(noOfferMethod, payLocal: true)).upi)
  }

  func testPixOnlyInReais() {
    let brazil = LocalOffer(currency: "brl", amountMinor: 26500, rate: 5.4, country: "BR", methods: ["pix"], token: "o")
    let config = makeConfig(enabled: ["card": true, "pix": true, "klarna": true], localOffer: brazil)
    let methods = sheetMethods(config, chargeOf(config, payLocal: true))
    XCTAssertEqual(methods.elementTypes, ["card"])
    XCTAssertTrue(methods.pix)
    XCTAssertFalse(methods.upi)
  }

  func testInstantACHOnlyInDollarsWhenTheBusinessOffersIt() {
    let config = makeConfig(enabled: ["card": true, "instantAch": true])
    XCTAssertTrue(sheetMethods(config, chargeOf(config, payLocal: false)).instantAch)
    // Never in the buyer's own currency.
    XCTAssertFalse(sheetMethods(config, chargeOf(config, payLocal: true)).instantAch)
    let off = makeConfig(enabled: ["card": true])
    XCTAssertFalse(sheetMethods(off, chargeOf(off, payLocal: false)).instantAch)
    // It is the sheet's own row, never one of the element's.
    XCTAssertEqual(sheetMethods(config, chargeOf(config, payLocal: false)).elementTypes, ["card"])
    XCTAssertTrue(EngineIntent(charge: chargeOf(config, payLocal: false), methods: sheetMethods(config, chargeOf(config, payLocal: false))).instantAch)
  }

  func testTheLinkedBankShowsOnlyItsLastFourNeverTheEnginesWords() {
    XCTAssertEqual(bankLabel("••••6789"), "•••• 6789")
    XCTAssertEqual(bankLabel("Success Bank ••••6789 "), "•••• 6789")
    XCTAssertNil(bankLabel("Link"))
    XCTAssertNil(bankLabel("Bank"))
    XCTAssertNil(bankLabel(nil))
  }

  func testServerAnswersMapToWhatTheMerchantGets() {
    XCTAssertEqual(
      resultFromPay(PayResponse(state: "succeeded", paymentId: "p", transactionId: "VT1", clientSecret: nil)),
      .succeeded(paymentId: "p", transactionId: "VT1", subscription: nil)
    )
    XCTAssertEqual(resultFromPay(PayResponse(state: "processing", paymentId: "p", clientSecret: nil)), .processing(paymentId: "p", subscription: nil))
    XCTAssertNil(resultFromPay(PayResponse(state: "requires_action", paymentId: "p", clientSecret: "pi_1_secret_x")))
    XCTAssertNil(resultFromStatus(SessionStatus(state: "open", paymentId: "p", transactionId: nil)))
    guard case .failed(let error)? = resultFromStatus(SessionStatus(state: "failed", paymentId: "p", transactionId: nil)) else {
      return XCTFail("a failed session is a failed result")
    }
    XCTAssertEqual(error.code, .declined)
    XCTAssertEqual(resultFromStatus(SessionStatus(state: "canceled", paymentId: "p", transactionId: nil)), .failed(expiredError))
  }

  func testReadsTheIntentIdOnlyFromARealClientSecret() {
    XCTAssertEqual(intentIdOf("pi_3Abc_secret_xyZ_1"), "pi_3Abc")
    XCTAssertNil(intentIdOf("pi_3Abc"))
    XCTAssertNil(intentIdOf(nil))
  }

  func testFormatsAmountsInTheirCurrency() {
    XCTAssertEqual(formatAmount(4900, "USD", locale: Locale(identifier: "en_US")), "$49.00")
    XCTAssertEqual(formatAmount(420000, "INR", locale: Locale(identifier: "en_IN")), "₹4,200.00")
  }

  func testCardNetworkMarks() {
    XCTAssertEqual(Mark.forBrand("visa"), .visa)
    XCTAssertEqual(Mark.forBrand("American Express"), .amex)
    XCTAssertEqual(Mark.forBrand("master_card"), .mastercard)
    XCTAssertNil(Mark.forBrand("unionpay"))
    XCTAssertNil(Mark.forBrand(nil))
    // The marks' bytes decode (the package ships no image asset).
    for mark in Mark.allCases { XCTAssertGreaterThan(mark.image.size.width, 0) }
    XCTAssertEqual(VezmoLogo.image.size.height, 14, accuracy: 0.5)
  }

  func testAMerchantAccentReplacesTheGradient() {
    let plain = Palette.resolve(dark: false, appearance: nil)
    XCTAssertTrue(plain.accentGradient)
    let own = Palette.resolve(
      dark: true,
      appearance: .init(colors: [.accent: UIColor { $0.userInterfaceStyle == .dark ? .systemOrange : .systemBlue }])
    )
    XCTAssertFalse(own.accentGradient)
    XCTAssertEqual(own.accent, UIColor.systemOrange.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark)))
    XCTAssertNil(own.payShadow) // no lift in dark
  }
}
