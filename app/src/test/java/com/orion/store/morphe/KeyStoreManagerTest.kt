package com.orion.store.morphe

import com.orion.store.morphe.store.KeyStoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class KeyStoreManagerTest {

    @Test
    fun `test key generation and self-signed certificate`() {
        val alias = "test-custom-key"
        val keyPair = KeyStoreManager.generateKeyPair(alias)
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)

        val keys = KeyStoreManager.getKnownKeys()
        assertTrue(keys.any { it.alias == alias })
    }

    @Test
    fun `test encrypted export and successful import with passphrase`() {
        val alias = "test-export-key"
        val keyPair = KeyStoreManager.generateKeyPair(alias)
        val cert = KeyStoreManager.generateSelfSignedCertificate(keyPair, "CN=$alias")
        val passphrase = "SecurePassword123!".toCharArray()

        val bundle = KeyStoreManager.exportEncryptedBundle(keyPair, cert, alias, passphrase)
        assertTrue(bundle.isNotEmpty())

        val (importedAlias, importedKeyPair) = KeyStoreManager.importEncryptedBundle(bundle, passphrase)
        assertEquals(alias, importedAlias)
        assertNotNull(importedKeyPair)
        assertNotNull(importedKeyPair?.public)
        assertNotNull(importedKeyPair?.private)
    }

    @Test(expected = Exception::class)
    fun `test import fails with incorrect passphrase`() {
        val alias = "test-wrong-pass"
        val keyPair = KeyStoreManager.generateKeyPair(alias)
        val cert = KeyStoreManager.generateSelfSignedCertificate(keyPair, "CN=$alias")
        val correctPass = "CorrectPassphrase".toCharArray()
        val wrongPass = "WrongPassphrase".toCharArray()

        val bundle = KeyStoreManager.exportEncryptedBundle(keyPair, cert, alias, correctPass)
        KeyStoreManager.importEncryptedBundle(bundle, wrongPass)
    }

    @Test
    fun `test sign apk with apksig v1 v2 v3 engine`() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "morphe_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val dummyApk = java.io.File(tempDir, "dummy.apk")
        val signedApk = java.io.File(tempDir, "signed.apk")

        // Create a minimal valid zip file
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(dummyApk)).use { zos ->
            val entry = java.util.zip.ZipEntry("AndroidManifest.xml")
            zos.putNextEntry(entry)
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
        }

        val success = KeyStoreManager.signApkFile(dummyApk, signedApk)
        assertTrue("Signing should succeed", success)
        assertTrue("Signed APK file should exist", signedApk.exists())
        assertTrue("Signed APK size should be greater than zero", signedApk.length() > 0L)

        tempDir.deleteRecursively()
    }

    @Test
    fun `test MorpheEngineImpl preserves STORED on resources arsc and native libraries`(): Unit = kotlinx.coroutines.runBlocking {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "morphe_stored_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val inputApk = java.io.File(tempDir, "input.apk")
        val patchedApk = java.io.File(tempDir, "patched.apk")
        val alignedApk = java.io.File(tempDir, "aligned.apk")
        val signedApk = java.io.File(tempDir, "signed.apk")

        java.util.zip.ZipOutputStream(java.io.FileOutputStream(inputApk)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()

            val arscBytes = "ARSC_DATA_CONTENT".toByteArray()
            val arscCrc = java.util.zip.CRC32().apply { update(arscBytes) }.value
            zos.putNextEntry(java.util.zip.ZipEntry("resources.arsc").apply {
                method = java.util.zip.ZipEntry.STORED
                size = arscBytes.size.toLong()
                compressedSize = arscBytes.size.toLong()
                crc = arscCrc
            })
            zos.write(arscBytes)
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libtest.so"))
            zos.write(ByteArray(256) { 0x7F })
            zos.closeEntry()
        }

        val engine = com.orion.store.morphe.engine.MorpheEngineImpl()
        val res = engine.applyPatches(
            inputApk = inputApk,
            selectedPatches = emptyList(),
            options = mapOf("packageName" to "com.test.sample", "bundleName" to "TestBundle"),
            outputApk = patchedApk
        ) { _, _ -> }

        assertTrue("Patch apply should succeed", res is com.orion.store.morphe.engine.PatchResult.Success)
        assertTrue("Zipalign should succeed", engine.zipalign(patchedApk, alignedApk))
        assertTrue("Signing should succeed", engine.signApk(alignedApk, signedApk, KeyStoreManager.DEFAULT_ALIAS))

        java.util.zip.ZipFile(signedApk).use { zip ->
            val arscEntry = zip.getEntry("resources.arsc")
            assertNotNull("resources.arsc must exist", arscEntry)
            assertEquals("resources.arsc MUST be STORED (0)", java.util.zip.ZipEntry.STORED, arscEntry!!.method)

            val soEntry = zip.getEntry("lib/arm64-v8a/libtest.so")
            assertNotNull("libtest.so must exist", soEntry)
            assertEquals("Native .so library MUST be STORED (0)", java.util.zip.ZipEntry.STORED, soEntry!!.method)
        }

        tempDir.deleteRecursively()
    }
}
