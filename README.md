# عالمنا الخاص — You Are My Soul

A native Android app built with Kotlin and Jetpack Compose. The product goal is a private, animated shared world for 2–5 participants, with an Arabic RTL interface and a stable nine-digit installation code used only for discovery—not authentication.

## Chosen architecture

The target design is cloud-first and offline-capable:
- A managed Supabase backend coordinates authenticated membership and synchronization.
- Each Android installation keeps local data so the app remains useful without a connection.
- A durable Room database now contains sync outbox, downloaded-event, and cursor tables.
- Three checked-in SQL migrations define world membership, owner-approved join requests, per-account/per-world rate-limited device-code discovery, append-only events, private media storage, and restrictive RLS.
- Local snapshots use AES-256-GCM with Android Keystore protection. A separate AES-GCM content envelope and per-world key vault have been added as cryptographic foundations, but key distribution is not implemented.

## Current implementation checkpoint

- Native Android/Compose scaffold with Arabic RTL UI, five destinations, animated transitions, starfield, and developer credit.
- Local world snapshot persistence with versioned authenticated encryption and fail-closed migration.
- Stable local nine-digit device code plus a random per-installation UUID; code collisions are detected by the server and retried without changing the installation identity.
- Pairing challenge/HMAC primitives and JVM tests.
- Transport-neutral encrypted sync-event model and cursor validation tests.
- Room sync outbox, downloaded-event cache, retry metadata, and monotonic cursor storage.
- AES-GCM world-content envelope bound to world/event/type/key-version metadata, with tamper and nonce tests.
- Android Keystore-wrapped per-world key vault.
- Supabase SQL migrations 001–003 and backend security/deployment checklist.
- Android anonymous Supabase Auth session creation/refresh with encrypted Keystore-backed session persistence.
- Authenticated device registration, idempotent cloud-world provisioning, and local world-name metadata updates.
- Authenticated join-request submission, owner inbox, approve/decline actions, and requester status refresh.
- Authenticated append/fetch transport for versioned AES-GCM event envelopes, plus a bounded Room outbox/download coordinator.
- GitHub Actions workflow runs unit tests before building the debug APK.

## Not yet complete — do not treat as a working connected app

- No Supabase project has been provisioned and migrations 001–003 have not been applied to a live/staging backend. Auth, device registration, cloud-world creation, and pairing clients are present in source but have not been verified against a live backend.
- Android build configuration now accepts `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` from Gradle properties or environment variables; the GitHub Actions build reads the matching repository secrets. Missing values leave cloud connectivity unconfigured rather than fabricating a successful connection. Secret/service-role keys are rejected by the runtime config validator.
- The encrypted world snapshot now migrates safely from SharedPreferences into Room. The durable outbox exists, but local UI edits are not yet atomically connected to outbox events.
- Content encryption and the local key vault are not yet wired into the app's messages/memories/media.
- No authenticated member-to-member key distribution, recovery, or key rotation; an approved join request grants database membership but does not yet deliver the world encryption key.
- Join-request delivery and owner approval UI are implemented but remain unusable until the backend migrations are deployed and tested. Cross-device event synchronization is not yet implemented.
- No media upload/download, shared album, shared drawing, or capsule synchronization.
- No final physical-device, offline/reconnect, abuse, and authorization test pass.

The nine-digit code is not a password. A device must authenticate, receive a fresh invitation/request, and be approved by the world owner. A visible success state must wait for a real server acknowledgement.

## Build

Open the repository in Android Studio and allow Gradle to sync, or use the GitHub Actions workflow under Actions → Android APK. This repository currently does not include a Gradle wrapper script, so ./gradlew is not available yet.


- Server event writes are designed to go through a membership-checked, idempotent, rate-limited Supabase RPC; its SQL migration is not yet deployed.
