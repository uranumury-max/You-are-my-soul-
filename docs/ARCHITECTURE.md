# Architecture and honest connection model

## Stable device identity
Each installation creates and locally retains a random nine-digit app identity. It is not a phone number and is not regenerated on each launch. A server-side uniqueness check is required before treating it as globally unique; the local generator alone cannot guarantee collision-free identities across every device.

## Authority and availability
No participant phone is the always-on server or sole source of truth. The production design uses a managed cloud backend as the coordination and durable synchronization authority, while every phone keeps its own encrypted local copy. This avoids making other participants' availability depend on the host phone remaining powered, online, and exempt from Android background process limits.

## Connectivity limitation
A phone can serve as a host while its process, power, and network are available. Android background restrictions, changing IP addresses, NAT, and offline/powered-off states prevent a phone-only server from guaranteeing worldwide, always-on access. A reliable remote reconnect needs an explicitly selected network transport and, where direct connections are blocked, a relay or hosted rendezvous. No UI should claim that a remote invitation or live sync succeeded until the transport acknowledges it.

## Invitations
The implemented source flow is: copy local app code -> enter peer code -> send an authenticated join request -> owner approves or declines -> approved device receives its per-device world-key envelope and encrypted initial snapshot. It remains unverified against a deployed backend.

## Product constraints
- Up to five participants.
- Arabic RTL interface.
- Android-native Kotlin/Compose; no WebView.
- Preserve local data and migrations as the app evolves.
- Developer credit at the end of each section/tab.


## Local data protection
- The world snapshot is encrypted with AES-256-GCM before it is written to the Room `local_world_snapshot` table. New writes use a versioned v2 envelope with authenticated associated data and a 2 MiB plaintext size ceiling; the reader remains backward-compatible with v1 ciphertext.
- The encryption key is generated and retained by Android Keystore; it is not stored beside the encrypted snapshot.
- Legacy plaintext and v1/v2 encrypted SharedPreferences snapshots are migrated into Room only after successful decoding and a durable encrypted Room write. A failed decode or migration write propagates an error and locks editing instead of pretending the world is empty.
- Legacy SharedPreferences values are removed only after the encrypted Room snapshot has been saved successfully; this migration is recoverable if the Room write fails.
- Android automatic backup is disabled for this app so the encrypted snapshot is not restored without its device-bound Keystore key.
- Cleartext HTTP traffic is disabled in the application manifest.
- This protects stored app data against casual extraction of preference files. It does not protect a compromised or already-unlocked device, and it does not replace authentication, authorization, or transport encryption for future device-to-device communication.

## Performance principles
- Keep the lightweight world summary available locally; do not add a network fetch to the initial screen.
- Compose only the selected tab's content, retaining outgoing content only for the short transition animation.
- Use lazy containers for potentially long lists and defer media decoding/loading until the relevant media screen is opened.
- Avoid synchronous disk writes on the main thread.
- Pause nonessential animations and background work when the app is not visible.
- Measure cold start, screen transitions, scrolling, memory use, and battery impact on representative Android devices before release.

## Security requirements before enabling networking
- A nine-digit device code is a discovery identifier, never a password or proof of identity.
- Pairing must require a fresh, expiring challenge and explicit host approval.
- Authenticate each session and authorize every operation by role and world membership.
- Encrypt network traffic; reject cleartext and invalid certificates.
- Validate message size, rate-limit requests, and reject malformed or replayed messages.
- Never expose arbitrary file access or a general-purpose shell/API to other participants.
- Keep secrets out of logs and error messages; do not trust a peer merely because it is on the same Wi-Fi network.


## Pairing protocol foundation
- Validate the nine-digit identifiers and the five-member capacity before accepting a join request.
- A device identifier is public discovery data, not an authenticator.
- The protocol primitives use a random 256-bit pairing secret, a fresh 256-bit nonce, a two-minute challenge lifetime, HMAC-SHA256 proofs, constant-time signature comparison, and single-use challenge consumption.
- Invalid proofs consume their challenge to prevent repeated guesses against one nonce. Basic per-device attempt throttling is included.
- These primitives are not a network transport. They must only be used after a certificate-authenticated encrypted channel is established; never expose the pairing secret over raw TCP, local broadcast, or cleartext HTTP.
- The Connection screen now sends authenticated device-code join requests and lets the local world owner approve or decline them through protected RPCs. This has not been verified against a live project. Approval creates membership only; it does not yet distribute world encryption keys or synchronize content.
- Before remote pairing is released, implement and test TLS certificate identity/pinning, host authorization, persistent rate limiting, replay protection across reconnects, and LAN/NAT traversal behavior.


