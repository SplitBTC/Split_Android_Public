# Split Android

Native Android client for Split, built with Kotlin and Jetpack Compose.

This app includes:

- self-custodial wallet flows through Breez Spark
- Lightning address management and wallet-authenticated backend sessions
- Split messaging with contacts, inbox/thread views, attachments, and push sync
- rewards, merchant discovery, merchant reporting, and map flows
- Proof of Spend posting, profile, legal, and support surfaces

## Why This Project Exists

The project-purpose writeup lives here:

- [PROJECT_PURPOSE.md](./PROJECT_PURPOSE.md)

That document explains the core thesis behind Split: Bitcoin should be usable as money, spending requires coordination, and communication is part of making a real Bitcoin economy work.

## Repo Status

This is a real Android app project, not a sample app.

This public repository exists for transparency and source availability. Active development may occur privately before released code is synced here. Pull requests may not be reviewed or merged.

The Android client may lag the private `Split Android` repo and may lag the iOS app as new work lands privately before publication sweeps are done.

## Requirements

- Android Studio current stable recommended
- JDK `17`
- Android SDK components for `compileSdk 36`
- a physical Android device if you want to test on hardware

## Opening the Project

Open this folder as a project in Android Studio.

The main app module lives in:

- [app](./app)

Primary Kotlin sources live in:

- [app/src/main/java/com/split/android](./app/src/main/java/com/split/android)

Unit tests live in:

- [app/src/test/java/com/split/android](./app/src/test/java/com/split/android)

## Build Notes

This public snapshot uses public-safe placeholder configuration by default.

If you are building under your own backend, Firebase project, or signing setup, you will probably need to:

- set your own application ID
- provide your own `app/google-services.json` if you want FCM push notifications
- set your own backend, messaging, and Lightning domain values
- provide your own maps API key if you want map features
- use your own signing configuration for release builds

The committed defaults are intentionally not production-ready for the real Split infrastructure.

## Backend And App Configuration

Public-safe defaults live in:

- [app/build.gradle.kts](./app/build.gradle.kts)

Local overrides can be created from:

- [split.local.properties.example](./split.local.properties.example)

That local override file is gitignored.

Supported local override keys include:

- `split.debugBaseUrl`
- `split.releaseBaseUrl`
- `split.messagingIdentityDomain`
- `split.lightningAddressDomain`
- `split.supportLightningAddress`
- `split.applicationId`
- `MAPS_API_KEY`

The app reads runtime values in [AppConfig.kt](./app/src/main/java/com/split/android/core/AppConfig.kt).

Important notes:

- debug defaults to `http://localhost:3000`
- release defaults to `https://example.invalid`
- Firebase setup is optional for local builds; add your own `app/google-services.json` if you want FCM push behavior
- this public repo intentionally does not commit a real Firebase config file
- the generated Android namespace is intentionally placeholder-based in this public mirror
- the in-app legal screen expects hosted documents from `BASE_URL`; with the committed public defaults it will intentionally show a placeholder notice instead of loading fake legal pages
- placeholder values are expected for backend hosts, support contacts, application identifiers, and messaging/lightning domains

If you are not an authorized developer working against Split infrastructure, point the app at your own backend before using it as a development client.

## Project Highlights

- [MainActivity.kt](./app/src/main/java/com/split/android/MainActivity.kt): app entry, version gate, and root Compose host
- [AppConfig.kt](./app/src/main/java/com/split/android/core/AppConfig.kt): runtime configuration surface
- [SplitRootViewModel.kt](./app/src/main/java/com/split/android/ui/SplitRootViewModel.kt): top-level orchestration for wallet, auth, messaging, rewards, and profile flows
- [data/messages](./app/src/main/java/com/split/android/data/messages): messaging identity, sync, crypto, storage, and push plumbing
- [data/wallet](./app/src/main/java/com/split/android/data/wallet): Breez Spark wallet lifecycle and payment flows
- [ui](./app/src/main/java/com/split/android/ui): Compose screens and theme

## Testing

This repo currently includes JVM unit tests in:

- [app/src/test/java/com/split/android](./app/src/test/java/com/split/android)

Run them with:

```bash
./gradlew test
```

There is currently no committed `app/src/androidTest` instrumentation suite in this public snapshot.

## Messaging Notes

The messaging trust/privacy writeup lives here:

- [MESSAGING_PRIVACY_AND_TRUST.md](./MESSAGING_PRIVACY_AND_TRUST.md)

That document is intentionally technical and conservative in scope.

## Open Source Hygiene

Before publishing or contributing, treat this repo as public:

- do not commit wallet seeds
- do not commit keystores or signing assets
- do not commit `app/google-services.json`
- do not commit `split.local.properties` or other local override files
- do not commit private support material
- do not assume the default backend URLs are appropriate for your own fork

## License

This repository is licensed under the Apache License 2.0.

See [LICENSE](./LICENSE).
