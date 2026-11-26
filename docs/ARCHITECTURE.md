# Architecture and honest connection model

## Stable device identity
Each installation creates and locally retains a random nine-digit app identity. It is not a phone number and is not regenerated on each launch. A server-side uniqueness check is required before treating it as globally unique; the local generator alone cannot guarantee collision-free identities across every device.

## Host-owned world
The design assigns one participant's device as the authoritative host and stores a durable checkpoint on it. Participants also retain local snapshots. When a session ends, the latest received state should be checkpointed on every device.

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
- The world snapshot is encrypted with AES-256-GCM before it is written to app preferences.
- The encryption key is generated and retained by Android Keystore; it is not stored beside the encrypted snapshot.
- Existing plaintext snapshots are migrated on first successful read. The old value is removed only after the encrypted write is queued.
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
