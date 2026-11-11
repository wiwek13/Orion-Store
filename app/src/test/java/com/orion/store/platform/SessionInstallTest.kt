package com.orion.store.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionInstallTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SessionInstall.sessions.clear()
        SessionInstall.confirm.clear()
    }

    @After
    fun tearDown() {
        SessionInstall.sessions.clear()
        SessionInstall.confirm.clear()
    }

    @Test
    fun testOnResultReturnsFalseForUnknownSession() {
        val intent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, 9999)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        }
        assertFalse(SessionInstall.onResult(context, intent))
    }

    @Test
    fun testPendingUserActionLaunchesIntentWithNewTaskFlag() {
        val sessionId = 42
        val deferred = CompletableDeferred<Pair<Int, String?>>()
        SessionInstall.sessions[sessionId] = deferred
        SessionInstall.confirm.add(sessionId)

        val confirmIntent = Intent("com.test.INSTALL_CONFIRM")
        val callbackIntent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(Intent.EXTRA_INTENT, confirmIntent)
        }

        val handled = SessionInstall.onResult(context, callbackIntent)
        assertTrue("onResult should return true when launch succeeds", handled)
        assertTrue("Intent should have FLAG_ACTIVITY_NEW_TASK set", (confirmIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertFalse("Deferred should not complete yet while awaiting user confirmation", deferred.isCompleted)
        assertTrue("Session should remain active", SessionInstall.sessions.containsKey(sessionId))
        assertTrue("Session should remain in confirm set", SessionInstall.confirm.contains(sessionId))

        // Next callback completes session
        val successIntent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        }
        val finalHandled = SessionInstall.onResult(context, successIntent)
        assertTrue(finalHandled)
        assertTrue(deferred.isCompleted)
        val (status, msg) = deferred.getCompleted()
        assertEquals(PackageInstaller.STATUS_SUCCESS, status)
        assertNull(msg)
    }

    @Test
    fun testPendingUserActionFailsWhenStartActivityThrows() {
        val sessionId = 43
        val deferred = CompletableDeferred<Pair<Int, String?>>()
        SessionInstall.sessions[sessionId] = deferred
        SessionInstall.confirm.add(sessionId)

        val failingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                throw SecurityException("Permission denied to launch installer")
            }
        }

        val confirmIntent = Intent("com.test.INSTALL_CONFIRM")
        val callbackIntent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(Intent.EXTRA_INTENT, confirmIntent)
        }

        val handled = SessionInstall.onResult(failingContext, callbackIntent)
        assertTrue("onResult should return true to indicate session was handled", handled)
        assertTrue(deferred.isCompleted)
        val (status, msg) = deferred.getCompleted()
        assertEquals(PackageInstaller.STATUS_FAILURE, status)
        assertTrue("Message should report failure to launch: $msg", msg?.contains("Failed to launch package install confirmation dialog") == true)
        assertTrue("Message should include original error: $msg", msg?.contains("Permission denied") == true)
        assertFalse("Session should be removed from confirm set", SessionInstall.confirm.contains(sessionId))
    }

    @Test
    fun testPendingUserActionFailsWhenNextIntentIsNull() {
        val sessionId = 44
        val deferred = CompletableDeferred<Pair<Int, String?>>()
        SessionInstall.sessions[sessionId] = deferred
        SessionInstall.confirm.add(sessionId)

        val callbackIntent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        }

        val handled = SessionInstall.onResult(context, callbackIntent)
        assertTrue(handled)
        assertTrue(deferred.isCompleted)
        val (status, msg) = deferred.getCompleted()
        assertEquals(PackageInstaller.STATUS_FAILURE, status)
        assertEquals("Android requested user confirmation, but no confirmation intent was provided.", msg)
    }

    @Test
    fun testPendingUserActionWithoutConfirmDropsPrompt() {
        val sessionId = 45
        val deferred = CompletableDeferred<Pair<Int, String?>>()
        SessionInstall.sessions[sessionId] = deferred
        // Note: not added to confirm (background install)

        val callbackIntent = Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        }

        val handled = SessionInstall.onResult(context, callbackIntent)
        assertTrue(handled)
        assertTrue(deferred.isCompleted)
        val (status, msg) = deferred.getCompleted()
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, status)
        assertEquals("Pending user confirmation", msg)
    }
}
