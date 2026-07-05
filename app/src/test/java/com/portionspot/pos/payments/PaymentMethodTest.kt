package com.portionspot.pos.payments

import com.portionspot.pos.data.Business
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Zimbabwe tender logic: code lookup, which methods capture a reference,
 * the business-level enable/order, and the pay-into account detail builder.
 */
class PaymentMethodTest {
    @Test
    fun fromCode_knownAndUnknown() {
        assertEquals(PaymentMethod.ECOCASH, PaymentMethod.fromCode("ecocash"))
        assertEquals(PaymentMethod.CASH, PaymentMethod.fromCode("cash"))
        assertNull(PaymentMethod.fromCode("bitcoin"))
        assertNull(PaymentMethod.fromCode("credit"))   // credit is a separate concept, not a tender
    }

    @Test
    fun mobileMoney_flagSet() {
        listOf(
            PaymentMethod.ECOCASH, PaymentMethod.INNBUCKS,
            PaymentMethod.ONEMONEY, PaymentMethod.OMARI
        ).forEach { assertTrue(it.name, it.isMobileMoney) }
        assertFalse(PaymentMethod.CASH.isMobileMoney)
        assertFalse(PaymentMethod.CARD.isMobileMoney)
    }

    @Test
    fun capturesReference_forMobileBankPaynow_notCashOrCard() {
        assertTrue(PaymentMethod.BANK.capturesReference)
        assertTrue(PaymentMethod.PAYNOW.capturesReference)
        assertTrue(PaymentMethod.ECOCASH.capturesReference)
        assertFalse(PaymentMethod.CASH.capturesReference)
        assertFalse(PaymentMethod.CARD.capturesReference)
    }

    @Test
    fun enabledPaymentMethods_filtersAndOrders() {
        val b = Business(cashEnabled = true, ecocashEnabled = true, paynowEnabled = true)
        // Display order is cash, card, bank, paynow, ecocash, ... — only enabled ones appear.
        assertEquals(
            listOf(PaymentMethod.CASH, PaymentMethod.PAYNOW, PaymentMethod.ECOCASH),
            b.enabledPaymentMethods()
        )
    }

    @Test
    fun payInstructions_bankSkipsBlankFields() {
        val b = Business(bankName = "CBZ", bankAccountNumber = "123456", bankBranch = "")
        val pi = b.payInstructions(PaymentMethod.BANK)!!
        assertEquals("Bank transfer", pi.title)
        // Blank branch + null account name are skipped; only the filled rows remain.
        assertEquals(listOf("Bank" to "CBZ", "Account number" to "123456"), pi.lines)
    }

    @Test
    fun payInstructions_cashHasNone() {
        assertNull(Business().payInstructions(PaymentMethod.CASH))
    }

    @Test
    fun fromCode_roundTripsEveryCode() {
        // Every enum's stored code must map back to itself — guards against a typo
        // in a code string silently breaking sync/receipt lookups.
        PaymentMethod.entries.forEach { m ->
            assertEquals(m.name, m, PaymentMethod.fromCode(m.code))
        }
    }

    @Test
    fun enabledPaymentMethods_noneWhenAllOff() {
        // cashEnabled defaults true, so a truly-empty business must switch it off too.
        val b = Business(cashEnabled = false)
        assertEquals(emptyList<PaymentMethod>(), b.enabledPaymentMethods())
    }

    @Test
    fun enabledPaymentMethods_allEightInDisplayOrder() {
        val b = Business(
            cashEnabled = true, cardEnabled = true, bankEnabled = true,
            paynowEnabled = true, ecocashEnabled = true, innbucksEnabled = true,
            onemoneyEnabled = true, omariEnabled = true
        )
        assertEquals(
            listOf(
                PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.BANK,
                PaymentMethod.PAYNOW, PaymentMethod.ECOCASH, PaymentMethod.INNBUCKS,
                PaymentMethod.ONEMONEY, PaymentMethod.OMARI
            ),
            b.enabledPaymentMethods()
        )
    }

    @Test
    fun payInstructions_ecocashListsAccountPhoneMerchant() {
        val b = Business(
            ecocashAccountName = "Spot Motors",
            ecocashPhone = "0771234567",
            ecocashMerchantCode = "12345"
        )
        val pi = b.payInstructions(PaymentMethod.ECOCASH)!!
        assertEquals("EcoCash", pi.title)
        assertEquals(
            listOf(
                "Account name" to "Spot Motors",
                "Phone" to "0771234567",
                "Merchant code" to "12345"
            ),
            pi.lines
        )
    }

    @Test
    fun payInstructions_onemoneyTitleAndLines() {
        val b = Business(onemoneyAccountName = "Spot Motors", onemoneyPhone = "0712345678")
        val pi = b.payInstructions(PaymentMethod.ONEMONEY)!!
        assertEquals("OneMoney", pi.title)
        assertEquals(
            listOf("Account name" to "Spot Motors", "Phone" to "0712345678"),
            pi.lines
        )
    }

    @Test
    fun payInstructions_paynowAndCardHaveNone() {
        // PAYNOW captures a reference but has no manual pay-into details to show,
        // so it (like CARD) returns null — a distinction worth pinning.
        assertTrue(PaymentMethod.PAYNOW.capturesReference)
        assertNull(Business().payInstructions(PaymentMethod.PAYNOW))
        assertNull(Business().payInstructions(PaymentMethod.CARD))
    }
}
