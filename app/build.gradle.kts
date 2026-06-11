plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

fun String.asBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val splitLocalProperties = Properties().apply {
    val splitLocalPropertiesFile = rootProject.file("split.local.properties")
    if (splitLocalPropertiesFile.exists()) {
        splitLocalPropertiesFile.inputStream().use(::load)
    }
}
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
fun configuredProperty(name: String, defaultValue: String): String {
    val localOverride = splitLocalProperties.getProperty(name)
        ?: localProperties.getProperty(name)
    return providers.gradleProperty(name)
        .orElse(localOverride ?: defaultValue)
        .get()
}
val splitDebugBaseUrl = configuredProperty("split.debugBaseUrl", "http://localhost:3000")
val splitReleaseBaseUrl = configuredProperty("split.releaseBaseUrl", "https://example.invalid")
val splitMessagingIdentityDomain = configuredProperty("split.messagingIdentityDomain", "example.com")
val splitLightningAddressDomain = configuredProperty("split.lightningAddressDomain", "example.com")
val splitSupportLightningAddress = configuredProperty("split.supportLightningAddress", "support@example.com")
val splitApplicationId = configuredProperty("split.applicationId", "com.example.splitandroid")
val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse(splitLocalProperties.getProperty("MAPS_API_KEY") ?: localProperties.getProperty("MAPS_API_KEY") ?: "")
    .get()
val hasGoogleServicesConfig = file("google-services.json").exists()

if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    // Source packages and Android namespace remain under `com.split.android`;
    // the public mirror uses a placeholder application ID instead.
    namespace = "com.split.android"
    compileSdk = 37

    defaultConfig {
        applicationId = splitApplicationId
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.7.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", mapsApiKey.asBuildConfigString())
        buildConfigField("String", "MESSAGING_IDENTITY_DOMAIN", splitMessagingIdentityDomain.asBuildConfigString())
        buildConfigField("String", "LIGHTNING_ADDRESS_DOMAIN", splitLightningAddressDomain.asBuildConfigString())
        buildConfigField("String", "SUPPORT_LIGHTNING_ADDRESS", splitSupportLightningAddress.asBuildConfigString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", splitDebugBaseUrl.asBuildConfigString())
            buildConfigField("String", "MESSAGING_PUSH_ENV", "\"dev\"")
        }
        release {
            buildConfigField("String", "BASE_URL", splitReleaseBaseUrl.asBuildConfigString())
            buildConfigField("String", "MESSAGING_PUSH_ENV", "\"prod\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.13.0")

    implementation(composeBom)
    implementation(firebaseBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.media3:media3-transformer:1.9.2")
    implementation("androidx.media3:media3-effect:1.9.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("info.guardianproject:tor-android:0.4.9.8")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("breez_sdk_spark:bindings-android:0.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.13.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.maps.android:maps-compose:8.2.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-base:18.1.0")
    implementation("com.google.android.gms:play-services-basement:18.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
