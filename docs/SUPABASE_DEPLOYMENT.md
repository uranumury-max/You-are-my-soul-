# Supabase deployment checklist

The Android client is deliberately not considered cloud-ready until these steps have been completed against a real Supabase project.

## 1. Create and configure the project

1. Create a Supabase project and keep its database password outside this repository.
2. Enable anonymous sign-ins in the project's Auth settings. The app creates an authenticated anonymous identity for each installation.
3. Keep the project URL and the public publishable key available. The app accepts an `sb_publishable_...` key (or a legacy anon JWT); it rejects secret and service-role keys.

## 2. Apply migrations in order

Run the checked-in SQL files in the Supabase SQL Editor, one at a time, in this exact order:

1. `supabase/migrations/202611270001_secure_world_sync.sql`
2. `supabase/migrations/202611270002_append_world_event_rpc.sql`
3. `supabase/migrations/202611270003_join_request_display_name.sql`
4. `supabase/migrations/202611270004_device_public_key_registration.sql`
5. `supabase/migrations/202611270005_world_key_envelopes.sql`
6. `supabase/migrations/202611270006_server_clock.sql`
7. `supabase/migrations/202611270007_immutable_device_public_key.sql`
8. `supabase/migrations/202611270008_owner_member_management.sql`
9. `supabase/migrations/202611270009_immutable_world_key_envelopes.sql`
10. `supabase/migrations/202611270010_server_authoritative_key_version.sql`

Do not skip a migration or reorder them. The private `world-media` bucket and its member-scoped read, insert, and delete policies are created by migration 001. The upload policy enforces a per-world limit of 100 objects.

## 3. Supply client configuration securely

For a local build, place these values in the user's Gradle properties file (not in this repository):

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_PUBLIC_KEY
```

The same names can be supplied as environment variables. For GitHub Actions, add repository or environment secrets named `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. The checked-in Android workflow already passes these two secrets to the Gradle build step when they are configured.

Never put a database password, `service_role` key, or any other administrative secret in Gradle properties committed to Git, an APK, or a client build.

## 4. Required two-device acceptance checks

- Create a world on device A and complete the pairing request from device B.
- Confirm that device B cannot join without owner approval.
- Confirm that each device receives only its own wrapped world-key envelope.
- Create and edit memories, messages, drawings, daily answers, capsules, and garden state on both devices.
- Upload, preview, and delete an image; confirm deletion metadata reaches the other device.
- Disconnect one device, make changes on both devices, reconnect, and verify event replay without losing collection entries.
- Verify revoked devices cannot read, upload, or delete world data.
- Confirm no secret or service-role key is present in the generated APK.

A successful Android build or SQL parser run does not replace these live integration checks.

## Device-key safety note

Migration 007 makes each registered installation's RSA public key immutable through ordinary registration. Migration 008 adds an owner-only active-member list and the display labels needed for explicit member revocation. Migration 009 makes a recipient envelope immutable within one world-key version: retrying the exact same envelope is idempotent, while changing it requires a new key version. Migration 010 stores the active key version on the server and lets only the owner advance exactly one version after every active member has a registered device and every active device has received its own envelope. This is a server-side activation gate, not yet a complete Android rotation/recovery workflow. Do not manually edit device-key or envelope rows to work around a rejected operation.
