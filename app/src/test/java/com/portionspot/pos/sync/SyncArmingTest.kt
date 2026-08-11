package com.portionspot.pos.sync

import com.portionspot.pos.data.Setting
import com.portionspot.pos.data.SettingDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which database this till has already offered its own rows to.
 *
 * A dirty flag records that a row was SENT, never WHERE. Repoint a till at a second
 * project and everything it pushed to the first is still marked clean, so it is never
 * offered to the new database and the pass reports success having sent nothing — there
 * genuinely was nothing pending.
 *
 * That is not hypothetical: customer `Ryan` was created before the shop's till was
 * repointed and stayed behind, `Tim` was created after and went up normally. Two sales
 * and a `credit_owed` of $15 then landed in the cloud referencing a customer present in
 * neither database — a debt with no debtor, raised nowhere, because nothing enforces the
 * reference.
 */
class SyncArmingTest {

    /** The settings key/value store, in memory. */
    private class FakeSettings : SettingDao {
        val values = HashMap<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(setting: Setting) { values[setting.key] = setting.value }
        override suspend fun delete(key: String) { values.remove(key) }
    }

    private val a = "https://lvaxbbobmounfxskooiw.supabase.co"
    private val b = "https://klkfynokcjunqvbkywfk.supabase.co"

    private fun config(): Pair<SyncConfig, FakeSettings> {
        val dao = FakeSettings()
        return SyncConfig(dao) to dao
    }

    @Test
    fun aTillThatHasNeverUploadedNeedsArming() = runBlocking {
        val (config, _) = config()
        // The first connection a local-first till makes. Everything rung up before there
        // was a cloud is exactly the history the shop wants uploaded.
        assertTrue(config.needsArmingFor(a))
    }

    @Test
    fun theSameDatabaseIsNotArmedTwice() = runBlocking {
        val (config, _) = config()
        config.setArmedFor(a)
        assertFalse(config.needsArmingFor(a))
    }

    @Test
    fun aTrailingSlashIsTheSameDatabase() = runBlocking {
        val (config, _) = config()
        // `saveConnection` stores the URL trimmed of its trailing slash, so the comparison
        // has to normalise identically — otherwise re-typing the URL with a slash re-arms
        // and re-uploads the shop's whole history for nothing.
        config.setArmedFor(a)
        assertFalse(config.needsArmingFor("$a/"))
        assertFalse(config.needsArmingFor("  $a  "))
    }

    @Test
    fun adifferentDatabaseNeedsArming() = runBlocking {
        val (config, _) = config()
        config.setArmedFor(a)
        assertTrue(config.needsArmingFor(b))
    }

    @Test
    fun comingBackToAnEarlierDatabaseArmsAgain() = runBlocking {
        val (config, _) = config()
        // A -> B -> A. The rows made while pointed at B were pushed to B, so A has never
        // seen them and must be offered them on return.
        config.setArmedFor(a)
        config.setArmedFor(b)
        assertTrue(config.needsArmingFor(a))
    }

    @Test
    fun disconnectingDoesNotForgetWhatWasAlreadyUploaded() = runBlocking {
        val (config, dao) = config()
        config.saveConnection(a, "anon-key")
        config.setArmedFor(a)

        config.clearConnection()

        // The connection is gone, the cursors and the adopted shop with it...
        assertEquals(null, dao.values[SyncConfig.KEY_URL])
        assertEquals(null, dao.values[SyncConfig.KEY_CLOUD_BID])
        // ...but disconnecting does not remove this till's rows from that database, so
        // reconnecting to it must NOT re-upload the shop's entire history.
        assertFalse(config.needsArmingFor(a))
        // A genuinely different project still arms.
        assertTrue(config.needsArmingFor(b))
    }
}
