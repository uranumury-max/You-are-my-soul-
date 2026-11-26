# عالمنا الخاص — You Are My Soul

Android app built natively with Kotlin and Jetpack Compose. The intended product is an animated shared world for 2–5 participants, with a stable unique 9-digit app identity per installation (not a phone number), host-phone persistence, invitations, memories, shared drawing, journey scenes, future capsules and a growing garden.

## Current implementation checkpoint
- Native Android/Compose project scaffold.
- RTL Arabic-first interface, five-destination mobile navigation, animated page transitions, twinkling starfield and orbiting centerpiece.
- Locally saved garden planting and daily reflection prompt, grouped with journey milestones in a compact experiences hub.
- Locally persisted 9-digit device identity and copy action.
- Connection UI prototype and tab structure.
- GitHub Actions APK build workflow.

**Not yet complete:** network transport/host server, remote invitation delivery, cross-device synchronization, cross-device feature persistence, shared drawing/photo album/capsules, trusted-time journey unlocks, and the complete animation/feature set. A host phone cannot be reachable from everywhere while powered off or offline; remote reachability requires a network route/relay. The code must not imply a real connection until that layer is implemented and tested.

Build: open with Android Studio or run `./gradlew assembleDebug`.
