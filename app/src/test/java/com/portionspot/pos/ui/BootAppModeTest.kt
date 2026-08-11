package com.portionspot.pos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which shell the app boots into — the rule that decides whether anyone ever sees a
 * sign-in screen at all.
 *
 * Two failures are being guarded against and they pull in opposite directions, which is
 * why the rule is worth a test rather than an `if` nobody reads again:
 *
 *  1. **A sign-in screen on a phone with no cloud configured** would be a worse regression
 *     than the bug it came from. Local (phone-only) mode is the DEFAULT and it is what the
 *     shop is running on right now: a fresh install must open straight into a usable till.
 *
 *  2. **A fall back to local mode on a phone that has staff on it** is a privilege
 *     escalation, not a convenience. Local mode runs as a hard-coded admin against the SAME
 *     database as the cloud session, so a cashier who signs out must not be able to arrive
 *     there — that route (sign out ⇒ empty account list ⇒ login screen ⇒ back arrow ⇒
 *     unrestricted owner, persisted) is exactly what was closed, and this is the half of
 *     the gate that survives a restart and an app upgrade.
 */
class BootAppModeTest {

    @Test
    fun `a fresh install boots into local mode with no sign-in`() {
        assertEquals(AppMode.Local, bootAppMode(storedMode = null, cloudProvisioned = false))
    }

    @Test
    fun `a till that chose local stays local`() {
        assertEquals(AppMode.Local, bootAppMode(storedMode = "local", cloudProvisioned = false))
    }

    @Test
    fun `an unreadable stored value falls back to local rather than to a login wall`() {
        assertEquals(AppMode.Local, bootAppMode(storedMode = "", cloudProvisioned = false))
        assertEquals(AppMode.Local, bootAppMode(storedMode = "CLOUD", cloudProvisioned = false))
    }

    @Test
    fun `opting into cloud is remembered`() {
        assertEquals(AppMode.Cloud, bootAppMode(storedMode = "cloud", cloudProvisioned = false))
    }

    /**
     * ★ The escalation guard. A device that has ever had a staff sign-in boots into cloud
     * mode whatever is persisted — including a "local" written by an older build through
     * the back-arrow route, which upgrading must not honour.
     */
    @Test
    fun `a device that has had a staff sign-in never boots back into local admin`() {
        assertEquals(AppMode.Cloud, bootAppMode(storedMode = "local", cloudProvisioned = true))
        assertEquals(AppMode.Cloud, bootAppMode(storedMode = null, cloudProvisioned = true))
        assertEquals(AppMode.Cloud, bootAppMode(storedMode = "cloud", cloudProvisioned = true))
    }
}
