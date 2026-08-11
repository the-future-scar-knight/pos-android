package com.portionspot.pos.auth

import com.portionspot.pos.data.StaffDao
import com.portionspot.pos.data.StaffMember

/**
 * The roster this device can check a PIN against, and the shop id that PIN was salted
 * with — the two things sign-in needs and neither of which the auth layer should reach
 * into Room (or the sync config) to find for itself.
 *
 * ★ WHY IT IS A SEAM AND NOT A COUPLE OF DAO CALLS ★
 *
 * [shopId] is the CLOUD business id, not the local one this device invented on first run.
 * `staff.pin_hash` is salted with `SHA-256("PortionSpot POS pin v1:" + business_id)` under
 * the cloud id (that is what the web writes), and the roster pull therefore stamps every
 * staff row's `businessId` with the cloud id too — so it is also the key the roster
 * queries take. One value, one meaning, resolved in one place; the alternative is every
 * caller picking between two ids that are both "the business id" and only one of which
 * can verify a PIN.
 *
 * Everything here reads the LOCAL mirror. Sign-in never touches the network: the hash is
 * already on the phone, and a shop trading through a power cut must still be able to open
 * its till. See [com.portionspot.pos.data.StaffMember] for why holding the hash locally
 * costs nothing that holding it in the cloud did not already cost.
 */
class StaffDirectory(
    private val dao: StaffDao,
    private val shopIdProvider: suspend () -> String?,
) {

    /** The CLOUD business id, or null on a till that has not yet identified its shop. */
    suspend fun shopId(): String? = shopIdProvider()?.trim()?.ifBlank { null }

    /**
     * EVERY staff row for the shop, including deactivated and tombstoned ones.
     *
     * Deliberately not the live-only list: [StaffSignIn] needs the dead rows in order to
     * answer "that account has been switched off" instead of "no such user", and telling
     * those two apart is the difference between a cashier who phones the owner and one who
     * stands at the counter retyping a PIN that was never wrong.
     */
    suspend fun all(): List<StaffMember> = shopId()?.let { dao.all(it) } ?: emptyList()

    suspend fun byId(id: String): StaffMember? = dao.getById(id)
}
