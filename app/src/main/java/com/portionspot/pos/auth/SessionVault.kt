package com.portionspot.pos.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Everything needed to work offline after the first online login. */
@Serializable
data class CachedAuth(
    val userId: String,
    val email: String,
    val role: String,          // "admin" | "cashier"
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,       // epoch seconds
)

/** One staff member provisioned on this device: their cached session + PIN gate. */
@Serializable
data class AccountRecord(
    val auth: CachedAuth,
    val pinSalt: String? = null, // base64; null until a PIN is set
    val pinHash: String? = null, // base64 PBKDF2(pin, salt); null until a PIN is set
    val pinFails: Int = 0,
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null
}

/** The whole device vault: every provisioned account plus which one is active. */
@Serializable
private data class VaultData(
    val accounts: List<AccountRecord> = emptyList(),
    val activeUserId: String? = null,
)

/** A provisioned account as the lock-screen picker sees it (no tokens/secrets). */
data class AccountSummary(
    val userId: String,
    val displayName: String,
    val role: String,
    val email: String,
    val hasPin: Boolean,
) {
    val isAdmin: Boolean get() = role == "admin"
}

/**
 * Encrypted at-rest storage for one-or-more Supabase sessions and each account's
 * offline-unlock PIN. Uses a hardware-backed (where available) Android Keystore
 * AES-256-GCM key directly — no extra dependencies, minSdk 23 compatible.
 *
 * Multi-account (so a shared or handed-over device can hold the admin AND every
 * cashier, each unlocking with their own PIN): the whole [VaultData] — sessions,
 * PIN salts and PIN hashes — is serialized to JSON and sealed as ONE encrypted
 * blob. PINs are still never stored in the clear: only PBKDF2-HmacSHA256(pin,
 * random salt, 120k rounds).
 *
 * A legacy single-session vault (KEY_SESSION + KEY_PIN_*) is migrated on first
 * load into a one-account [VaultData], so existing installs keep their login/PIN.
 */
class SessionVault(context: Context) {

    private val prefs = context.getSharedPreferences("pos_auth_vault", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ── vault blob ────────────────────────────────────────────────────────

    private fun load(): VaultData {
        prefs.getString(KEY_VAULT, null)?.let { b64 ->
            return decryptVault(b64) ?: VaultData()
        }
        // No new-format vault yet: migrate a legacy single-session vault if present.
        return migrateLegacy() ?: VaultData()
    }

    private fun persist(data: VaultData) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(data).toByteArray())
        prefs.edit().putString(KEY_VAULT, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun decryptVault(b64: String): VaultData? = try {
        val sealed = Base64.decode(b64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed, 0, IV_LEN))
        val plain = cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
        json.decodeFromString<VaultData>(String(plain))
    } catch (_: Exception) {
        // Corrupt blob or keystore key invalidated: treat as signed out.
        null
    }

