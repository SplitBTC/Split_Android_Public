package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSetupSupportTest {

    private val twelveReasonableWords = listOf(
        "abandon",
        "ability",
        "able",
        "about",
        "above",
        "absent",
        "absorb",
        "abstract",
        "absurd",
        "abuse",
        "access",
        "accident"
    )

    @Test
    fun normalizesRecoveryPhraseWords() {
        val words = normalizeRecoveryPhraseWords("  Abandon,\nAbility able  about  ")

        assertEquals(listOf("abandon", "ability", "able", "about"), words)
    }

    @Test
    fun acceptsReasonableRecoveryWordsWithoutWordlistCheck() {
        val message = validateRecoveryPhraseWords(
            words = listOf(
                "abandon",
                "ability",
                "able",
                "about",
                "above",
                "absent",
                "absorb",
                "abstract",
                "absurd",
                "abuse",
                "access",
                "typoo"
            )
        )

        assertNull(message)
    }

    @Test
    fun rejectsNonAlphabeticRecoveryWords() {
        val message = validateRecoveryPhraseWords(
            words = twelveReasonableWords.dropLast(1) + "acc1dent"
        )

        assertEquals(
            "That recovery phrase looks invalid. Double-check the words and their order.",
            message
        )
    }

    @Test
    fun acceptsTwelveWordRecoveryPhrase() {
        val message = validateRecoveryPhraseWords(
            words = twelveReasonableWords
        )

        assertNull(message)
    }

    @Test
    fun mapsSdkInternalConnectionErrorsToFriendlyMessage() {
        val message = walletConnectionFailureMessage(
            error = IllegalStateException("breez_sdk_spark.FfiException"),
            source = WalletConnectionSource.CREATE
        )

        assertEquals(
            "Unable to finish wallet setup right now. Please try again.",
            message
        )
    }

    @Test
    fun mapsNetworkLikeRestoreErrorsToServiceAvailabilityMessage() {
        val message = walletConnectionFailureMessage(
            error = IllegalStateException("Socket timeout while connecting"),
            source = WalletConnectionSource.RESTORE
        )

        assertEquals(
            "Unable to restore this wallet right now. Split or Breez may be temporarily unreachable. Please try again in a minute.",
            message
        )
    }

    @Test
    fun unwrapsInitializerErrorsIntoSdkChecksumMessage() {
        val message = walletConnectionFailureMessage(
            error = ExceptionInInitializerError(
                RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
            ),
            source = WalletConnectionSource.RESTORE
        )

        assertEquals(
            "Wallet SDK integrity check failed in this Android build. Please reinstall the latest app build and try again.",
            message
        )
    }

    @Test
    fun summarizesNestedFailureChain() {
        val summary = walletFailureDebugSummary(
            ExceptionInInitializerError(
                IllegalStateException("No implementation found for native register")
            )
        )

        assertTrue(summary.contains("java.lang.ExceptionInInitializerError"))
        assertTrue(summary.contains("java.lang.IllegalStateException: No implementation found for native register"))
    }

    @Test
    fun detectsInvalidRecoveryPhraseErrors() {
        val isInvalidPhrase = looksLikeInvalidRecoveryPhraseError(
            IllegalArgumentException("Invalid mnemonic: unknown word")
        )

        assertTrue(isInvalidPhrase)
        assertFalse(
            looksLikeInvalidRecoveryPhraseError(
                IllegalArgumentException("Socket timeout while connecting")
            )
        )
    }
}
