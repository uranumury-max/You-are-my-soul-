# عالمنا الخاص — You Are My Soul

A native Android app built with Kotlin and Jetpack Compose. The product goal is a private, animated shared world for 2–5 participants, with an Arabic RTL interface and a stable nine-digit installation code used only for discovery—not authentication.

## Chosen architecture

The target design is cloud-first and offline-capable:
- A managed Supabase backend coordinates authenticated membership and synchronization.
- Each Android installation keeps local data so the app remains useful without a connection.
- A durable Room database now contains sync outbox, downloaded-event, and cursor tables.
- Five checked-in SQL migrations define world membership, owner-approved join requests, per-account/per-world rate-limited device-code discovery, append-only events, private media storage, device public-key registration, owner-controlled recipient key envelopes, and restrictive RLS.
- Local snapshots use AES-256-GCM with Android Keystore protection. World content uses authenticated AES-GCM envelopes; installation-specific RSA-OAEP keys support owner-to-device world-key delivery. An approved member receives an encrypted initial snapshot, and field-level memory, garden, daily-answer, and settings changes are queued, encrypted, uploaded, downloaded, and deterministically replayed from the latest checkpoint in source.

## Current implementation checkpoint

- Native Android/Compose scaffold with Arabic RTL UI, five destinations, animated transitions, starfield, and developer credit.
- Local world snapshot persistence with versioned authenticated encryption and fail-closed migration.
- Stable local nine-digit device code plus a random per-installation UUID; code collisions are detected by the server and retried without changing the installation identity.
- Pairing challenge/HMAC primitives and JVM tests.
- Transport-neutral encrypted sync-event model and cursor validation tests.
- Room sync outbox, downloaded-event cache, retry metadata, and monotonic cursor storage.
- AES-GCM world-content envelope bound to world/event/type/key-version metadata, with tamper and nonce tests.
- Android Keystore-wrapped per-world key vault; installation-specific RSA-OAEP public keys and owner-controlled encrypted key envelopes support delivery to approved member devices.
- Supabase SQL migrations 001–005 and backend security/deployment checklist.
- Android anonymous Supabase Auth session creation/refresh with encrypted Keystore-backed session persistence.
- Authenticated device registration, idempotent cloud-world provisioning, and local world-name metadata updates.
- Authenticated join-request submission, owner inbox, approve/decline actions, and requester status refresh.
- Authenticated append/fetch transport for versioned AES-GCM event envelopes, a bounded Room outbox/download coordinator, and automatic periodic synchronization with deterministic event replay.
- UI changes to memories, garden flowers, daily answers, world name, and relationship start date are converted into separate encrypted events; they are no longer uploaded as whole-snapshot replacements.
- GitHub Actions workflow runs unit tests before building the debug APK.

## Not yet complete — do not treat as a working connected app

- No Supabase project has been provisioned and migrations 001–005 have not been applied to a live/staging backend. Auth, device registration, cloud-world creation, and pairing clients are present in source but have not been verified against a live backend.
- Android build configuration now accepts `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` from Gradle properties or environment variables; the GitHub Actions build reads the matching repository secrets. Missing values leave cloud connectivity unconfigured rather than fabricating a successful connection. Secret/service-role keys are rejected by the runtime config validator.
- The encrypted world snapshot migrates safely from SharedPreferences into Room. An approved joining device receives an encrypted checkpoint, then applies ordered encrypted event updates. Local edits are persisted first and queued as encrypted field-level events; outbox enqueue and local snapshot persistence are sequential operations, not yet one shared Room transaction.
- Content encryption and the local key vault are not yet wired into the app's messages/memories/media.
- Device public keys are registered per installation and the owner can distribute per-device encrypted world-key envelopes after approval. Key recovery and rotation remain incomplete.
- Join-request delivery and owner approval UI are implemented but remain unusable until the backend migrations are deployed and tested. Cross-device synchronization is implemented in source but has not yet been verified against a live Supabase project or two physical devices.
- No media upload/download, shared album, shared drawing, or capsule synchronization.
- No final physical-device, offline/reconnect, abuse, and authorization test pass.

The nine-digit code is not a password. A device must authenticate, receive a fresh invitation/request, and be approved by the world owner. A visible success state must wait for a real server acknowledgement.

## Build

Open the repository in Android Studio and allow Gradle to sync, or use the GitHub Actions workflow under Actions → Android APK. This repository currently does not include a Gradle wrapper script, so ./gradlew is not available yet.


- Server event writes are designed to go through a membership-checked, idempotent, rate-limited Supabase RPC; its SQL migration is not yet deployed.
