package com.portionspot.pos.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mobile-money SMS parser (prompt §6). Uses real-shaped confirmation
 * messages: a rule must extract amount + currency + unique txn code, ignore
 * non-payment messages, and keep the txn code stable (it is the idempotency key).
 */
class MobileMoneyParserTest {

    @Test fun ecocash_usd_received_parses() {
        val body = "Confirmed. You have received USD10.00 from JOHN DOE 263771234567. " +
            "New EcoCash balance: USD35.50. Reference: MP230101.1430.L45678"
        val p = MobileMoneyParser.parse("EcoCash", body, 1000L)!!
        assertEquals("ecocash", p.provider)
        assertEquals(10.00, p.amount, 0.001)
        assertEquals("USD", p.currency)               // grabbed the received amount, not the balance
        assertEquals("MP230101.1430.L45678", p.txnCode)
        assertEquals("JOHN DOE", p.senderName)
        assertEquals("263771234567", p.senderPhone)
        assertEquals(1000L, p.receivedAt)
    }

    @Test fun ecocash_dollar_sign_and_trailing_period_on_ref() {
        val body = "Confirmed. \$25.00 received from 0782123456 MARY M. Ref: PP230202.1000.A12345. " +
            "Thank you for using EcoCash."
        val p = MobileMoneyParser.parse("EcoCash", body)!!
        assertEquals("ecocash", p.provider)
        assertEquals(25.00, p.amount, 0.001)
        assertEquals("USD", p.currency)               // "$" normalised to USD
        assertEquals("PP230202.1000.A12345", p.txnCode) // trailing sentence period stripped
        assertEquals("0782123456", p.senderPhone)
    }

    @Test fun onemoney_zwl_normalises_to_zwg() {
        val body = "You have received ZWL500.00 from 0713000000 PETER. Txn ID: OM12345678. " +
            "Balance: ZWL1000.00"
        val p = MobileMoneyParser.parse("OneMoney", body)!!
        assertEquals("onemoney", p.provider)
        assertEquals(500.00, p.amount, 0.001)
        assertEquals("ZWG", p.currency)               // legacy ZWL mapped to the app's ZWG
        assertEquals("OM12345678", p.txnCode)
    }

    @Test fun innbucks_no_colon_ref_parses() {
        val body = "InnBucks: You received USD5.00 from 0771111111. Ref INB987654."
        val p = MobileMoneyParser.parse(null, body)!!
        assertEquals("innbucks", p.provider)
        assertEquals(5.00, p.amount, 0.001)
        assertEquals("INB987654", p.txnCode)
        assertEquals("0771111111", p.senderPhone)
    }

    @Test fun balance_enquiry_is_not_a_payment() {
        assertNull(MobileMoneyParser.parse("EcoCash", "Your EcoCash balance is USD35.50"))
    }

    @Test fun money_out_is_not_a_payment() {
        assertNull(
            MobileMoneyParser.parse("EcoCash", "Confirmed. You paid USD10.00 to ACME LTD. Ref: MP99")
        )
    }

    @Test fun unknown_wallet_falls_back_but_is_still_captured() {
        val body = "You have received USD40.00 from 0771234567. Transaction ID: BANK55512"
        val p = MobileMoneyParser.parse("CBZ", body)!!
        assertTrue(p.provider == "unknown")
        assertEquals(40.00, p.amount, 0.001)
        assertEquals("BANK55512", p.txnCode)
    }

    @Test fun txn_code_is_stable_across_reparse() {
        val body = "Confirmed. You have received USD10.00 from JOHN DOE 263771234567. Reference: MP230101.1430.L45678"
        val a = MobileMoneyParser.parse("EcoCash", body, 1L)!!
        val b = MobileMoneyParser.parse("EcoCash", body, 2L)!!
        assertEquals(a.txnCode, b.txnCode)            // same SMS → same idempotency key
    }
}
