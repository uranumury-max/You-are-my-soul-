# Cloud backend setup and security checklist

This folder contains the first database migration for the hybrid cloud/offline architecture.

## Current state

- The SQL migration is committed to the repository.
- It has **not** been applied to a live Supabase project.
- No project URL, publishable key, service-role key, or user credential is stored here.
- The Android app does not yet call these RPCs or synchronize events.
- Do not mark the app as connected until authenticated server requests and acknowledgements work.

## Provisioning policy

Create a dedicated Supabase project for this app only when the Android client integration and staging test plan are ready. Use a development/staging project first. Do not paste secrets into source code, issues, screenshots, or chat.

The Android binary may contain the Supabase project URL and publishable/anon client key. It must never contain the service-role key, database password, JWT signing secret, or any other privileged secret. RLS is mandatory even when a publishable key is used.

## Apply locally first

1. Install the Supabase CLI and Docker.
2. Run supabase init if the project has not been initialized.
3. Start the local stack with supabase start.
4. Link the migration directory to the local Supabase project configuration.
5. Apply migrations locally with supabase db reset.
6. Inspect the generated schema and run the authorization test matrix below before any hosted deployment.

The migration uses Supabase-managed auth.users, storage.buckets, storage.objects, and the optional supabase_realtime publication. Verify those platform objects exist in the local stack version being used.

## Pairing flow

1. A signed-in installation registers its stable installation UUID and nine-digit display code using register_device_code.
2. The joining installation calls request_join_by_device_code.
3. The owner receives a pending request and explicitly approves or declines it using decide_join_request.
4. Approval adds the authenticated requester as a member, subject to the five-active-member limit.
5. The server returns only authorized world data to active members. A revoked member cannot fetch future events.
6. The nine-digit code is a discovery hint, never an authentication factor. The app must not display a success state when the RPC returns null or fails.

The SQL currently limits code lookups to five per authenticated account per ten-minute window. Before public release, add Edge/API-level IP and device throttling, generic failure responses, monitoring, and abuse alerts to make account farming and enumeration harder.

## Event and media rules

- Encrypt private content on the Android device before upload.
- Store only authenticated ciphertext in world_events.ciphertext.
- Use a fresh AEAD nonce for every encryption operation; never reuse a nonce with the same key.
- Keep event IDs stable across retries so a repeated upload is idempotent.
- Fetch pages using server_seq as a cursor. Realtime notifications are only hints that a pull is needed.
- Upload encrypted media as application/octet-stream under a path beginning with the world UUID.
- Never store plaintext messages, memories, photo captions, or capsule contents in event metadata, object names, analytics, or logs.

The database migration does not implement client-side encryption, key exchange, key recovery, or key rotation. Those are release blockers, not optional enhancements.

## Required authorization tests

Run these tests against a disposable local database with at least four separate authenticated test users:

| Scenario | Expected result |
|---|---|
| Unauthenticated read/write | Denied |
| Unrelated account reads a world | No rows |
| Active member reads world and events | Allowed |
| Active member inserts an event as their own user | Allowed |
| Member inserts an event with another actor ID | Denied |
| Member updates or deletes an event | Denied |
| Member reads another world's private media | Denied |
| Non-owner creates an invite | Denied |
| Owner creates a valid short-lived invite | Allowed |
| Expired, revoked, or reused invite is accepted | Denied |
| Join request by a valid device code | Pending only; no membership yet |
| Owner declines a join request | No membership added |
| Owner approves a join request | Membership added |
| Sixth active member is admitted | Denied |
| Revoked member reads future events/media | Denied |
| Five concurrent join approvals race for remaining slots | Active member count never exceeds five |
| Replayed event ID is uploaded twice | One event row only |

Also test malformed UUIDs, invalid ciphertext encodings, oversized payloads, expired challenges, repeated code guesses, and reconnects from an old cursor.

## Release gates

Do not ship production networking until:
- The migration is applied and reviewed on staging.
- The authorization matrix passes.
- Client-side end-to-end encryption and safe world-key distribution are implemented.
- Recovery and member-revocation key rotation are implemented and tested.
- The local Room outbox survives process death and retries safely.
- Two to five physical Android devices pass reconnect, offline, and conflict tests.
- The APK contains no privileged backend secret.