## Storage and UI thread discipline
- The initial encrypted snapshot read runs on Dispatchers.IO; the interface shows a lightweight loading state while it completes.
- Snapshot encryption and durable Room commits run on Dispatchers.IO and are serialized with a mutex so rapid edits cannot overwrite one another out of order.
- If an encrypted snapshot cannot be decrypted or saved, editing is locked and world creation is disabled. Do not silently interpret unreadable encrypted data as an empty world.
- Only the active Compose destination is built; outgoing content is retained for the short transition animation. Large media must be deferred to its own screen.


## Selected production architecture (decision)
The selected target is a hybrid cloud-first, offline-capable design:

- **Backend:** Supabase (managed PostgreSQL, Auth, Realtime, private Storage, and server-side RPC/Edge Functions). A first SQL migration is now checked into `supabase/migrations/202611270001_secure_world_sync.sql`; it is not deployed to a live project yet.
- **Authentication privacy:** Android now has a minimal Supabase Auth REST client that creates an anonymous identity on first configured launch, refreshes expiring sessions, and stores access/refresh credentials encrypted with a dedicated Android Keystore AES-GCM key and AAD. A failed refresh or unreadable stored session does not silently create a replacement identity. Anonymous identities are still authenticated users for RLS. Their session cannot be recovered after app data is cleared or the app is reinstalled, so a tested recovery/export or optional account-linking flow is a release blocker. Enable CAPTCHA/abuse protection and server-side throttling before public access. The Auth and device-registration clients are implemented but require live-project verification.
- **Local-first UX:** Android Room is now added with durable outbox, downloaded-event, and cursor tables. The encrypted world snapshot now lives in Room. Existing v1/v2 encrypted and legacy plaintext SharedPreferences checkpoints migrate only after successful decode and durable Room persistence; old preferences are then removed best-effort. The outbox is integrated with UI mutations, and a separate encrypted baseline enables offline edit recovery after process death. Network state must never block reading or editing already available content.
- **Synchronization:** use immutable, uniquely identified events rather than repeatedly overwriting one shared world JSON blob. The migration defines an append-only event table with a server sequence and a unique (world, event) key. Client writes go through an authenticated idempotent RPC that derives actor identity from the session and rejects event-ID payload collisions. A transport-neutral Kotlin contract and JVM tests now validate ciphertext envelopes, page size, monotonic server cursors, and retry limits. The Android client has a durable Room outbox, bounded upload/download passes, retry metadata, encrypted event construction, and ordered replay from the latest checkpoint. Live backend verification remains pending.
- **Media:** the private `world-media` bucket is configured by migration 001 with active-member object policies. The Android source now includes AES-GCM binary media encryption (world/media-bound authenticated data, versioned envelope, 20 MiB plaintext cap) and authenticated private Storage upload/download. The encrypted metadata model, snapshot persistence, mutation event construction, and validated event replay are implemented. The Compose album now supports document selection, bounded 20 MiB reads, encrypted upload before metadata persistence, authenticated download, integrity-checked decryption, and on-demand downsampled in-memory previews. Plaintext media is not written to a shared/public cache. Authenticated member-scoped object deletion and encrypted media_delete replay are implemented. Live-backend verification and two-device integration tests remain pending.
- **Realtime:** Realtime is a notification that new events may exist, not the sole delivery mechanism. On reconnect or missed notifications, clients fetch events from the last acknowledged cursor.
- **Pairing:** the nine-digit device code is only a human-friendly discovery hint. Android now creates a random per-installation UUID and automatically registers that UUID plus the nine-digit code through the authenticated `register_device_code` RPC; the server derives the account ID from `auth.uid()`. The RPC detects both pre-existing and concurrent code collisions, and the client rotates the public code and retries a bounded number of times without changing the installation UUID. The migration also defines account-level lookup throttling, join requests, explicit owner approval/decline, a five-member cap, expiring one-time invitations, and owner revocation. The code is never an authenticator. The join-request and owner-approval UI flows are implemented in source; live backend verification remains pending. Additional IP/device-level abuse protection must be added at the Edge/API boundary before public launch.
- **Transport:** use HTTPS/WSS with platform certificate validation. Do not add brittle certificate pins without a tested pin-rotation and backup-pin process. Never ship a Supabase service-role/secret key in the APK; only publishable client configuration may be embedded, and every exposed table/function/storage object must enforce least-privilege authorization. The migration defines restrictive RLS policies and security-definer RPCs with fixed search paths.
- **End-to-end privacy:** an AES-GCM content-envelope primitive now binds ciphertext to the world ID, event ID, event type, and key version. A local world-key vault wraps per-world 256-bit keys with a non-exportable Android Keystore key. These primitives are not yet connected to app content or cloud upload, and no member-to-member key distribution exists. Before upload, private content must be encrypted on-device; distribute keys only through an authenticated public-key envelope exchange after owner approval, and rotate the key for future content after member revocation. Do not claim old content is inaccessible to a removed member if that member already downloaded it.
- **Recovery:** provide an explicit encrypted recovery/export path. A device-bound Android Keystore key alone cannot restore data on a replacement phone. Recovery must be designed before promising account/device recovery; do not silently upload plaintext keys.
- **Scale:** cap a world at five active members. Apply server-side rate limits, payload-size limits, schema validation, audit events for membership changes, and per-world authorization on every operation.

