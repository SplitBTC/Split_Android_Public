package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class EclairConnectParserTest {

    @Test
    fun parsesEclairHttpConnectionQr() {
        val credentials = EclairConnectParser.parse(
            "eclair+http://umbrel.local:8080?password=test-password&name=Umbrel%20Eclair"
        )

        assertEquals("http", credentials.scheme)
        assertEquals("umbrel.local", credentials.host)
        assertEquals(8080, credentials.port)
        assertEquals("test-password", credentials.apiPassword)
        assertEquals("Umbrel Eclair", credentials.label)
    }

    @Test
    fun parsesJsonConnectionQrWithUrl() {
        val credentials = EclairConnectParser.parse(
            """
            {
              "url": "https://192.168.1.25:8443",
              "apiPassword": "json+password",
              "label": "Eclair Node"
            }
            """.trimIndent()
        )

        assertEquals("https", credentials.scheme)
        assertEquals("192.168.1.25", credentials.host)
        assertEquals(8443, credentials.port)
        assertEquals("json+password", credentials.apiPassword)
        assertEquals("Eclair Node", credentials.label)
    }

    @Test
    fun normalizesUrlShapedManualHost() {
        val credentials = EclairConnectParser.parse(
            scheme = "http",
            host = "http://192.168.1.25:8080/api",
            port = 8080,
            apiPassword = "test-password"
        )

        assertEquals("192.168.1.25", credentials.host)
    }

    @Test
    fun preservesLiteralPlusInQueryPassword() {
        val credentials = EclairConnectParser.parse(
            "eclair+http://umbrel.local:8080?password=abc+def%2Fghi"
        )

        assertEquals("abc+def/ghi", credentials.apiPassword)
    }
}
