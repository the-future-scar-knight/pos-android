package com.portionspot.pos.sms

/**
 * Rule-based parser for mobile-money confirmation SMS (prompt §6 — the flagship
 * reconciliation feature).
 *
 * Design intent: **extensible by adding a [SmsRule] to [RULES], never by rewriting
 * the engine.** EcoCash is shipped first; OneMoney, InnBucks, Omari and bank alerts
 * are just more rules. The parser is pure Kotlin (no Android deps) so it is fully
 * unit-tested — see MobileMoneyParserTest.
 *
 * A rule only fires for a **money-IN** message (the shop received a payment): the
 * body must contain a "received"-style keyword AND a "from" clause, so a balance
 * enquiry or a money-OUT alert never becomes a phantom receipt.
 */

/** A payment extracted from one SMS. [txnCode] is the provider's unique reference. */
data class ParsedPayment(
    val provider: String,           // ecocash | onemoney | innbucks | omari | bank | unknown
    val sender: String?,            // SMS originating address (e.g. "EcoCash")
    val senderName: String?,        // payer name parsed from the body, if present
    val senderPhone: String?,       // payer number parsed from the body, if present
    val amount: Double,
    val currency: String,           // normalised: USD | ZWG | ...
    val txnCode: String,
    val receivedAt: Long
)

object MobileMoneyParser {

    /** Parse [body] (with optional SMS [sender] address). Returns null if no rule matches. */
    fun parse(sender: String?, body: String?, receivedAt: Long = System.currentTimeMillis()): ParsedPayment? {
        if (body.isNullOrBlank()) return null
        for (rule in RULES) {
            rule.tryParse(sender, body, receivedAt)?.let { return it }
        }
        return null
    }

    /**
     * The unique reference on many Zimbabwean wallet/bank SMS is labelled "Approval
     * Code", not "Ref"/"Txn ID" — e.g. EcoCash USD "Cashin Confirmation" messages.
     * Without this the message parses everything EXCEPT the txn code and is dropped
     * (verified on a real device, 2026-07). Shared so every rule can accept it.
     */
    private val APPROVAL_CODE =
        Regex("""(?i)\bapproval\s*code\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})""")

    // Order matters only for disambiguation; keywords keep them from cross-firing.
    val RULES: List<SmsRule> = listOf(
        // EcoCash USD agent/merchant "Cashin Confirmation" format — distinct wording:
        // "Cashin Confirmation: USD 358.00 received from 062340-AMBASSADOR PROFESSOR.
        //  Approval Code: CI260706.0923.T1610618. New balance: USD 361.98."
        // The payer id is an agent/till code (not a phone), so it stays unmatched-by-
        // phone and awaits manual assignment. Placed first: its "cashin confirmation"
        // hint is specific, so it never steals a normal EcoCash "you have received" SMS.
        SmsRule(
            provider = "ecocash",
            hints = listOf("cashin confirmation", "cash-in confirmation", "cash in confirmation"),
            txnRegexes = listOf(APPROVAL_CODE)
        ),
        SmsRule(
            provider = "ecocash",
            hints = listOf("ecocash"),
            txnRegexes = listOf(
                Regex("""(?i)\b(?:ref(?:erence)?|txn(?:\s*id)?)\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})"""),
                APPROVAL_CODE
            )
        ),
        SmsRule(
            provider = "onemoney",
            hints = listOf("onemoney", "one money", "netone"),
            txnRegexes = listOf(
                Regex("""(?i)\b(?:txn\s*id|ref(?:erence)?)\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})""")
            )
        ),
        SmsRule(
            provider = "innbucks",
            hints = listOf("innbucks", "inn bucks"),
            txnRegexes = listOf(
                Regex("""(?i)\b(?:ref(?:erence)?|voucher|txn(?:\s*id)?)\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})""")
            )
        ),
        SmsRule(
            provider = "omari",
            hints = listOf("omari"),
            txnRegexes = listOf(
                Regex("""(?i)\b(?:ref(?:erence)?|txn(?:\s*id)?)\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})""")
            )
        ),
        // Generic fallback: any confirmation-shaped message with a reference. Lets a
        // bank alert or an unknown wallet still be captured (as `unknown`/`bank`) and
        // reconciled manually rather than silently dropped (§6.5 — nothing discarded).
        SmsRule(
            provider = "unknown",
            hints = emptyList(),
            txnRegexes = listOf(
                Regex("""(?i)\b(?:ref(?:erence)?|txn(?:\s*id)?|transaction\s*id)\s*[:.]?\s*([A-Za-z0-9][A-Za-z0-9.\-]{3,})"""),
                APPROVAL_CODE
            )
        )
    )
}

