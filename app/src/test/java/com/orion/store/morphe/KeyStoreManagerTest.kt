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
}
