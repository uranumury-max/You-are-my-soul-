# عالمنا الخاص — You Are My Soul

Android app built natively with Kotlin and Jetpack Compose. The intended product is an animated shared world for 2–5 participants, with a stable unique 9-digit app identity per installation (not a phone number), host-phone persistence, invitations, memories, shared drawing, journey scenes, future capsules and a growing garden.

## Current implementation checkpoint
- Native Android/Compose project scaffold.
- RTL Arabic-first interface and initial animated/celestial visual foundation.
- Locally persisted 9-digit device identity and copy action.
- Connection UI prototype and tab structure.
- GitHub Actions APK build workflow.

**Not yet complete:** network transport/host server, remote invitation delivery, cross-device synchronization, feature persistence models, and the complete animation/feature set. A host phone cannot be reachable from everywhere while powered off or offline; remote reachability requires a network route/relay. The code must not imply a real connection until that layer is implemented and tested.

Build: open with Android Studio or run `./gradlew assembleDebug`.
