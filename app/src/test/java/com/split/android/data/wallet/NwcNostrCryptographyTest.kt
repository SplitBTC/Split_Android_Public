package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcNostrCryptographyTest {
    @Test
    fun signsAndVerifiesNostrEvents() {
        val event = NwcNostrCryptography.signEvent(
            privateKeyHex = testSecret,
            createdAt = 1_765_000_000L,
            kind = 23194,
            tags = listOf(listOf("p", testWalletPubkey)),
            content = """{"method":"get_balance","params":{}}"""
        )

        assertTrue(NwcNostrCryptography.verifyEvent(event))

        val tampered = event.copy(content = """{"method":"pay_invoice","params":{}}""")
        assertFalse(NwcNostrCryptography.verifyEvent(tampered))
    }

    @Test
    fun encryptsAndDecryptsNip44Payloads() {
        val plaintext = """{"method":"get_balance","params":{}}"""
        val encrypted = NwcNostrCryptography.nip44Encrypt(
            plaintext = plaintext,
            senderPrivateKeyHex = testSecret,
            recipientPublicKeyHex = testWalletPubkey
        )

        val decrypted = NwcNostrCryptography.nip44Decrypt(
            payload = encrypted,
            recipientPrivateKeyHex = testWalletSecret,
            senderPublicKeyHex = NwcNostrCryptography.publicKeyHex(testSecret)
        )

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptsAndDecryptsNip04Payloads() {
        val plaintext = """{"method":"get_balance","params":{}}"""
        val encrypted = NwcNostrCryptography.nip04Encrypt(
            plaintext = plaintext,
            senderPrivateKeyHex = testSecret,
            recipientPublicKeyHex = testWalletPubkey
        )

        val decrypted = NwcNostrCryptography.nip04Decrypt(
            payload = encrypted,
            recipientPrivateKeyHex = testWalletSecret,
            senderPublicKeyHex = NwcNostrCryptography.publicKeyHex(testSecret)
        )

        assertEquals(plaintext, decrypted)
    }
}

