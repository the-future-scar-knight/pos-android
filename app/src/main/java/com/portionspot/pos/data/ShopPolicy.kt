package com.portionspot.pos.data

/**
 * The rules about money that belong to the SHOP, not to a handset — and the rule that
 * decides which side's copy of them wins.
 *
 * ── WHY THESE THREE ARE NOT DEVICE PREFERENCES ────────────────────────────────
 *
 * [com.portionspot.pos.ui.ShopPrefs] is device-local key/value storage and it is right
 * for what it mostly holds: which printer this phone drives, how big its receipt font is,
 * what colour its theme is. None of that is a fact about the business — two phones in one
 * shop are allowed to disagree about it, and nobody loses money when they do.
 *
 * These three are different in kind. They are ENFORCEMENT rules, and enforcement that
 * only exists on one handset is not enforcement:
 *
 *  - The owner raises the per-line discount cap on his phone and walks away believing the
 *    shop's cap is now $5. The cashier's phone never heard, and still refuses at $2 — or,
 *    worse, the owner LOWERS it after a bad week and the cashier's till goes on allowing
 *    the old, larger discount on every line of every sale for as long as the two phones
 *    are apart. Nothing errors. The books simply come up short and the discount report is
 *    the only place it shows.
 *  - The PIN gate percentage is the sibling control to that cap: one is the ceiling the
 *    till physically enforces, the other is the point at which a manager has to approve.
 *    Syncing one without the other gives a shop where the gate and the cap disagree about
 *    what a "large" discount is, which is worse than either being wrong on its own.
 *  - The cash-variance note threshold decides when a short drawer has to be EXPLAINED. Per
 *    device, the phone that closes the day decides whether the shop owes anyone an
 *    explanation — so the same $3 shortage is a written note on one till and silence on
 *    the other, and the audit trail records whichever phone happened to be in hand.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────
 *
 * The shared `businesses` row carries a great deal more: receipt header and footer, the
 * bank and mobile-money account details, the VAT number and percentage, the `lock_*`
 * capability flags, the logo, the currency and the paper width. **None of them sync, and
 * that is a decision, not an omission.** Paper width and the receipt look are genuinely
 * per-device (one phone drives a 58mm belt printer and another an 80mm counter unit). The
 * rest — the locks especially, which are shop-wide capability switches and have exactly
 * the same argument for syncing that these three do — nobody has decided yet, and quietly
 * widening the scope of a sync that touches money settings is not a decision to make by
 * accident. When someone decides, the pull and push below extend by three lines each.
 */
data class ShopPolicy(
    /** Hard ceiling, in base-currency units, on the discount a cashier may take off ONE
     *  cart line. The till physically refuses to exceed it. 0 = no limit. */
    val maxItemDiscount: Double,
    /** Discount, as a % of the line's goods value, above which a manager/admin PIN is
     *  required to approve. Admins are never gated. 0 = never gated. */
    val discountThresholdPct: Double,
    /** How far a close-of-day count may miss before a written note is required. */
    val varianceNoteThreshold: Double,
)

/**
 * What the shared row actually SAID about each rule. Null is "not stated" and is not the
 * same as a value — see [planShopPolicySync] for why the difference costs money.
 */
data class ShopPolicyWireValues(
    val maxItemDiscount: Double? = null,
    val discountThresholdPct: Double? = null,
    val varianceNoteThreshold: Double? = null,
)

/** What a device should DO about the shop's policy this pass. */
sealed class ShopPolicyPlan {
    /** The two sides already agree, or there is nothing to compare against. */
    object Settled : ShopPolicyPlan()

    /** The shared row is newer: write these values into this device's settings. */
    data class Adopt(val policy: ShopPolicy) : ShopPolicyPlan()

    /** This device changed the policy more recently: send these three columns up. */
    data class Push(val policy: ShopPolicy) : ShopPolicyPlan()
}

/**
 * Last-writer-wins on the shared clocks, exactly as every other table here resolves.
 *
 * ── WHY NOT PULL-ONLY ─────────────────────────────────────────────────────────
 *
 * Pull-only is the tempting shape — "the web owns the business row" — and it is wrong for
 * this shop. The owner administers from his phone as often as from the browser. Under
 * pull-only, he raises the cap on the handset, the till enforces the new one until the
 * next sync, and then the pull silently puts the old number back. He has no way to tell
 * that happened short of re-opening the screen and noticing the figure moved on its own.
 *
 * ── THE COMPARISON ────────────────────────────────────────────────────────────
 *
 * [localChangedAt] is stamped only when a person actually CHANGES one of these three on
 * this device; [wireChangedAt] is `businesses.updated_at`, the server clock. A device
 * that has never touched the policy carries 0 and therefore always adopts, which is what
 * a new phone joining the shop should do.
 *
 * A push settles after exactly one pass: the server stamps `updated_at = now()` on the
 * PATCH, so the next comparison has the wire ahead and adopts back the same values.
 *
 * ── WHY A NULL FIELD IS NOT A ZERO ────────────────────────────────────────────
 *
 * Each field is resolved on its own, and a null is "the row does not state this". Read a
 * null as a value instead and the till adopts a cap of 0 — which in this app means NO
 * LIMIT — from a database that simply predates the column. The cashier's per-line
 * discount ceiling would come off entirely, on every phone, with nothing to show for it
 * but a quieter till.
 */
fun planShopPolicySync(
    local: ShopPolicy,
    localChangedAt: Long,
    wire: ShopPolicyWireValues?,
    wireChangedAt: Long,
): ShopPolicyPlan = when {
    // No row to compare against — the shop has not been identified in the database yet,
    // or the read failed. Doing nothing keeps whatever this till is already enforcing;
    // guessing would change a money rule on the strength of a failed request.
    wire == null -> ShopPolicyPlan.Settled
    localChangedAt > wireChangedAt -> ShopPolicyPlan.Push(local)
    else -> {
        val adopted = ShopPolicy(
            maxItemDiscount = wire.maxItemDiscount ?: local.maxItemDiscount,
            discountThresholdPct = wire.discountThresholdPct ?: local.discountThresholdPct,
            varianceNoteThreshold = wire.varianceNoteThreshold ?: local.varianceNoteThreshold,
        )
        // Nothing moved, so nothing is written. Worth the check: adopting on every pass
        // would rewrite three settings rows a minute for the life of the till.
        if (adopted == local) ShopPolicyPlan.Settled else ShopPolicyPlan.Adopt(adopted)
    }
}
