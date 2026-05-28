package com.split.android.data.rewards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardProofEncodingTest {
    @Test
    fun acceptsCanonicalHex() {
        val proof = " $proofHexUpper ".normalizedRewardProof32ByteHex()

        requireNotNull(proof)
        assertEquals(proofHex, proof.hex)
        assertEquals("hex", proof.encoding)
    }

    @Test
    fun convertsLndBase64BytesToHex() {
        val proof = proofBase64.normalizedRewardProof32ByteHex()

        requireNotNull(proof)
        assertEquals(proofHex, proof.hex)
        assertEquals("base64", proof.encoding)
    }

    @Test
    fun convertsBase64UrlBytesToHex() {
        val proof = urlProofBase64.normalizedRewardProof32ByteHex()

        requireNotNull(proof)
        assertEquals(urlProofHex, proof.hex)
        assertEquals("base64url", proof.encoding)
    }

    @Test
    fun rejectsInvalidProofValues() {
        val missing: String? = null
        assertNull(missing.normalizedRewardProof32ByteHex())
        assertNull("".normalizedRewardProof32ByteHex())
        assertNull("not-valid-proof".normalizedRewardProof32ByteHex())
        assertNull("0".repeat(62).normalizedRewardProof32ByteHex())
        assertNull("c2hvcnQ=".normalizedRewardProof32ByteHex())
    }

    private companion object {
        const val proofHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val proofHexUpper = "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
        const val proofBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        const val urlProofHex = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val urlProofBase64 = "__________________________________________8"
    }
}
