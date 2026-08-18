package com.portionspot.pos.sync.wire

import com.portionspot.pos.data.ShopPolicy
import com.portionspot.pos.data.ShopPolicyWireValues
import com.portionspot.pos.sync.IsoTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The three columns of the shared `businesses` row that carry the shop's money RULES —
 * and nothing else off that row.
 *
 * Android has never read this row. The only reference to `businesses` in the sync engine
 * is `selectAll("businesses", "id")`, which learns the shop's id and reads no other
 * column, so `discount_threshold` has sat there since the schema was written with the web
 * as its only reader. See [com.portionspot.pos.data.ShopPolicy] for why these three are
 * shop facts and the rest of that row is not.
 */
@Serializable
data class BusinessPolicyDto(
    val id: String = "",
    /** Numeric columns arrive as JSON STRINGS — PostgREST renders `numeric` that way to
     *  keep precision — so every one of these is parsed, not cast. */
    @SerialName("max_item_discount") val maxItemDiscount: String? = null,
    @SerialName("discount_threshold") val discountThreshold: String? = null,
    @SerialName("variance_note_threshold") val varianceNoteThreshold: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /**
     * The SERVER clock this row was last written on, which is the only clock both clients
     * can compare against. A row with no `updated_at` reads as the epoch, so a device
     * that has actually changed the policy wins over it rather than being overwritten by
     * a row that cannot say when it was written.
     */
    fun cursorStamp(): String = updatedAt ?: IsoTime.EPOCH

    /** Null is preserved as null all the way through: it means the row does not STATE the
     *  rule, which resolves to the local value, never to zero. See [planShopPolicySync]. */
    fun toPolicyValues(): ShopPolicyWireValues = ShopPolicyWireValues(
        maxItemDiscount = maxItemDiscount?.toDoubleOrNull(),
        discountThresholdPct = discountThreshold?.toDoubleOrNull(),
        varianceNoteThreshold = varianceNoteThreshold?.toDoubleOrNull(),
    )
}

/**
 * The PATCH body — three columns and the client clock, and deliberately NOTHING else.
 *
 * ★★ THIS IS NOT A PUSH DTO AND MUST NEVER BE UPSERTED. ★★
 *
 * Every other push in this package is a full-row upsert, because every other table's rows
 * are written by this app and no other. `businesses` is the opposite case: the web owns
 * almost all of it, and PostgREST resolves an upsert of a PARTIAL row by writing the
 * columns it was given and NULLING every column it was not. Upserting this object would
 * therefore wipe the shop's bank account number, its EcoCash merchant code, its VAT
 * number, its receipt header and footer, its logo and all seven payment-method flags —
 * settings this app does not manage, cannot rebuild, and in most shops has never even
 * displayed. The owner's next receipt would print without the details customers pay
 * against, and nothing in the app would say why.
 *
 * So it goes up as a targeted `PATCH ?id=eq.<business>` naming these columns only, via
 * [com.portionspot.pos.sync.SupabaseRest.updateById]. Not a convenience: an upsert here is
 * a silent data-loss bug that would look, from the till, exactly like a successful sync.
 *
 * `client_updated_at` and not `updated_at`, for the same reason as everywhere else: the
 * server owns `updated_at` and every pull cursor reads it, so a phone with a skewed clock
 * that stamped it could park the row in the future and make every other device skip
 * everything behind it. The server's `set_updated_at` trigger also uses this value as its
 * stale-write guard — a PATCH carrying an older client clock than the row already holds is
 * IGNORED rather than rejected, which is what makes two phones editing the policy while
 * offline from each other resolve to the later edit instead of to whoever synced last.
 */
@Serializable
data class BusinessPolicyPatchDto(
    @SerialName("max_item_discount") val maxItemDiscount: Double,
    @SerialName("discount_threshold") val discountThreshold: Double,
    @SerialName("variance_note_threshold") val varianceNoteThreshold: Double,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/** The three rules as the shared row wants them, stamped with when this device changed them. */
fun ShopPolicy.toPatch(changedAt: Long): BusinessPolicyPatchDto = BusinessPolicyPatchDto(
    maxItemDiscount = maxItemDiscount,
    discountThreshold = discountThresholdPct,
    varianceNoteThreshold = varianceNoteThreshold,
    clientUpdatedAt = IsoTime.toIso(changedAt),
)
