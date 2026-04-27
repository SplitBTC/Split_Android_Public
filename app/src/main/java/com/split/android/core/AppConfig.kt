package com.split.android.core

import com.example.splitandroid.BuildConfig
import java.net.URL

object AppConfig {
    private fun requiredConfigValue(rawValue: String, label: String): String {
        return rawValue.trim().also { configuredValue ->
            require(configuredValue.isNotEmpty()) {
                "$label is missing from the Android build configuration."
            }
        }
    }

    val baseUrl: String = requiredConfigValue(BuildConfig.BASE_URL, "BASE_URL")

    val messagingPushEnvironment: String = BuildConfig.MESSAGING_PUSH_ENV
        .trim()
        .lowercase()
        .takeIf { it == "dev" || it == "prod" }
        ?: run {
            val host = runCatching { URL(baseUrl).host.orEmpty().trim().lowercase() }.getOrDefault("")
            if (host == "localhost" || host.endsWith(".local") || host.contains("dev") || host.contains("ngrok")) {
                "dev"
            } else {
                "prod"
            }
        }

    val messagingIdentityDomain: String =
        requiredConfigValue(BuildConfig.MESSAGING_IDENTITY_DOMAIN, "MESSAGING_IDENTITY_DOMAIN")
            .lowercase()

    val lightningAddressDomain: String =
        requiredConfigValue(BuildConfig.LIGHTNING_ADDRESS_DOMAIN, "LIGHTNING_ADDRESS_DOMAIN")
            .lowercase()

    val supportLightningAddress: String =
        requiredConfigValue(BuildConfig.SUPPORT_LIGHTNING_ADDRESS, "SUPPORT_LIGHTNING_ADDRESS")
            .lowercase()

    val usesPublicPlaceholderConfig: Boolean = BuildConfig.BASE_URL.contains("example.invalid") ||
        (
            messagingIdentityDomain == "example.com" &&
                lightningAddressDomain == "example.com" &&
                supportLightningAddress == "support@example.com"
            )
}
