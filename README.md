# عالمنا الخاص — You Are My Soul

A native Android app built with Kotlin and Jetpack Compose. The product goal is a private, animated shared world for 2–5 participants, with an Arabic RTL interface and a stable nine-digit installation code used only for discovery—not authentication.

## Chosen architecture

The target design is cloud-first and offline-capable:
- A managed Supabase backend coordinates authenticated membership and synchronization.
- Each Android installation keeps local data so the app remains useful without a connection.
- A durable Room database now contains sync outbox, downloaded-event, and cursor tables.
- A checked-in SQL migration defines world membership, owner-approved join requests, rate-limited device-code discovery, append-only events, private media storage, and restrictive RLS.
- Local snapshots use AES-256-GCM with Android Keystore protection. A separate AES-GCM content envelope and per-world key vault have been added as cryptographic foundations, but key distribution is not implemented.

## Current implementation checkpoint

- Native Android/Compose scaffold with Arabic RTL UI, five destinations, animated transitions, starfield, and developer credit.
- Local world snapshot persistence with versioned authenticated encryption and fail-closed migration.
- Stable local nine-digit device identity.
- Pairing challenge/HMAC primitives and JVM tests.
- Transport-neutral encrypted sync-event model and cursor validation tests.
- Room sync outbox, downloaded-event cache, retry metadata, and monotonic cursor storage.
- AES-GCM world-content envelope bound to world/event/type/key-version metadata, with tamper and nonce tests.
- Android Keystore-wrapped per-world key vault.
- Supabase SQL migration and backend security/deployment checklist.
- GitHub Actions workflow runs unit tests before building the debug APK.

## Not yet complete — do not treat as a working connected app

- No Supabase project has been provisioned and the SQL migration has not been applied to a live/staging backend.
- No Supabase Auth/network client is wired into Android.
- The encrypted world snapshot now migrates safely from SharedPreferences into Room. The durable outbox exists, but local UI edits are not yet atomically connected to outbox events.
- Content encryption and the local key vault are not yet wired into the app's messages/memories/media.
- No authenticated member-to-member key distribution, recovery, or key rotation.
- No real join-request delivery, owner approval UI, or cross-device event synchronization.
- No media upload/download, shared album, shared drawing, or capsule synchronization.
- No final physical-device, offline/reconnect, abuse, and authorization test pass.

The nine-digit code is not a password. A device must authenticate, receive a fresh invitation/request, and be approved by the world owner. A visible success state must wait for a real server acknowledgement.

## Build

Open the repository in Android Studio and allow Gradle to sync, or use the GitHub Actions workflow under Actions → Android APK. This repository currently does not include a Gradle wrapper script, so ./gradlew is not available yet.
