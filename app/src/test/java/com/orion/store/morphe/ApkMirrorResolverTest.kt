package com.orion.store.morphe

import com.orion.store.platform.ApkMirrorResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkMirrorResolverTest {

    @Test
    fun testApkMirrorQueryBuilder() {
        val queryUrl = ApkMirrorResolver.buildSearchUrl("com.instagram.android")
        assertTrue(queryUrl.contains("searchtype=apk"))
        assertTrue(queryUrl.contains("com.instagram.android"))
    }

    @Test
    fun testVariantSelectorPrefersArm64OrNodpi() {
        val variants = listOf(
            ApkMirrorResolver.ApkMirrorVariant(id = "1", abi = "x86", dpi = "nodpi", downloadUrl = "https://test.com/1"),
            ApkMirrorResolver.ApkMirrorVariant(id = "2", abi = "arm64-v8a", dpi = "nodpi", downloadUrl = "https://test.com/2"),
            ApkMirrorResolver.ApkMirrorVariant(id = "3", abi = "armeabi-v7a", dpi = "480dpi", downloadUrl = "https://test.com/3")
        )
        val selected = ApkMirrorResolver.selectBestVariant(variants)
        assertNotNull(selected)
        assertEquals("arm64-v8a", selected?.abi)
        assertEquals("nodpi", selected?.dpi)
    }

    @Test
    fun testVariantSelectorFallsBackToArm64AnyDpi() {
        val variants = listOf(
            ApkMirrorResolver.ApkMirrorVariant(id = "1", abi = "x86", dpi = "nodpi", downloadUrl = "https://test.com/1"),
            ApkMirrorResolver.ApkMirrorVariant(id = "2", abi = "arm64-v8a", dpi = "640dpi", downloadUrl = "https://test.com/2")
        )
        val selected = ApkMirrorResolver.selectBestVariant(variants)
        assertNotNull(selected)
        assertEquals("arm64-v8a", selected?.abi)
        assertEquals("640dpi", selected?.dpi)
    }

    @Test
    fun testVariantSelectorFallsBackToUniversal() {
        val variants = listOf(
            ApkMirrorResolver.ApkMirrorVariant(id = "1", abi = "x86", dpi = "nodpi", downloadUrl = "https://test.com/1"),
            ApkMirrorResolver.ApkMirrorVariant(id = "2", abi = "universal", dpi = "nodpi", downloadUrl = "https://test.com/2")
        )
        val selected = ApkMirrorResolver.selectBestVariant(variants)
        assertNotNull(selected)
        assertEquals("universal", selected?.abi)
    }

    @Test
    fun testVariantSelectorEmpty() {
        val selected = ApkMirrorResolver.selectBestVariant(emptyList())
        assertNull(selected)
    }
}
