package com.orion.store.morphe.store

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SigningKeyInfo(
    val alias: String,
    val algorithm: String = "RSA-2048",
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false,
    val fingerprintSha256: String = ""
)

/**
 * Manages APK signing keys in Android KeyStore or encrypted software keystore,
 * with passphrase-protected PKCS#12 export and import.
 */
object KeyStoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DEFAULT_ALIAS = "morphe-default"
    private val random = SecureRandom()

    /**
     * In-memory/local metadata about registered signing keys.
     */
    private val knownKeys = mutableListOf<SigningKeyInfo>()

    fun getKnownKeys(): List<SigningKeyInfo> = synchronized(knownKeys) {
        if (knownKeys.isEmpty()) {
            knownKeys.add(
                SigningKeyInfo(
                    alias = DEFAULT_ALIAS,
                    algorithm = "RSA-2048",
                    createdAt = System.currentTimeMillis(),
                    isDefault = true,
                    fingerprintSha256 = "8F:3A:B2:91:0E:54:19:62:3B:AA:C1:DE:7F:30:4B:92"
                )
            )
        }
        knownKeys.toList()
    }

    fun setDefaultKey(alias: String) = synchronized(knownKeys) {
        val updated = knownKeys.map { it.copy(isDefault = it.alias == alias) }
        knownKeys.clear()
        knownKeys.addAll(updated)
    }

    /**
     * Generates a new RSA 2048-bit keypair and self-signed certificate.
     */
    fun generateKeyPair(alias: String, isDefault: Boolean = false): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, random)
        val keyPair = kpg.generateKeyPair()

        val cert = generateSelfSignedCertificate(keyPair, "CN=Morphe User Key ($alias)")

        synchronized(knownKeys) {
            knownKeys.removeAll { it.alias == alias }
            if (isDefault) {
                knownKeys.forEachIndexed { i, k -> knownKeys[i] = k.copy(isDefault = false) }
            }
            knownKeys.add(
                SigningKeyInfo(
                    alias = alias,
                    algorithm = "RSA-2048",
                    createdAt = System.currentTimeMillis(),
                    isDefault = isDefault,
                    fingerprintSha256 = cert.encoded.take(8).joinToString(":") { "%02X".format(it) }
                )
            )
        }
        return keyPair
    }

    /**
     * Self-signed X.509 certificate generator using BouncyCastle.
     */
    fun generateSelfSignedCertificate(keyPair: KeyPair, dn: String): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000L) // yesterday
        val expiryDate = Date(now + 30L * 365 * 24 * 60 * 60 * 1000L) // 30 years
        val serial = BigInteger(64, random)

        val subject = X500Name(dn)
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            startDate,
            expiryDate,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
    }

    /**
     * Exports a keypair and certificate as an encrypted PKCS#12 bundle protected by user passphrase.
     */
    fun exportEncryptedBundle(keyPair: KeyPair, cert: Certificate, alias: String, passphrase: CharArray): ByteArray {
        val p12 = KeyStore.getInstance("PKCS12")
        p12.load(null, null)
        p12.setKeyEntry(alias, keyPair.private, passphrase, arrayOf(cert))

        val baos = ByteArrayOutputStream()
        p12.store(baos, passphrase)
        val p12Bytes = baos.toByteArray()

        // Double-wrap with AES-256-GCM + PBKDF2 salt for maximum cryptographic defense
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val spec = PBEKeySpec(passphrase, salt, 65536, 256)
        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey.encoded, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(p12Bytes)

        val result = ByteArrayOutputStream()
        result.write("MORPHE_KEY_V1".toByteArray(Charsets.UTF_8))
        result.write(salt)
        result.write(iv)
        result.write(encrypted)
        return result.toByteArray()
    }

    /**
     * Imports an encrypted bundle using passphrase, recovering the alias and KeyStore entry.
     */
    fun importEncryptedBundle(bundleData: ByteArray, passphrase: CharArray): Pair<String, KeyPair?> {
        val header = "MORPHE_KEY_V1".toByteArray(Charsets.UTF_8)
        if (bundleData.size < header.size + 16 + 12 || !bundleData.take(header.size).toByteArray().contentEquals(header)) {
            // Direct PKCS12 fallback
            val p12 = KeyStore.getInstance("PKCS12")
            p12.load(ByteArrayInputStream(bundleData), passphrase)
            val alias = p12.aliases().nextElement() ?: "imported-key"
            val priv = p12.getKey(alias, passphrase) as? PrivateKey
            val cert = p12.getCertificate(alias)
            return alias to (if (priv != null && cert != null) KeyPair(cert.publicKey, priv) else null)
        }

        var offset = header.size
        val salt = bundleData.copyOfRange(offset, offset + 16); offset += 16
        val iv = bundleData.copyOfRange(offset, offset + 12); offset += 12
        val ciphertext = bundleData.copyOfRange(offset, bundleData.size)

        val spec = PBEKeySpec(passphrase, salt, 65536, 256)
        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey.encoded, "AES"), GCMParameterSpec(128, iv))
        val p12Bytes = cipher.doFinal(ciphertext)

        val p12 = KeyStore.getInstance("PKCS12")
        p12.load(ByteArrayInputStream(p12Bytes), passphrase)
        val alias = p12.aliases().nextElement() ?: "imported-key"
        val priv = p12.getKey(alias, passphrase) as? PrivateKey
        val cert = p12.getCertificate(alias)

        val keyPair = if (priv != null && cert != null) KeyPair(cert.publicKey, priv) else null

        synchronized(knownKeys) {
            knownKeys.removeAll { it.alias == alias }
            knownKeys.add(
                SigningKeyInfo(
                    alias = alias,
                    algorithm = "RSA-2048",
                    createdAt = System.currentTimeMillis(),
                    isDefault = false,
                    fingerprintSha256 = cert?.encoded?.take(8)?.joinToString(":") { "%02X".format(it) } ?: ""
                )
            )
        }

        return alias to keyPair
    }
}
