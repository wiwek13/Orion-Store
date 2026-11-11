package com.orion.store.morphe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orion.store.platform.PatchStorageCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchStorageCleanerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "base_apk_cache").deleteRecursively()
        File(context.filesDir, "mpp_cache").deleteRecursively()
        File(context.cacheDir, "patcher_session").deleteRecursively()
    }

    @Test
    fun testPurgeAppCacheDeletesOnlyTargetPackage() {
        val app1Cache = File(context.filesDir, "base_apk_cache/com.test.app1/bundle_1.0").apply { mkdirs() }
        File(app1Cache, "base.apk").writeBytes(ByteArray(1024))

        val app2Cache = File(context.filesDir, "base_apk_cache/com.test.app2/bundle_1.0").apply { mkdirs() }
        File(app2Cache, "base.apk").writeBytes(ByteArray(2048))

        val app1Session = File(context.cacheDir, "patcher_session/com.test.app1/session_123").apply { mkdirs() }
        File(app1Session, "temp.apk").writeBytes(ByteArray(512))

        val freed = PatchStorageCleaner.purgeAppCache(context, "com.test.app1")
        assertTrue(freed > 1000L)
        assertFalse(File(context.filesDir, "base_apk_cache/com.test.app1").exists())
        assertFalse(app1Session.exists())
        assertTrue(File(context.filesDir, "base_apk_cache/com.test.app2").exists())
    }

    @Test
    fun testClearPatcherCacheRemovesAllBaseAndMppCaches() {
        val baseCache = File(context.filesDir, "base_apk_cache/com.test.app/bundle_1").apply { mkdirs() }
        File(baseCache, "base.apk").writeBytes(ByteArray(4096))
        val mppCache = File(context.filesDir, "mpp_cache").apply { mkdirs() }
        File(mppCache, "test.mpp").writeBytes(ByteArray(4096))

        val freed = PatchStorageCleaner.clearPatcherCache(context)
        assertTrue(freed >= 8192L)
        assertFalse(baseCache.exists())
        assertFalse(File(mppCache, "test.mpp").exists())
    }

    @Test
    fun testGetPatcherCacheSizeMb() {
        val baseCache = File(context.filesDir, "base_apk_cache/com.test.app/bundle_1").apply { mkdirs() }
        File(baseCache, "base.apk").writeBytes(ByteArray(2 * 1024 * 1024))
        val sizeMb = PatchStorageCleaner.getPatcherCacheSizeMb(context)
        assertEquals(2L, sizeMb)
    }
}
