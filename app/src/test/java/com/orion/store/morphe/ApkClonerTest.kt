package com.orion.store.morphe

import com.orion.store.morphe.engine.ApkCloner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkClonerTest {

    @Test
    fun testApkClonerRewritesManifestPackageName() {
        val tempIn = File.createTempFile("test_in", ".apk")
        val tempOut = File.createTempFile("test_out", ".apk")

        // Create dummy APK with manifest
        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("package=\"com.test.original\" android:authorities=\"com.test.original.provider\"".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(100))
            zos.closeEntry()
        }

        val success = ApkCloner.clonePackage(tempIn, tempOut, "com.test.original", "com.test.cloned")
        assertTrue(success)

        var manifestContent = ""
        ZipFile(tempOut).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use {
                manifestContent = it.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(manifestContent.contains("com.test.cloned"))
        assertTrue(manifestContent.contains("com.test.cloned.provider"))
        assertFalse(manifestContent.contains("com.test.original"))

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun testApkClonerRewritesManifestPackageNameUtf16LE() {
        val tempIn = File.createTempFile("test_in_utf16", ".apk")
        val tempOut = File.createTempFile("test_out_utf16", ".apk")

        val utf16Text = "package=\"com.test.utf16\" android:authorities=\"com.test.utf16.provider\""
        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(utf16Text.toByteArray(Charsets.UTF_16LE))
            zos.closeEntry()
        }

        val success = ApkCloner.clonePackage(tempIn, tempOut, "com.test.utf16", "com.test.cloned16")
        assertTrue(success)

        var manifestContent = ""
        ZipFile(tempOut).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use {
                manifestContent = it.readBytes().toString(Charsets.UTF_16LE)
            }
        }
        assertTrue(manifestContent.contains("com.test.cloned16"))
        assertTrue(manifestContent.contains("com.test.cloned16.provider"))
        assertFalse(manifestContent.contains("com.test.utf16"))

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun testApkClonerPreservesStoredEntriesAndOmitsStaleSignatures() {
        val tempIn = File.createTempFile("test_stored_in", ".apk")
        val tempOut = File.createTempFile("test_stored_out", ".apk")

        val dummyArsc = "Dummy resources.arsc content".toByteArray(Charsets.UTF_8)
        val dummySo = "ELF dummy shared library content".toByteArray(Charsets.UTF_8)

        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            // Manifest
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("package=\"com.instagram.android\"".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // resources.arsc as STORED
            val arscEntry = ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = dummyArsc.size.toLong()
                compressedSize = dummyArsc.size.toLong()
                val crc = CRC32().apply { update(dummyArsc) }
                this.crc = crc.value
            }
            zos.putNextEntry(arscEntry)
            zos.write(dummyArsc)
            zos.closeEntry()

            // lib.so as STORED
            val soEntry = ZipEntry("lib/arm64-v8a/libtest.so").apply {
                method = ZipEntry.STORED
                size = dummySo.size.toLong()
                compressedSize = dummySo.size.toLong()
                val crc = CRC32().apply { update(dummySo) }
                this.crc = crc.value
            }
            zos.putNextEntry(soEntry)
            zos.write(dummySo)
            zos.closeEntry()

            // Stale signature files
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
            zos.write("Signature-Version: 1.0".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
            zos.write("DUMMY_RSA".toByteArray())
            zos.closeEntry()
        }

        val success = ApkCloner.clonePackage(
            inputApk = tempIn,
            outputApk = tempOut,
            targetPkg = "com.instagram.android",
            newPkg = "com.instagram.android.morphe"
        )
        assertTrue(success)

        ZipFile(tempOut).use { zf ->
            val arsc = zf.getEntry("resources.arsc")
            val so = zf.getEntry("lib/arm64-v8a/libtest.so")
            val manifestMf = zf.getEntry("META-INF/MANIFEST.MF")
            val certSf = zf.getEntry("META-INF/CERT.SF")
            val certRsa = zf.getEntry("META-INF/CERT.RSA")

            assertEquals(ZipEntry.STORED, arsc.method)
            assertEquals(ZipEntry.STORED, so.method)

            assertNull(manifestMf)
            assertNull(certSf)
            assertNull(certRsa)
        }

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun testApkClonerSamePackageCopiesDirectly() {
        val tempIn = File.createTempFile("test_same_in", ".apk")
        val tempOut = File.createTempFile("test_same_out", ".apk")

        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("package=\"com.same.pkg\"".toByteArray())
            zos.closeEntry()
        }

        val success = ApkCloner.clonePackage(tempIn, tempOut, "com.same.pkg", "com.same.pkg")
        assertTrue(success)
        assertTrue(tempOut.exists())
        assertEquals(tempIn.length(), tempOut.length())

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun testApkClonerReturnsFalseOnMissingInput() {
        val nonExistent = File("/tmp/non_existent_apk_${System.currentTimeMillis()}.apk")
        val tempOut = File.createTempFile("test_out", ".apk")

        val success = ApkCloner.clonePackage(nonExistent, tempOut, "com.target", "com.cloned")
        assertFalse(success)

        tempOut.delete()
    }
}