/**
 * One provider's SMS shape. [hints] are lowercase substrings that identify the
 * provider (matched against sender address + body); empty [hints] means the rule
 * accepts any message (the generic fallback). [txnRegexes] are tried in order for
 * the unique transaction code — the first with a non-blank group 1 wins.
 */
class SmsRule(
    val provider: String,
    private val hints: List<String>,
    private val txnRegexes: List<Regex>,
) {
    fun tryParse(sender: String?, body: String, receivedAt: Long): ParsedPayment? {
        val lower = body.lowercase()
        // Must be a money-IN confirmation, not a balance enquiry or a payment-out.
        val isCredit = (lower.contains("received") || lower.contains("you have got") ||
            lower.contains("credited")) && lower.contains("from")
        if (!isCredit) return null

        if (hints.isNotEmpty()) {
            val hay = (sender.orEmpty() + " " + body).lowercase()
            if (hints.none { hay.contains(it) }) return null
        }

        val (currency, amount) = extractAmount(body) ?: return null
        val txn = txnRegexes.firstNotNullOfOrNull { rx ->
            // The char class allows internal dots/hyphens (real refs have them), so
            // strip only surrounding punctuation left by the sentence (e.g. "…A12345.").
            rx.find(body)?.groupValues?.getOrNull(1)?.trim()?.trim('.', ',', '-')?.ifBlank { null }
        } ?: return null

        return ParsedPayment(
            provider = provider,
            sender = sender?.trim()?.ifBlank { null },
            senderName = extractName(body),
            senderPhone = extractPhone(body),
            amount = amount,
            currency = currency,
            txnCode = txn,
            receivedAt = receivedAt
        )
    }

    companion object {
        // Currency-prefixed (USD100.00 / $25 / ZWL 50,00) then amount-suffixed (100.00 USD).
        private val AMOUNT_PREFIXED =
            Regex("""(?i)(USD|US\$|ZWG|ZWL|ZWD|RTGS|ZW\$|\$)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
        private val AMOUNT_SUFFIXED =
            Regex("""(?i)([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(USD|ZWG|ZWL|ZWD|RTGS)""")

        /** Money-IN clause: "from <name> <number>" / "from <number> <name>". */
        private val PHONE = Regex("""(?i)from\b[^0-9]{0,40}?([0-9]{9,13})""")
        // The name may follow an optional leading agent/till code + separator, e.g.
        // "from 062340-AMBASSADOR PROFESSOR" or "from 0782123456 MARY M".
        private val NAME = Regex("""(?i)from\s+(?:[0-9][\w]*[- ]+)?([A-Za-z][A-Za-z .'\-]{1,40}?)(?=\s*(?:[0-9]|,|\.|\bon\b|\bnew\b|$))""")

        /** Returns (normalisedCurrency, amount) or null. Amount handles thousands commas. */
        fun extractAmount(body: String): Pair<String, Double>? {
            val m = AMOUNT_PREFIXED.find(body)
            if (m != null) {
                val amt = m.groupValues[2].replace(",", "").toDoubleOrNull()
                if (amt != null) return normaliseCurrency(m.groupValues[1]) to amt
            }
            val s = AMOUNT_SUFFIXED.find(body)
            if (s != null) {
                val amt = s.groupValues[1].replace(",", "").toDoubleOrNull()
                if (amt != null) return normaliseCurrency(s.groupValues[2]) to amt
            }
            return null
        }

        /** `$`/`US$`/`USD` → USD; legacy Zim tokens (ZWL/RTGS/ZWD/ZW$) → ZWG. */
        fun normaliseCurrency(raw: String): String = when (raw.uppercase().trim()) {
            "$", "US$", "USD" -> "USD"
            "ZWL", "ZWD", "RTGS", "ZW$", "ZWG" -> "ZWG"
            else -> raw.uppercase().trim()
        }

        private fun extractPhone(body: String): String? =
            PHONE.find(body)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

        private fun extractName(body: String): String? =
            NAME.find(body)?.groupValues?.getOrNull(1)?.trim()
                ?.trim('.', ',', ' ', '-')
                ?.takeIf { it.length >= 2 && it.any(Char::isLetter) }
    }
}
