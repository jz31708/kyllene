# Building and installing

Builds happen on the Lenovo; the phone and watch are installed over wireless ADB. Kyllene is a Kotlin
Multiplatform project: `shared/core` (protocol, storage, queue logic), `shared/ui` (Compose screens), `androidApp`,
`wearApp`, `desktopApp`, `pebbleApp`.

## What the build requires

| Piece | Version | Why |
|---|---|---|
| JetBrains Runtime | **25** | Gradle's daemon JVM (`gradle-daemon-jvm.properties`); a plain JDK 21 will not do for the daemon |
| Gradle | 9.8.0 | the wrapper pins it |
| Android Gradle Plugin | 9.5.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | ditto |
| Android SDK | platform **37.1**, build-tools 37.0.0 | `compileSdk = 37.1`, `targetSdk = 36`, `minSdk = 26` |

The repo asks for the JBR because Compose and AGP 9.5 want a JDK that ships the desktop bits; Gradle downloads it
when none is found, which is why a machine with only JDK 21 still builds after the first long run.

## On the Lenovo

Measured 22 November 2026:

| Component | State |
|---|---|
| OS | Ubuntu 26.04.1, x86_64, 16 cores |
| JDK | Temurin 21.0.13.1 at `~/jdk` (`JAVA_HOME` set) for the client; Gradle and Kotlin daemons on auto-provisioned **JBR 25** |
| Gradle | 9.8.0 via the wrapper; AGP 9.5.0, Kotlin 2.4.20, KSP 2.3.12 |
| Android SDK | `~/Android/Sdk`: platforms `android-36` and `android-37.1`, build-tools 36.0.0 and 37.0.0; adb 37.0.1 |
| Disk | 97% used, ~5.6 GB free — enough for an incremental build, not for a clean checkout plus caches |
| Builds | `:androidApp:assembleDebug` 2 m 25 s cold / 19 s warm; `:wearApp:assembleDebug` under a minute |
| Tests | `:shared:core:testAndroidHostTest --tests dev.hermeskotlin.core.speak.SpeakQueueTest` — 19 tests, 0 failures |

```bash
cd ~/repos/kyllene
git fetch origin && git checkout board/voice-door && git pull --ff-only
./gradlew :androidApp:assembleDebug          # the phone app
./gradlew :wearApp:assembleDebug             # the watch app
./gradlew :shared:core:testAndroidHostTest    # unit tests, including the speak queue
```

APKs land in `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (about 60 MB) and
`wearApp/build/outputs/apk/debug/wearApp-debug.apk` (about 64 MB), both V2-signed with the standard
`CN=Android Debug` key. Keep long build logs in `~/logs`, not `/tmp`: `/tmp` is tmpfs on the Lenovo and a build
log there does not survive a reboot (one did not, on 22 November).

CI runs a longer list (`CONTRIBUTING.md`): every host-test module, `assembleDebug`, `assembleDev`, lint, and the
watch app's tests. A patch is only "done" once that list passes somewhere with an SDK; nothing in the fork's docs
or queue logic needs a device.

## Installing to the Oppo and the watch

Both install over wireless ADB from the Lenovo, so the phone and watch never leave the house and no cable is
needed after the first pairing.

1. On the Oppo: Settings → About → tap Build number seven times; then Developer options → **Wireless debugging** on.
2. Wireless debugging → **Pair device with pairing code** → note the IP:port and the six-digit code.
3. On the Lenovo: `adb pair <ip:port>` then the code; afterwards `adb connect <ip>:<port-from-the-main-screen>`.
4. `adb devices` shows the phone; `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
5. Repeat 1–3 on the Pixel Watch (Developer options → Wireless debugging), then install the wear APK the same way.
   Watch and phone must be built together: they share the `applicationId` and use split version codes.

The pairing code is the only thing a human has to type, and it changes every time the pairing dialog is reopened,
so it belongs in a secure card, not in a note.

## Signing into a gateway

The phone signs in to the **dashboard** (`https://<host>:9119`), not the API server on `:8642`. Native sign-in is
an OAuth PKCE flow in a Custom Tab with a loopback redirect, or a password login; both end in a WebSocket ticket.
The dashboard must be reachable from the phone's network — on the tailnet, `hermes.tail38b488.ts.net` — and a
non-loopback bind requires an auth provider, which is why the phone can never talk to an unauthenticated dashboard.

## What the phone talks to, in one line

`/api/ws` (JSON-RPC) for everything conversational, `/api/audio/*` on the dashboard for voice, and the ntfy topic
from the herald-push plugin for anything that must arrive while the app is in the background.
