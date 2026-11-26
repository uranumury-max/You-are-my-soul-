# Architecture and honest connection model

## Stable device identity
Each installation creates and locally retains a random nine-digit app identity. It is not a phone number and is not regenerated on each launch. A server-side uniqueness check is required before treating it as globally unique; the local generator alone cannot guarantee collision-free identities across every device.

## Authority and availability
No participant phone is the always-on server or sole source of truth. The production design uses a managed cloud backend as the coordination and durable synchronization authority, while every phone keeps its own encrypted local copy. This avoids making other participants' availability depend on the host phone remaining powered, online, and exempt from Android background process limits.

## Connectivity limitation
A phone can serve as a host while its process, power, and network are available. Android background restrictions, changing IP addresses, NAT, and offline/powered-off states prevent a phone-only server from guaranteeing worldwide, always-on access. A reliable remote reconnect needs an explicitly selected network transport and, where direct connections are blocked, a relay or hosted rendezvous. No UI should claim that a remote invitation or live sync succeeded until the transport acknowledges it.

## Invitations
The intended flow is: copy local app code -> enter peer code -> send join request -> host approves -> join world. This must be implemented over a real transport, not simulated by a local button state.

## Product constraints
- Up to five participants.
- Arabic RTL interface.
- Android-native Kotlin/Compose; no WebView.
- Preserve local data and migrations as the app evolves.
- Developer credit at the end of each section/tab.


## Local data protection
- The world snapshot is encrypted with AES-256-GCM before it is written to app preferences. New writes use a versioned v2 envelope with authenticated associated data and a 2 MiB plaintext size ceiling; the reader remains backward-compatible with v1 ciphertext.
- The encryption key is generated and retained by Android Keystore; it is not stored beside the encrypted snapshot.
- Legacy plaintext and v1 encrypted snapshots are migrated only after successful decoding and a durable v2 write. A failed decode or migration write now propagates an error and locks editing instead of pretending the world is empty.
- Existing plaintext snapshots are migrated on first successful read. The old value is removed in the same durable commit that writes the encrypted v2 snapshot.
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
- The current Connection screen only validates form data locally. It does not send a request, discover another phone, approve a participant, or synchronize a world.
- Before remote pairing is released, implement and test TLS certificate identity/pinning, host authorization, persistent rate limiting, replay protection across reconnects, and LAN/NAT traversal behavior.


## Storage and UI thread discipline
- The initial encrypted snapshot read runs on Dispatchers.IO; the interface shows a lightweight loading state while it completes.
- Snapshot encryption and durable SharedPreferences commits run on Dispatchers.IO and are serialized with a mutex so rapid edits cannot overwrite one another out of order.
- If an encrypted snapshot cannot be decrypted or saved, editing is locked and world creation is disabled. Do not silently interpret unreadable encrypted data as an empty world.
- Only the active Compose destination is built; outgoing content is retained for the short transition animation. Large media must be deferred to its own screen.


## Selected production architecture (decision)
The selected target is a hybrid cloud-first, offline-capable design:

- **Backend:** Supabase (managed PostgreSQL, Auth, Realtime, private Storage, and server-side RPC/Edge Functions). A first SQL migration is now checked into `supabase/migrations/202611270001_secure_world_sync.sql`; it is not deployed to a live project yet.
- **Authentication privacy:** default to Supabase anonymous authentication so the app does not require an email address or phone number just to create a private world. Anonymous identities are still authenticated users for RLS. Their session cannot be recovered after app data is cleared or the app is reinstalled, so a tested recovery/export or optional account-linking flow is a release blocker. Enable CAPTCHA/abuse protection and server-side throttling before public access.
- **Local-first UX:** Android Room is now added with durable outbox, downloaded-event, and cursor tables. The outbox repository is implemented but is not yet wired into UI mutations; the existing world snapshot remains in encrypted SharedPreferences until a tested Room migration is ready. Network state must never block reading or editing already available content.
- **Synchronization:** use immutable, uniquely identified events rather than repeatedly overwriting one shared world JSON blob. The migration defines an append-only event table with a server sequence and a unique (world, event) key. Client writes go through an authenticated idempotent RPC that derives actor identity from the session and rejects event-ID payload collisions. A transport-neutral Kotlin contract and JVM tests now validate ciphertext envelopes, page size, monotonic server cursors, and retry limits. The Room outbox and network client are still to be implemented.
- **Media:** encrypted media files are uploaded to private object storage. Store metadata and encrypted object references in the event stream. Never expose public buckets for private memories or photos.
- **Realtime:** Realtime is a notification that new events may exist, not the sole delivery mechanism. On reconnect or missed notifications, clients fetch events from the last acknowledged cursor.
- **Pairing:** the nine-digit device code is only a human-friendly discovery hint. The migration now defines device-code registration, account-level lookup throttling, join requests, explicit owner approval/decline, a five-member cap, expiring one-time invitations, and owner revocation. The code is never an authenticator. Additional IP/device-level abuse protection must be added at the Edge/API boundary before public launch.
- **Transport:** use HTTPS/WSS with platform certificate validation. Do not add brittle certificate pins without a tested pin-rotation and backup-pin process. Never ship a Supabase service-role/secret key in the APK; only publishable client configuration may be embedded, and every exposed table/function/storage object must enforce least-privilege authorization. The migration defines restrictive RLS policies and security-definer RPCs with fixed search paths.
- **End-to-end privacy:** an AES-GCM content-envelope primitive now binds ciphertext to the world ID, event ID, event type, and key version. A local world-key vault wraps per-world 256-bit keys with a non-exportable Android Keystore key. These primitives are not yet connected to app content or cloud upload, and no member-to-member key distribution exists. Before upload, private content must be encrypted on-device; distribute keys only through an authenticated public-key envelope exchange after owner approval, and rotate the key for future content after member revocation. Do not claim old content is inaccessible to a removed member if that member already downloaded it.
- **Recovery:** provide an explicit encrypted recovery/export path. A device-bound Android Keystore key alone cannot restore data on a replacement phone. Recovery must be designed before promising account/device recovery; do not silently upload plaintext keys.
- **Scale:** cap a world at five active members. Apply server-side rate limits, payload-size limits, schema validation, audit events for membership changes, and per-world authorization on every operation.

### Important implementation boundary
This is the chosen architecture, not a claim that cloud synchronization already works. The repository has local encrypted snapshot storage, pairing-security primitives, a checked-in Supabase SQL migration, and a transport-neutral sync contract with JVM tests. There is no provisioned Supabase project, no production credentials, no UI-integrated Room outbox, no deployed cloud schema, no integrated end-to-end encryption/key exchange, and no live network pairing/synchronization. Do not show a successful connection state until a real server acknowledgement is received.

### Delivery sequence
1. Migrate the existing encrypted snapshot into Room with a tested, recoverable migration.
2. Wire every local edit to a transactionally persisted outbox event and deterministic conflict rules.
3. Apply the checked-in Supabase migration to staging and pass the owner/member/revocation authorization matrix.
4. Add authenticated Android networking and server-authorized pairing.
5. Add end-to-end encrypted event/media synchronization, reconnect recovery, and revocation/key rotation.
6. Add integration tests, abuse/rate-limit tests, backup/restore tests, and real multi-device tests before release.


## Current cloud schema implementation
The checked-in migration currently defines:
- Worlds and active/revoked memberships, with owner membership created atomically.
- Private world invitation hashes with expiry and single-use acceptance.
- Device-code registration and account-level throttling for code lookups.
- Join requests resolved from a nine-digit device discovery code, with explicit owner approve/decline RPCs.
- Owner-only member revocation and a five-active-member cap protected by per-world row locks during admission.
- An append-only event stream with per-world idempotency and server sequence.
- A private encrypted-media bucket with member-scoped object policies.
- Realtime publication of the event table as a notification channel; cursor-based fetch remains authoritative.

Security boundaries and remaining work:
- The SQL migration is source code only until applied to a newly provisioned Supabase project and reviewed against its actual Auth/Storage settings.
- Rate limiting in SQL is per authenticated account. Add IP/device-level throttling and monitoring at the API/Edge layer to resist account farming and enumeration.
- The app must generate high-entropy invite tokens and send only SHA-256 hashes to the database; the raw token must never be logged.
- The event ciphertext envelope and local world-key vault primitives are implemented and unit-tested, but are not wired into the content model or uploads. Member key distribution, recovery, key rotation, and forward secrecy are not implemented yet.
- Revoking a member blocks future server access, but cannot erase content that member already downloaded. Rotate the world key for future content after revocation.
- Never expose the Supabase service-role key to Android. Verify every RLS policy and RPC with separate owner, member, revoked-member, and unrelated-account tests before production.


Authentication reference: Supabase anonymous sign-ins avoid collecting email/phone by default, but the identity cannot be recovered after sign-out or clearing app data. Enable abuse protection before production. See the official [anonymous sign-in documentation](https://supabase.com/docs/guides/auth/auth-anonymous).
