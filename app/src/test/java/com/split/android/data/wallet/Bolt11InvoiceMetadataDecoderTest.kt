package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bolt11InvoiceMetadataDecoderTest {
    @Test
    fun extractsPaymentHashAndRecoversDestinationPubkey() {
        val metadata = Bolt11InvoiceMetadataDecoder.decode(bolt11DonationInvoice)

        requireNotNull(metadata)
        assertNull(metadata.amountSats)
        assertEquals(paymentHash, metadata.paymentHash)
        assertEquals(destinationPubkey, metadata.destinationPubkey)
        assertEquals("Please consider supporting this project", metadata.description)
    }

    @Test
    fun extractsFixedAmountInvoices() {
        val metadata = Bolt11InvoiceMetadataDecoder.decode(bolt11CoffeeInvoice)

        requireNotNull(metadata)
        assertEquals(250_000L, metadata.amountSats)
        assertEquals(paymentHash, metadata.paymentHash)
        assertEquals(destinationPubkey, metadata.destinationPubkey)
        assertEquals("1 cup coffee", metadata.description)
    }

    @Test
    fun rejectsInvalidChecksums() {
        val invalidChecksum = bolt11DonationInvoice.dropLast(1) + "x"

        assertNull(Bolt11InvoiceMetadataDecoder.decode(invalidChecksum))
    }

    @Test
    fun rejectsNonCanonicalExplicitPayeeSignatures() {
        assertNull(Bolt11InvoiceMetadataDecoder.decode(bolt11InvalidHighSWithExplicitPayeeInvoice))
    }

    private companion object {
        const val paymentHash =
            "0001020304050607080900010203040506070809000102030405060708090102"
        const val destinationPubkey =
            "03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"
        const val bolt11DonationInvoice =
            "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
        const val bolt11CoffeeInvoice =
            "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
        const val bolt11InvalidHighSWithExplicitPayeeInvoice =
            "lnbc25m1p70xwfzpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaqnp4q0n326hr8v9zprg8gsvezcch06gfaqqhde2aj730yg0durunfhv66sp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsp5cfzp9ugllvk03rltd6hvndxj26ux6gcxc5azyxk060rj9tzghct5zvjlps76gx8wpq5yuu79688k8gnm2c0al6v608s96l0xzrrlqqwnzxmu"
    }
}