    /** One-time upgrade from the old single-session layout; clears the legacy keys. */
    private fun migrateLegacy(): VaultData? {
        val legacy = prefs.getString(KEY_LEGACY_SESSION, null) ?: return null
        val auth = try {
            val sealed = Base64.decode(legacy, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed, 0, IV_LEN))
            val plain = cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
            json.decodeFromString<CachedAuth>(String(plain))
        } catch (_: Exception) {
            null
        }
        // Whatever happens, don't try to migrate twice.
        val salt = prefs.getString(KEY_LEGACY_PIN_SALT, null)
        val hash = prefs.getString(KEY_LEGACY_PIN_HASH, null)
        val fails = prefs.getInt(KEY_LEGACY_PIN_FAILS, 0)
        prefs.edit()
            .remove(KEY_LEGACY_SESSION)
            .remove(KEY_LEGACY_PIN_HASH)
            .remove(KEY_LEGACY_PIN_SALT)
            .remove(KEY_LEGACY_PIN_FAILS)
            .apply()
        if (auth == null) return null
        val data = VaultData(
            accounts = listOf(AccountRecord(auth, pinSalt = salt, pinHash = hash, pinFails = fails)),
            activeUserId = auth.userId,
        )
        persist(data)
        return data
    }

    // ── account queries ───────────────────────────────────────────────────

    fun accounts(): List<AccountSummary> = load().accounts.map {
        AccountSummary(it.auth.userId, it.auth.displayName, it.auth.role, it.auth.email, it.hasPin)
    }

    fun hasAnyAccount(): Boolean = load().accounts.isNotEmpty()

    fun activeUserId(): String? = load().activeUserId

    fun activeSession(): CachedAuth? {
        val data = load()
        val id = data.activeUserId ?: return null
        return data.accounts.firstOrNull { it.auth.userId == id }?.auth
    }

    fun sessionFor(userId: String): CachedAuth? =
        load().accounts.firstOrNull { it.auth.userId == userId }?.auth

    // ── account mutations ─────────────────────────────────────────────────

    /** Add or replace an account's session (preserving any existing PIN), optionally
     *  making it the active account. Used on a fresh online login. */
    fun upsertSession(auth: CachedAuth, makeActive: Boolean = true) {
        val data = load()
        val existing = data.accounts.firstOrNull { it.auth.userId == auth.userId }
        val merged = existing?.copy(auth = auth) ?: AccountRecord(auth)
        val accounts = data.accounts.filterNot { it.auth.userId == auth.userId } + merged
        persist(data.copy(accounts = accounts, activeUserId = if (makeActive) auth.userId else data.activeUserId))
    }

    /** Refresh just the tokens on an existing account without touching PIN or active. */
    fun updateSession(userId: String, auth: CachedAuth) {
        val data = load()
        if (data.accounts.none { it.auth.userId == userId }) return
        val accounts = data.accounts.map { if (it.auth.userId == userId) it.copy(auth = auth) else it }
        persist(data.copy(accounts = accounts))
    }

    fun setActive(userId: String) {
        val data = load()
        if (data.accounts.none { it.auth.userId == userId }) return
        persist(data.copy(activeUserId = userId))
    }

    /** Remove one account from this device. Returns the accounts that remain. */
    fun removeAccount(userId: String): List<AccountSummary> {
        val data = load()
        val accounts = data.accounts.filterNot { it.auth.userId == userId }
        val active = if (data.activeUserId == userId) null else data.activeUserId
        persist(data.copy(accounts = accounts, activeUserId = active))
        return accounts.map {
            AccountSummary(it.auth.userId, it.auth.displayName, it.auth.role, it.auth.email, it.hasPin)
        }
    }

    fun clearAll() = prefs.edit().clear().apply()

    // ── PIN (per account) ─────────────────────────────────────────────────

    fun hasPin(userId: String): Boolean =
        load().accounts.firstOrNull { it.auth.userId == userId }?.hasPin == true

    fun setPin(userId: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        mutateAccount(userId) {
            it.copy(
                pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP),
                pinHash = Base64.encodeToString(pbkdf2(pin, salt), Base64.NO_WRAP),
                pinFails = 0,
            )
        }
    }

    /** True on match. Failed attempts are counted by the caller via [recordPinFailure]. */
    fun verifyPin(userId: String, pin: String): Boolean {
        val acc = load().accounts.firstOrNull { it.auth.userId == userId } ?: return false
        val salt = Base64.decode(acc.pinSalt ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(acc.pinHash ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, pbkdf2(pin, salt))
    }

    /** True if [pin] matches ANY admin account provisioned on this device. Used to
     *  authorise a cashier action that needs manager approval (e.g. a big discount). */
    fun verifyAnyAdminPin(pin: String): Boolean = load().accounts.any { acc ->
        acc.auth.role == "admin" && acc.hasPin &&
            MessageDigest.isEqual(
                Base64.decode(acc.pinHash, Base64.NO_WRAP),
                pbkdf2(pin, Base64.decode(acc.pinSalt, Base64.NO_WRAP))
            )
    }

    fun recordPinFailure(userId: String): Int {
        var fails = 0
        mutateAccount(userId) { fails = it.pinFails + 1; it.copy(pinFails = fails) }
        return fails
    }

    fun resetPinFailures(userId: String) = mutateAccount(userId) { it.copy(pinFails = 0) }

    private fun mutateAccount(userId: String, block: (AccountRecord) -> AccountRecord) {
        val data = load()
        if (data.accounts.none { it.auth.userId == userId }) return
        val accounts = data.accounts.map { if (it.auth.userId == userId) block(it) else it }
        persist(data.copy(accounts = accounts))
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256))
            .encoded

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pos_session_vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KEY_VAULT = "vault_v2"
        // Legacy single-session keys, migrated then removed.
        const val KEY_LEGACY_SESSION = "session"
        const val KEY_LEGACY_PIN_HASH = "pin_hash"
        const val KEY_LEGACY_PIN_SALT = "pin_salt"
        const val KEY_LEGACY_PIN_FAILS = "pin_fails"
    }
}
