package com.orion.store.morphe.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Clones an APK by rewriting the root package name and provider authorities in the binary manifest.
 * Allows patched apps to be installed alongside their original counterparts without signature conflicts.
 *
 * Preserves STORED compression method, CRC-32, and exact uncompressed sizes on resources.arsc and
 * native shared libraries (.so), while stripping stale META-INF signature files.
 */
object ApkCloner {

    fun clonePackage(
        inputApk: File,
        outputApk: File,
        targetPkg: String,
        newPkg: String
    ): Boolean {
        if (!inputApk.exists() || inputApk.length() == 0L) return false
        if (targetPkg.isBlank() || newPkg.isBlank()) return false

        outputApk.parentFile?.mkdirs()

        if (targetPkg == newPkg) {
            return try {
                inputApk.copyTo(outputApk, overwrite = true)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        try {
            ZipFile(inputApk).use { inZip ->
                ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
                    val buffer = ByteArray(65536)
                    for (entry in inZip.entries().asSequence()) {
                        val name = entry.name

                        // Omit stale signatures so apksig signs cleanly with a new key
                        if (name.startsWith("META-INF/") && (
                                name.endsWith(".SF", ignoreCase = true) ||
                                name.endsWith(".RSA", ignoreCase = true) ||
                                name.endsWith(".DSA", ignoreCase = true) ||
                                name.endsWith(".EC", ignoreCase = true) ||
                                name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
                            )) {
                            continue
                        }

                        if (name == "AndroidManifest.xml") {
                            val originalBytes = inZip.getInputStream(entry).use { it.readBytes() }
                            val modifiedBytes = rewriteAxmlPackage(originalBytes, targetPkg, newPkg)
                            val manifestEntry = ZipEntry("AndroidManifest.xml")
                            outZip.putNextEntry(manifestEntry)
                            outZip.write(modifiedBytes)
                            outZip.closeEntry()
                        } else {
                            val mustStore = name == "resources.arsc" ||
                                name.endsWith(".so", ignoreCase = true) ||
                                entry.method == ZipEntry.STORED

                            val newEntry = ZipEntry(name)
                            if (mustStore) {
                                newEntry.method = ZipEntry.STORED
                                if (entry.size >= 0L && entry.crc != -1L) {
                                    newEntry.size = entry.size
                                    newEntry.compressedSize = entry.size
                                    newEntry.crc = entry.crc
                                } else {
                                    val (computedSize, computedCrc) = computeSizeAndCrc(inZip, entry, buffer)
                                    newEntry.size = computedSize
                                    newEntry.compressedSize = computedSize
                                    newEntry.crc = computedCrc
                                }
                            } else {
                                newEntry.method = ZipEntry.DEFLATED
                            }

                            outZip.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                inZip.getInputStream(entry).use { input ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        outZip.write(buffer, 0, read)
                                    }
                                }
                            }
                            outZip.closeEntry()
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun computeSizeAndCrc(zip: ZipFile, entry: ZipEntry, buffer: ByteArray): Pair<Long, Long> {
        val crc = CRC32()
        var total = 0L
        zip.getInputStream(entry).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                crc.update(buffer, 0, read)
                total += read
            }
        }
        return total to crc.value
    }

    private fun rewriteAxmlPackage(axmlBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        if (oldPkg.isEmpty() || newPkg.isEmpty()) return axmlBytes

        val targetOld8 = oldPkg.toByteArray(Charsets.UTF_8)
        val targetNew8 = newPkg.toByteArray(Charsets.UTF_8)
        val targetOld16 = oldPkg.toByteArray(Charsets.UTF_16LE)
        val targetNew16 = newPkg.toByteArray(Charsets.UTF_16LE)

        val out = ByteArrayOutputStream(axmlBytes.size + 1024)

        var i = 0
        while (i < axmlBytes.size) {
            val match8 = matchesAt(axmlBytes, i, targetOld8)
            val match16 = if (!match8) matchesAt(axmlBytes, i, targetOld16) else false

            when {
                match8 -> {
                    out.write(targetNew8)
                    i += targetOld8.size
                }
                match16 -> {
                    out.write(targetNew16)
                    i += targetOld16.size
                }
                else -> {
                    out.write(axmlBytes[i].toInt())
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    private fun matchesAt(bytes: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (target.isEmpty() || offset + target.size > bytes.size) return false
        for (j in target.indices) {
            if (bytes[offset + j] != target[j]) return false
        }
        return true
    }
}
