# Split Android Public AGENTS.md

## Optional Internal Context

- Internal Split agents with access to the full project folder may also review `PROJECT_MAP_INTERNAL.md` at the project root for cross-repo context.
- External or public-only review agents should ignore that file. This repo's `AGENTS.md` is the complete repo-local guidance.

## Repo Role

This is the public Android repository for Split.

- It exists for inspection, transparency, and community scrutiny.
- It is not the primary day-to-day development repo.
- It may intentionally lag behind the private `Split Android` repo.
- It should represent a public-safe snapshot of Android app code when the user chooses to sync it.

## Intended Consumers

This file is for both implementation agents and review agents.

- Use it to understand what this repo is for before proposing changes or filing review findings.
- Do not treat this repo like the private source-of-truth Android repo unless explicitly instructed.

## System Relationships

- Private Android development happens in `Split Android`.
- This repo is a public publication target for Android code that is ready to be exposed.
- The app depends on the same `Split` backend as the iOS client.
- `Split Rewards` remains the primary product and behavior reference when Android parity questions arise.
- `Split Rewards Public` and `Split Backend Public` play the same role for the iOS app and backend.

## Non-Negotiable Rules

- This repo must always be open-source ready.
- Do not assume this repo should always mirror the private Android repo.
- Only sync code here when the user wants the public repo updated to a coherent public-safe snapshot.
- Treat every push to `main` as an immediate public release.
- There is no dev branch safety net here. If a change is not ready for public exposure, it does not belong in this repo.
- This repo is not primarily for outside contributions. Its main purpose is transparency, inspection, and scrutiny.
- Never launch Android emulators from this machine. The user tests manually on physical devices.

## Review Posture

If you are reviewing this repo:

- Judge it as a public publication target, not as the main active development repo.
- Do not assume that a difference from the private Android repo is automatically a bug.
- Do flag anything that weakens public transparency, public safety, or the coherence of the published snapshot.
- Prioritize findings around secrets, signing material, committed Firebase config, local-only overrides, misleading docs, or a snapshot that is obviously incomplete or inconsistent.
- Treat "this repo is behind private development" as expected unless the user says the public mirror should already include newer work.

## Public Release Rules

- Before publishing here, check for wallet seeds, keystores, signing assets, private support docs, internal notes, local-only config, and committed service credentials.
- Keep backend configuration, messaging/lightning domains, support/contact examples, Firebase setup, maps/API key setup, and application IDs public-safe unless the user explicitly chooses otherwise.
- Do not commit a real `app/google-services.json`.
- Make sure README and public docs accurately describe the repo's public role and limitations.

## Private-To-Public Sync Workflow

- Treat the private `Split Android` repo as the implementation source and this repo as a sanitized publication mirror.
- Do not do a blind file-for-file mirror from private to public.
- Sync newer production-ready code, tests, and architecture changes from private only after a publication sweep.
- Preserve the public repo's sanitization layer when it already exists.

When updating this public repo from private:

- keep public-facing README, AGENTS, and publication-oriented docs as the base versions
- update those docs only as needed to reflect new code or changed behavior
- preserve public-safe placeholders for application IDs, backend hosts, messaging/lightning domains, support/contact examples, and local config patterns
- keep Firebase and maps setup documentation public-safe and example-oriented
- exclude local-only config files, `google-services.json`, build outputs, signing assets, and internal-only notes

Default review stance during a sync:

- implementation changes should usually come from private
- sanitization, placeholder config, and public positioning should usually stay from public
- if the private version would reintroduce real identifiers or private setup details, re-apply the public version or adapt the change before publishing

## Current Repo Shape

- `app/`: Android application module
- `app/src/main/java/com/split/android/`: Kotlin app source
- `app/src/main/res/`: resources and manifest-linked assets
- `app/src/test/`: JVM unit tests
- `app/build.gradle.kts`: Android build config and public-safe defaults
- `split.local.properties.example`: local override example for public clones
- `gradle/`, `gradlew`, `gradlew.bat`: Gradle wrapper

Current public snapshot notes:

- Public-safe placeholder backend hosts, application IDs, messaging/lightning domains, and support/contact examples are expected here.
- A real `app/google-services.json` should not be committed.
- Trust the actual public repo contents when describing what is publicly available.

## Working Rules For Future Changes

- If syncing from `Split Android`, do a publication sweep before pushing:
- remove or avoid secrets
- remove internal-only material
- verify docs and examples are public-safe
- verify local override files are not committed
- verify the code reflects a coherent, reviewable snapshot
- preserve the public repo's sanitized README/docs/config posture unless the user explicitly wants that changed
- Keep backend URLs and other environment-sensitive values overridable through Gradle properties and `split.local.properties`.
- Preserve the same backend contract discipline as the private app: public code should still reflect stable, production-safe API usage.
- If a feature is live privately but not intended for public release yet, do not assume it belongs here.
- If asked to review or update this repo, optimize for public clarity and publication readiness, not internal development speed.

## Testing And Verification

- Never run Android emulators on this machine.
- The user tests manually on physical devices.
- Safe verification includes static review, Gradle builds, JVM unit tests, and careful public-safety sweeps.

## Coordination Notes

- Active feature work usually starts in the private backend and iOS repos, then comes to Android.
- This repo should be updated when the user decides the public Android snapshot should move forward.
- Never treat this repo like a staging branch.
- If unsure whether something is public-safe, stop and confirm before publishing.
