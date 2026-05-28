package com.split.android.data.rewards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardsRepositoryTest {
    @Test
    fun merchantPubkeyHashUsesBackendCompatibleNormalizationAndPrefix() {
        val expectedHash = "2b3878883bc0b1757f1d979dfad4f9ea727aaac7a193f533579a9f16ac27efcd"

        assertEquals(
            expectedHash,
            rewardMerchantPubkeyHash(" 03E7156AE33B0A208D0744199163177E909E80176E55D97A2F221EDE0F934DD9AD ")
        )
        assertNull(rewardMerchantPubkeyHash("   "))
    }
}
