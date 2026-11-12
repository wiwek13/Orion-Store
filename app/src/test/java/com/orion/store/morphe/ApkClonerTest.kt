package com.orion.store.morphe

import com.orion.store.morphe.engine.ApkCloner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun testBinaryAxmlPackageCloningWithAapt2Verification() {
        // Load real binary AndroidManifest.xml from Boost APK or test resources
        val manifestBytes: ByteArray = run {
            val boostApk = File("build/test_temp/boost/github_com.rubenmayayo.reddit.apk")
            if (boostApk.exists()) {
                ZipFile(boostApk).use { zf ->
                    zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use { it.readBytes() }
                }
            } else {
                val resStream = javaClass.classLoader?.getResourceAsStream("axml/boost_manifest.bin")
                if (resStream != null) {
                    resStream.use { it.readBytes() }
                } else {
                    File("src/test/resources/axml/boost_manifest.bin").takeIf { it.exists() }?.readBytes()
                        ?: File("app/src/test/resources/axml/boost_manifest.bin").readBytes()
                }
            }
        }

        assertTrue("Manifest bytes must not be empty", manifestBytes.isNotEmpty())

        val tempIn = File.createTempFile("test_boost_in", ".apk")
        val tempOut = File.createTempFile("test_boost_cloned", ".apk")

        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(128))
            zos.closeEntry()
        }

        // Clone to different length: com.rubenmayayo.reddit (22 chars) -> com.rubenmayayo.reddit.morphe (29 chars)
        val success = ApkCloner.clonePackage(
            inputApk = tempIn,
            outputApk = tempOut,
            targetPkg = "com.rubenmayayo.reddit",
            newPkg = "com.rubenmayayo.reddit.morphe"
        )
        assertTrue("clonePackage must succeed on binary AXML APK", success)
        assertTrue("Output APK must exist and have content", tempOut.exists() && tempOut.length() > 0)

        // Verify with aapt2 dump xmltree --file AndroidManifest.xml <clonedApk>
        val aapt2Path = listOf(
            "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt2",
            "/usr/local/share/android-commandlinetools/build-tools/35.0.0/aapt2"
        ).firstOrNull { File(it).exists() && File(it).canExecute() }
            ?: System.getenv("ANDROID_HOME")?.let { File(it, "build-tools/35.0.0/aapt2").takeIf { f -> f.exists() }?.absolutePath }
            ?: System.getenv("ANDROID_SDK_ROOT")?.let { File(it, "build-tools/35.0.0/aapt2").takeIf { f -> f.exists() }?.absolutePath }

        if (aapt2Path != null) {
            val process = ProcessBuilder(
                aapt2Path,
                "dump", "xmltree",
                "--file", "AndroidManifest.xml",
                tempOut.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            println("[AAPT2 VERIFICATION OUTPUT]\n" + output.take(500))
            assertEquals("aapt2 must exit with code 0 without parse errors", 0, exitCode)
            assertTrue(
                "aapt2 output must declare package=\"com.rubenmayayo.reddit.morphe\"",
                output.contains("package=\"com.rubenmayayo.reddit.morphe\"")
            )
            assertFalse(
                "aapt2 output must not retain old package=\"com.rubenmayayo.reddit\"",
                output.contains("package=\"com.rubenmayayo.reddit\"")
            )
            assertTrue(
                "aapt2 output must have rewritten provider authorities",
                output.contains("com.rubenmayayo.reddit.morphe.provider")
            )
        } else {
            println("[WARN] aapt2 binary not found on standard paths, falling back to binary parser checks.")
            val clonedBytes = ZipFile(tempOut).use { zf ->
                zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            }
            val buf = java.nio.ByteBuffer.wrap(clonedBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rootType = buf.getShort(0).toInt() and 0xFFFF
            assertEquals("Root chunk type must remain 0x0003", 0x0003, rootType)
            assertEquals("Root header size must remain 8", 8, buf.getShort(2).toInt() and 0xFFFF)
        }

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun testBinaryAxmlPackageCloningContraction() {
        val manifestBytes = File("app/src/test/resources/axml/boost_manifest.bin").takeIf { it.exists() }?.readBytes()
            ?: File("src/test/resources/axml/boost_manifest.bin").readBytes()

        val tempIn = File.createTempFile("test_contract_in", ".apk")
        val tempOut = File.createTempFile("test_contract_out", ".apk")

        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestBytes)
            zos.closeEntry()
        }

        // Contract length: com.rubenmayayo.reddit (22 chars) -> com.r.r (7 chars)
        val success = ApkCloner.clonePackage(
            inputApk = tempIn,
            outputApk = tempOut,
            targetPkg = "com.rubenmayayo.reddit",
            newPkg = "com.r.r"
        )
        assertTrue(success)

        val aapt2Path = "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt2"
        if (File(aapt2Path).exists()) {
            val process = ProcessBuilder(
                aapt2Path,
                "dump", "xmltree",
                "--file", "AndroidManifest.xml",
                tempOut.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            assertEquals("aapt2 contraction test must exit with code 0", 0, exitCode)
            assertTrue("Must contain package=\"com.r.r\"", output.contains("package=\"com.r.r\""))
        }

        tempIn.delete()
        tempOut.delete()
    }

    @Test
    fun `test rewritePackageString respects package boundaries and avoids substring corruption`() {
        val target = "com.instagram.android"
        val clone = "com.instagram.clone"
        assertEquals(clone, ApkCloner.rewritePackageString("com.instagram.android", target, clone))
        assertEquals(
            "com.instagram.clone.fileprovider",
            ApkCloner.rewritePackageString("com.instagram.android.fileprovider", target, clone)
        )
        assertEquals(
            "content://com.instagram.clone.provider/items",
            ApkCloner.rewritePackageString("content://com.instagram.android.provider/items", target, clone)
        )
        assertEquals(
            "com.instagram.clone:remote",
            ApkCloner.rewritePackageString("com.instagram.android:remote", target, clone)
        )
        assertEquals(
            "com.instagram.android2.extra",
            ApkCloner.rewritePackageString("com.instagram.android2.extra", target, clone)
        )
        assertEquals(
            "org.other.com.instagram.android",
            ApkCloner.rewritePackageString("org.other.com.instagram.android", target, clone)
        )
    }
}