### Important implementation boundary
This is the chosen architecture, not a claim that cloud synchronization already works. The repository contains encrypted local snapshot storage, a UI-integrated Room outbox, device-specific key exchange, authenticated pairing flows, and event replay. There is no provisioned Supabase project, no deployed cloud schema, and no live network pairing or two-device synchronization validation. Do not show a successful connection state until a real server acknowledgement is received.

### Delivery sequence
1. Move the encrypted snapshot into Room with a recoverable migration (implementation is present; validation remains).
2. Verify atomic Room snapshot/baseline/outbox commits and deterministic replay with migration and process-death tests.
3. Apply the checked-in Supabase migrations to staging and pass the owner/member/revocation authorization matrix.
4. Complete authenticated Android networking and server-authorized pairing (anonymous Auth session restore, device registration, join requests, owner inbox, and approve/decline UI are implemented; live deployment and end-to-end verification remain).
5. Add end-to-end encrypted event/media synchronization, reconnect recovery, and revocation/key rotation.
6. Add integration tests, abuse/rate-limit tests, backup/restore tests, and real multi-device tests before release.


## Current cloud schema implementation
The checked-in migration currently defines:
- Worlds and active/revoked memberships, with owner membership created atomically.
- Private world invitation hashes with expiry and single-use acceptance.
- Device-code registration and account-level throttling for code lookups.
- Join requests resolved from a nine-digit device discovery code, with validated participant display names, per-account and per-world request throttles, and explicit owner approve/decline RPCs (migration 003).
- Owner-only member revocation and a five-active-member cap protected by per-world row locks during admission.
- An append-only event stream with per-world idempotency and server sequence.
- A private encrypted-media bucket with member-scoped object policies.
- Realtime publication of the event table as a notification channel; cursor-based fetch remains authoritative.

Security boundaries and remaining work:
- The SQL migrations 001–005 are source code only until applied to a newly provisioned Supabase project and reviewed against its actual Auth/Storage settings.
- Pairing lookups are rate-limited per authenticated account and per target world (20 requests per ten-minute world window). Add IP/device-level throttling and monitoring at the API/Edge layer to resist account farming and enumeration.
- The app must generate high-entropy invite tokens and send only SHA-256 hashes to the database; the raw token must never be logged.
- The event ciphertext envelope and local world-key vault are integrated with content changes and encrypted event uploads. Migration 004 adds authenticated per-installation RSA public-key registration; Android Keystore keeps the corresponding private key non-exportable, and the client has an RSA-OAEP-SHA-256 envelope primitive. Migration 005 adds owner-only discovery of active member device public keys, owner-only publishing of per-device RSA-OAEP envelopes, and recipient-only envelope reads. The Android owner approval path distributes the existing world key to registered active devices; the approved requester can retrieve and locally re-wrap its own envelope. An encrypted initial world snapshot is now appended after owner approval and restored by the approved device after it imports its own envelope. Ongoing granular edit synchronization is present in source; encrypted offline recovery is implemented through a durable baseline, while account/device recovery, key rotation, and forward secrecy remain incomplete.
- Revoking a member blocks future server access, but cannot erase content that member already downloaded. Rotate the world key for future content after revocation.
- Never expose the Supabase service-role key to Android. Verify every RLS policy and RPC with separate owner, member, revoked-member, and unrelated-account tests before production.


