package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreLightningConnectParserTest {

    @Test
    fun preservesLiteralPlusInRuneQueryValue() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=abc+def/ghi=&name=Umbrel%20CLN"
        )

        assertEquals("https", credentials.scheme)
        assertEquals("umbrel.local", credentials.host)
        assertEquals(3010, credentials.port)
        assertEquals("abc+def/ghi=", credentials.rune)
        assertEquals("Umbrel CLN", credentials.label)
    }

    @Test
    fun decodesEncodedPlusInRuneQueryValue() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=abc%2Bdef%2Fghi%3D"
        )

        assertEquals("abc+def/ghi=", credentials.rune)
    }

    @Test
    fun preservesLiteralPlusInCertificateQueryValue() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=test-rune&cert=+/8="
        )

        assertEquals("+/8=", credentials.tlsCertificateDerBase64)
    }

    @Test
    fun decodesEncodedPlusInCertificateQueryValue() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=test-rune&cert=%2B%2F8%3D"
        )

        assertEquals("+/8=", credentials.tlsCertificateDerBase64)
    }

    @Test
    fun acceptsWrappedRestUrlWithPlusRune() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest://https://umbrel.local:3010?rune=abc+def"
        )

        assertEquals("https", credentials.scheme)
        assertEquals("umbrel.local", credentials.host)
        assertEquals("abc+def", credentials.rune)
    }

    @Test
    fun acceptsStart9QuickConnectOnionFormat() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest://03abcdef@abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl.onion:3010?rune=test-rune"
        )

        assertEquals("https", credentials.scheme)
        assertEquals("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl.onion", credentials.host)
        assertEquals(3010, credentials.port)
        assertEquals("test-rune", credentials.rune)
    }

    @Test
    fun acceptsJsonConnectionPayloadWithUrlAndTopLevelFields() {
        val credentials = CoreLightningConnectParser.parse(
            """
            {
              "url": "https://192.168.1.25:3010?name=URL%20Name",
              "rune": "json+rune",
              "cert": "+/8=",
              "label": "JSON Name"
            }
            """.trimIndent()
        )

        assertEquals("https", credentials.scheme)
        assertEquals("192.168.1.25", credentials.host)
        assertEquals("json+rune", credentials.rune)
        assertEquals("+/8=", credentials.tlsCertificateDerBase64)
        assertEquals("URL Name", credentials.label)
    }

    @Test
    fun jsonUrlQueryFieldsWinOverTopLevelFields() {
        val credentials = CoreLightningConnectParser.parse(
            """
            {
              "url": "https://192.168.1.25:3010?rune=url%2Brune&cert=%2B%2F8%3D&name=URL%20Name",
              "rune": "json+rune",
              "cert": "AQID",
              "label": "JSON Name"
            }
            """.trimIndent()
        )

        assertEquals("url+rune", credentials.rune)
        assertEquals("+/8=", credentials.tlsCertificateDerBase64)
        assertEquals("URL Name", credentials.label)
    }

    @Test
    fun rejectsRuneWhitespaceAfterRealSpaceDecode() {
        assertThrows(CoreLightningWalletException.InvalidRune::class.java) {
            CoreLightningConnectParser.parse(
                "clnrest+https://umbrel.local:3010?rune=abc%20def"
            )
        }
    }

    @Test
    fun acceptsConnectionWithoutCertificate() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=test-rune"
        )

        assertEquals("test-rune", credentials.rune)
        assertNull(credentials.tlsCertificateDerBase64)
    }

    @Test
    fun ignoresOfficialCombinedCertsBundleUntilBundleParsingIsSupported() {
        val credentials = CoreLightningConnectParser.parse(
            "clnrest+https://umbrel.local:3010?rune=test-rune&certs=%2B%2F8%3D"
        )

        assertEquals("test-rune", credentials.rune)
        assertNull(credentials.tlsCertificateDerBase64)
    }
}
