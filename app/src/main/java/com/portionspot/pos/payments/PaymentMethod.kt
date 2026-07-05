package com.portionspot.pos.payments

import com.portionspot.pos.data.Business

/**
 * A tender option offered at checkout. The [code] is what gets stored in
 * [com.portionspot.pos.data.SaleEntity.paymentMethod] and synced; the [label]
 * is what the cashier and the printed receipt see.
 *
 * NB: "credit" (sell-on-account) is NOT in this list — it is a separate concept
 * handled by the existing customer/credit flow, not a tender type.
 */
enum class PaymentMethod(val code: String, val label: String) {
    CASH("cash", "Cash"),
    CARD("card", "Card / Swipe"),
    BANK("bank", "Bank Transfer"),
    PAYNOW("paynow", "Paynow"),
    ECOCASH("ecocash", "EcoCash"),
    INNBUCKS("innbucks", "InnBucks"),
    ONEMONEY("onemoney", "OneMoney"),
    OMARI("omari", "Omari");

    /** Phone-number mobile-money methods that show account details + capture a reference. */
    val isMobileMoney: Boolean
        get() = this == ECOCASH || this == INNBUCKS || this == ONEMONEY || this == OMARI

    /** Methods where the cashier records a transaction reference to complete the sale. */
    val capturesReference: Boolean
        get() = isMobileMoney || this == BANK || this == PAYNOW

    companion object {
        fun fromCode(code: String): PaymentMethod? = entries.firstOrNull { it.code == code }
    }
}

/** The payment methods this business has switched on, in display order. */
fun Business.enabledPaymentMethods(): List<PaymentMethod> = buildList {
    if (cashEnabled) add(PaymentMethod.CASH)
    if (cardEnabled) add(PaymentMethod.CARD)
    if (bankEnabled) add(PaymentMethod.BANK)
    if (paynowEnabled) add(PaymentMethod.PAYNOW)
    if (ecocashEnabled) add(PaymentMethod.ECOCASH)
    if (innbucksEnabled) add(PaymentMethod.INNBUCKS)
    if (onemoneyEnabled) add(PaymentMethod.ONEMONEY)
    if (omariEnabled) add(PaymentMethod.OMARI)
}

/** Pay-into account details shown to the buyer for a manual method. */
data class PayInstructions(val title: String, val lines: List<Pair<String, String>>)

/** Build the account details to display for [method], or null if it has none. */
fun Business.payInstructions(method: PaymentMethod): PayInstructions? = when (method) {
    PaymentMethod.BANK -> PayInstructions("Bank transfer", buildList {
        bankName?.takeIf { it.isNotBlank() }?.let { add("Bank" to it) }
        bankBranch?.takeIf { it.isNotBlank() }?.let { add("Branch" to it) }
        bankAccountName?.takeIf { it.isNotBlank() }?.let { add("Account name" to it) }
        bankAccountNumber?.takeIf { it.isNotBlank() }?.let { add("Account number" to it) }
    })
    PaymentMethod.ECOCASH -> PayInstructions("EcoCash", buildList {
        ecocashAccountName?.takeIf { it.isNotBlank() }?.let { add("Account name" to it) }
        ecocashPhone?.takeIf { it.isNotBlank() }?.let { add("Phone" to it) }
        ecocashMerchantCode?.takeIf { it.isNotBlank() }?.let { add("Merchant code" to it) }
    })
    PaymentMethod.INNBUCKS -> PayInstructions("InnBucks", buildList {
        innbucksAccountName?.takeIf { it.isNotBlank() }?.let { add("Account name" to it) }
        innbucksPhone?.takeIf { it.isNotBlank() }?.let { add("Phone" to it) }
    })
    PaymentMethod.ONEMONEY -> PayInstructions("OneMoney", buildList {
        onemoneyAccountName?.takeIf { it.isNotBlank() }?.let { add("Account name" to it) }
        onemoneyPhone?.takeIf { it.isNotBlank() }?.let { add("Phone" to it) }
    })
    PaymentMethod.OMARI -> PayInstructions("Omari", buildList {
        omariAccountName?.takeIf { it.isNotBlank() }?.let { add("Account name" to it) }
        omariPhone?.takeIf { it.isNotBlank() }?.let { add("Phone" to it) }
    })
    else -> null
}