Authentication reference: Supabase anonymous sign-ins avoid collecting email/phone by default, but the identity cannot be recovered after sign-out or clearing app data. Enable abuse protection before production. See the official [anonymous sign-in documentation](https://supabase.com/docs/guides/auth/auth-anonymous).


## Server-side event write boundary (migration 002)

The follow-up migration `202611270002_append_world_event_rpc.sql` removes direct client INSERT permission on `world_events`. Authenticated clients must call `append_world_event`, which derives the actor from `auth.uid()`, checks active world membership, validates event type, ciphertext byte size and client timestamp, enforces a per-account event-write quota, and makes committed event IDs idempotent. Reusing an event ID with different content is rejected. The migration is source-controlled only and has not been deployed to a Supabase project. Concurrent submissions of the same event ID are serialized by a transaction-scoped advisory lock before idempotency and quota checks, so duplicate retries do not consume another event slot.


## Current Android cloud bootstrap
- Build-time configuration accepts only an HTTPS Supabase project root and a publishable key (`sb_publishable_`) or legacy anon JWT; service-role/secret keys are rejected.
- GitHub Actions receives `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` from repository secrets. These are intentionally not committed to source control.
- On configured startup, the app restores or creates an anonymous Supabase Auth session, persists it with a dedicated Keystore-backed cipher, then registers the installation through the protected RPC. Connectivity restoration triggers another bounded authentication/registration attempt. Each local world now carries a stable cloud UUID; the app idempotently creates and verifies its owner row in `public.worlds`, whose database trigger creates the owner membership. The app does not claim this succeeded when the project is not configured or the server rejects the request.
- Live Auth, RLS, RPC, migration, event-envelope validation, refresh-rotation, owner/member/revoked-member authorization, and device-reinstall/recovery behavior remain unverified until a Supabase project is provisioned and end-to-end tests run. Join approval does not yet establish member encryption keys or deliver the existing world snapshot.

## Encrypted shared-world event flow
- The owner publishes a complete encrypted WORLD_UPDATE checkpoint when approving a new member. The checkpoint is encrypted with the per-world AES-256 key and bound to the world ID, event ID, event type, and key version as AES-GCM associated data.
- The owner wraps the world key separately for each active member device using that device's registered RSA public key. The private device key remains non-exportable in Android Keystore.
- Local edits to memories, messages, drawing strokes, future capsules, garden count, daily answer, world name, and relationship start date are converted to typed event payloads. Each payload is encrypted before entering the Room outbox; plaintext is not sent to Supabase.
- A bounded sync pass uploads due outbox events, downloads pages after the persisted server cursor, and stores remote ciphertext in Room. A periodic pass in the foreground app requests synchronization every 20 seconds and retries after connectivity returns.
- State reconstruction selects the latest encrypted checkpoint and replays later events in server-sequence order. This gives deterministic ordering and avoids replacing the whole world snapshot for each field edit. Edits to the same scalar field use server order; this is not a semantic merge for simultaneous edits.
- A separate encrypted baseline records the last state whose events were durably queued. Offline edits do not advance it, so a later process can recover missing changes after restart. When connected, the local snapshot, new baseline, and bounded outbox batch commit in one Room transaction. Remote replay commits the rebuilt snapshot and baseline atomically after the complete event tail has been validated and applied.
- This implementation is source-complete for the listed data types but is not production-verified until the SQL migrations are deployed, CI is green on the latest commit, and two-device integration tests pass.

- Future capsules store a fixed unlock timestamp in the encrypted world snapshot. The creation flow does not expose an edit operation for that timestamp. Opening requests fetch the database clock through authenticated RPC `get_server_time`; the client keeps the capsule closed if the server clock cannot be reached or says the unlock time has not arrived. This is an application-level gate, not a claim that a modified client can be made tamper-proof.

- The shared drawing board stores normalized vector points rather than device-pixel coordinates. Strokes are bounded by point count and history length, encrypted as DRAWING_UPDATE events, and replayed in server-sequence order. A clear event resets the board before any later strokes are replayed.
