package com.orion.store.morphe.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        outputApk.parentFile?.mkdirs()

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

    /**
     * Rewrites package and authority strings inside binary AndroidManifest.xml.
     * Uses a genuine Android Binary XML StringPool parser and rebuilder.
     * If input is not binary AXML or parsing fails, falls back gracefully to raw byte replacement.
     */
    internal fun rewriteAxmlPackage(axmlBytes: ByteArray, targetPkg: String, newPkg: String): ByteArray {
        val needsCloning = targetPkg.isNotBlank() && newPkg.isNotBlank() && targetPkg != newPkg
        if (axmlBytes.size < 36) return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)

        return try {
            val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)
            val rootType = buf.getShort(0).toInt() and 0xFFFF
            val rootHeaderSize = buf.getShort(2).toInt() and 0xFFFF
            if (rootType != 0x0003 || rootHeaderSize != 8) {
                return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
            }

            // Find StringPool chunk (type 0x0001, headerSize 28)
            var spOffset = 8
            var foundSp = false
            while (spOffset + 8 <= axmlBytes.size) {
                val chunkType = buf.getShort(spOffset).toInt() and 0xFFFF
                val chunkSize = buf.getInt(spOffset + 4)
                if (chunkType == 0x0001) {
                    foundSp = true
                    break
                }
                if (chunkSize <= 0 || spOffset + chunkSize > axmlBytes.size) break
                spOffset += chunkSize
            }

            if (!foundSp || spOffset + 28 > axmlBytes.size) {
                return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
            }

            val oldSpHeaderSize = buf.getShort(spOffset + 2).toInt() and 0xFFFF
            val oldSpChunkSize = buf.getInt(spOffset + 4)
            if (oldSpHeaderSize < 28 || spOffset + oldSpChunkSize > axmlBytes.size) {
                return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
            }

            val stringCount = buf.getInt(spOffset + 8)
            val styleCount = buf.getInt(spOffset + 12)
            val flags = buf.getInt(spOffset + 16)
            val oldStringsStart = buf.getInt(spOffset + 20)
            val oldStylesStart = buf.getInt(spOffset + 24)

            val isUtf8 = (flags and 0x0100) != 0

            if (stringCount < 0 || stringCount > 200_000 || spOffset + 28 + stringCount * 4 > axmlBytes.size) {
                return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
            }

            val stringOffsets = IntArray(stringCount) { i ->
                buf.getInt(spOffset + 28 + i * 4)
            }

            val stringsDataStart = spOffset + oldStringsStart
            val originalStrings = ArrayList<String>(stringCount)

            for (i in 0 until stringCount) {
                val offset = stringOffsets[i]
                var pos = stringsDataStart + offset
                if (pos < 0 || pos >= axmlBytes.size) {
                    return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
                }

                if (isUtf8) {
                    // Read UTF-16 length varint
                    val b1 = axmlBytes[pos].toInt() and 0xFF
                    pos++
                    var u16Len = b1
                    if ((b1 and 0x80) != 0 && pos < axmlBytes.size) {
                        val b2 = axmlBytes[pos].toInt() and 0xFF
                        pos++
                        u16Len = ((b1 and 0x7F) shl 8) or b2
                    }
                    // Read UTF-8 byte length varint
                    if (pos >= axmlBytes.size) return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
                    val b3 = axmlBytes[pos].toInt() and 0xFF
                    pos++
                    var u8Len = b3
                    if ((b3 and 0x80) != 0 && pos < axmlBytes.size) {
                        val b4 = axmlBytes[pos].toInt() and 0xFF
                        pos++
                        u8Len = ((b3 and 0x7F) shl 8) or b4
                    }
                    if (pos + u8Len > axmlBytes.size) return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
                    originalStrings.add(String(axmlBytes, pos, u8Len, Charsets.UTF_8))
                } else {
                    // Read char count (uint16 or two uint16)
                    if (pos + 2 > axmlBytes.size) return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
                    val len1 = (axmlBytes[pos].toInt() and 0xFF) or ((axmlBytes[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    var u16Len = len1
                    if ((len1 and 0x8000) != 0 && pos + 2 <= axmlBytes.size) {
                        val len2 = (axmlBytes[pos].toInt() and 0xFF) or ((axmlBytes[pos + 1].toInt() and 0xFF) shl 8)
                        pos += 2
                        u16Len = ((len1 and 0x7FFF) shl 16) or len2
                    }
                    val byteLen = u16Len * 2
                    if (pos + byteLen > axmlBytes.size) return fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
                    originalStrings.add(String(axmlBytes, pos, byteLen, Charsets.UTF_16LE))
                }
            }

            var modified = false
            val newStrings = originalStrings.map { str ->
                val rewritten = if (needsCloning) rewritePackageString(str, targetPkg, newPkg) else str
                if (rewritten != str) modified = true
                rewritten
            }

            if (!modified) {
                return axmlBytes
            }

            // Encode new strings with length prefixes and null terminators
            val strData = ByteArrayOutputStream()
            val newOffsets = IntArray(stringCount)

            for (i in 0 until stringCount) {
                // Compute new 4-byte aligned stringOffsets
                while (strData.size() % 4 != 0) {
                    strData.write(0)
                }
                newOffsets[i] = strData.size()

                val s = newStrings[i]
                if (isUtf8) {
                    val utf8Bytes = s.toByteArray(Charsets.UTF_8)
                    val u16Len = s.length
                    val u8Len = utf8Bytes.size
                    if (u16Len > 0x7F) {
                        strData.write(((u16Len ushr 8) and 0x7F) or 0x80)
                        strData.write(u16Len and 0xFF)
                    } else {
                        strData.write(u16Len and 0x7F)
                    }
                    if (u8Len > 0x7F) {
                        strData.write(((u8Len ushr 8) and 0x7F) or 0x80)
                        strData.write(u8Len and 0xFF)
                    } else {
                        strData.write(u8Len and 0x7F)
                    }
                    strData.write(utf8Bytes)
                    strData.write(0) // 1-byte null terminator
                } else {
                    val u16Len = s.length
                    if (u16Len > 0x7FFF) {
                        val high = ((u16Len ushr 16) and 0x7FFF) or 0x8000
                        val low = u16Len and 0xFFFF
                        strData.write(high and 0xFF)
                        strData.write((high ushr 8) and 0xFF)
                        strData.write(low and 0xFF)
                        strData.write((low ushr 8) and 0xFF)
                    } else {
                        strData.write(u16Len and 0xFF)
                        strData.write((u16Len ushr 8) and 0xFF)
                    }
                    strData.write(s.toByteArray(Charsets.UTF_16LE))
                    strData.write(0)
                    strData.write(0) // 2-byte null terminator
                }
            }

            // Pad strings data to 4-byte boundary
            while (strData.size() % 4 != 0) {
                strData.write(0)
            }
            val stringsDataBytes = strData.toByteArray()

            var newStringsStart = 28 + stringCount * 4 + styleCount * 4
            while (newStringsStart % 4 != 0) {
                newStringsStart++
            }

            val oldStylesDataLength = if (styleCount > 0 && oldStylesStart > 0 && oldStylesStart < oldSpChunkSize) {
                oldSpChunkSize - oldStylesStart
            } else 0

            val newStylesStart = if (styleCount > 0) newStringsStart + stringsDataBytes.size else 0
            val newSpChunkSize = if (styleCount > 0) newStylesStart + oldStylesDataLength else newStringsStart + stringsDataBytes.size

            // Build new StringPool chunk
            val spBaos = ByteArrayOutputStream(newSpChunkSize)
            // Header (8 bytes)
            writeShortLE(spBaos, 0x0001)
            writeShortLE(spBaos, 28)
            writeIntLE(spBaos, newSpChunkSize)

            // Header fields (20 bytes)
            writeIntLE(spBaos, stringCount)
            writeIntLE(spBaos, styleCount)
            writeIntLE(spBaos, flags)
            writeIntLE(spBaos, newStringsStart)
            writeIntLE(spBaos, newStylesStart)

            // String offsets
            for (off in newOffsets) {
                writeIntLE(spBaos, off)
            }

            // Copy style offsets if styleCount > 0
            if (styleCount > 0) {
                val styleOffsetsStart = spOffset + 28 + stringCount * 4
                val styleOffsetsLen = styleCount * 4
                if (styleOffsetsStart + styleOffsetsLen <= axmlBytes.size) {
                    spBaos.write(axmlBytes, styleOffsetsStart, styleOffsetsLen)
                }
            }

            // Pad up to newStringsStart
            while (spBaos.size() < newStringsStart) {
                spBaos.write(0)
            }

            // Strings data
            spBaos.write(stringsDataBytes)

            // Styles data (if styleCount > 0)
            if (styleCount > 0 && oldStylesDataLength > 0) {
                val stylesDataSrc = spOffset + oldStylesStart
                if (stylesDataSrc + oldStylesDataLength <= axmlBytes.size) {
                    spBaos.write(axmlBytes, stylesDataSrc, oldStylesDataLength)
                }
            }

            // Pad chunk to newSpChunkSize
            while (spBaos.size() < newSpChunkSize) {
                spBaos.write(0)
            }

            val newSpBytes = spBaos.toByteArray()

            // Remaining chunks after StringPool
            val remainingOffset = spOffset + oldSpChunkSize
            val remainingLength = (axmlBytes.size - remainingOffset).coerceAtLeast(0)

            val newRootChunkSize = spOffset + newSpBytes.size + remainingLength

            val finalBaos = ByteArrayOutputStream(newRootChunkSize)
            // Root chunk header (8 bytes)
            writeShortLE(finalBaos, rootType)
            writeShortLE(finalBaos, rootHeaderSize)
            writeIntLE(finalBaos, newRootChunkSize)

            // Any bytes between root header and StringPool
            if (spOffset > 8) {
                finalBaos.write(axmlBytes, 8, spOffset - 8)
            }

            // New StringPool chunk
            finalBaos.write(newSpBytes)

            // Remaining chunks
            if (remainingLength > 0) {
                finalBaos.write(axmlBytes, remainingOffset, remainingLength)
            }

            finalBaos.toByteArray()
        } catch (e: Exception) {
            fallbackRawRewrite(axmlBytes, targetPkg, newPkg)
        }
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun fallbackRawRewrite(axmlBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        var current = axmlBytes
        if (oldPkg.isNotEmpty() && newPkg.isNotEmpty() && oldPkg != newPkg) {
            current = rawReplace(current, oldPkg, newPkg)
        }
        current = rawReplace(current, "com.android.vending.splits.required", "com.android.vending.splits.disabled")
        return current
    }

    private fun rawReplace(bytes: ByteArray, oldStr: String, newStr: String): ByteArray {
        val targetOld8 = oldStr.toByteArray(Charsets.UTF_8)
        val targetNew8 = newStr.toByteArray(Charsets.UTF_8)
        val targetOld16 = oldStr.toByteArray(Charsets.UTF_16LE)
        val targetNew16 = newStr.toByteArray(Charsets.UTF_16LE)

        val out = ByteArrayOutputStream(bytes.size + 1024)
        var i = 0
        while (i < bytes.size) {
            val match8 = matchesAt(bytes, i, targetOld8)
            val match16 = if (!match8) matchesAt(bytes, i, targetOld16) else false

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
                    out.write(bytes[i].toInt())
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

    /**
     * Replaces [targetPkg] only at package/authority boundaries (exact match, `.sub`, `:suffix`, `/path`, `://host`),
     * preventing substring corruption such as rewriting `com.foo.bar2` when cloning `com.foo.bar`.
     */
    internal fun rewritePackageString(value: String, targetPkg: String, newPkg: String): String {
        if (targetPkg.isBlank() || targetPkg == newPkg || !value.contains(targetPkg)) return value
        val boundaryRegex = Regex("""(?<![A-Za-z0-9_.])""" + Regex.escape(targetPkg) + """(?=$|[.:/])""")
        return boundaryRegex.replace(value, newPkg)
    }
}
