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
