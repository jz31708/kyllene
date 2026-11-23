# A call started from the wrist, with the phone locked

Research note, 2026-11-23. Not built: what each Android mechanism allows while Thomas's OPPO is in a pocket or a
car mount and the Pixel Watch is the thing he taps. The watch work assumes §11.6 of the Worktable spec — the phone
records, the watch toggles and displays — and that only holds if the phone can still record with the screen off.

Everything below is read from the Android documentation and issue tracker as of Android 16 (API 36). What ColorOS
does on top of it is not documented by Android, and is marked as such.

## The rule that decides all three

A microphone foreground service may only be **created while the app is visible**. Since Android 14, "the operating
system checks when you create a foreground service to make sure your app has the app's appropriate permissions …
If your app has a while-in-use permission, it only has that permission while it's in the foreground … the system
sees that your app doesn't currently have the required permissions, and it throws a `SecurityException`"
([FGS restrictions, "Restrictions on starting foreground services that need while-in-use
permissions"](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).

Once that service is up, nothing about the screen locking stops it: the service is already in the foreground, and
its `microphone` type is exactly what keeps `RECORD_AUDIO` usable out of sight — "Continue microphone capture from
the background, such as voice recorders or communication apps"
([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)). So:

- A call Thomas starts **on the phone, with the screen on**, then pockets: fine. This is what Kyllene does today
  (`VoiceCallService`, `foregroundServiceType="microphone"`, started from `VoiceController.startChat`, which cannot
  run before the screen shows the voice pane).
- A call the **watch** starts while the phone is locked and in a pocket: not possible as a microphone service,
  unless it falls in one of the narrow exemptions below.

Android 16 adds no new restriction here: its all-app changes are about job quotas, not background microphone access
([Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all)). Android 15's
changes are about `BOOT_COMPLETED` and `SYSTEM_ALERT_WINDOW`
([Android 15 behaviour changes](https://developer.android.com/about/versions/15/behavior-changes-15)) — and note
that `microphone` has been barred from `BOOT_COMPLETED` since 14, so "start it at boot" is not an option either.

The exemptions that matter (same page, "Exemptions from restrictions on while-in-use permissions"), verbatim:

- A system component starts the service.
- The service starts by interacting with app widgets.
- The service starts by interacting with a notification.
- The service starts as a `PendingIntent` that is sent from a different, visible app.
- The service starts by an app that is a device policy controller that runs in device owner mode.
- The service starts by an app which provides the `VoiceInteractionService`.
- The service starts by an app that has the `START_ACTIVITIES_FROM_BACKGROUND` privileged permission.

A Wear data-layer message is not on that list. A `CompanionDeviceManager` exemption is explicitly *not* one either:
"if an app wants to launch a foreground service that needs while-in-use permissions … it cannot create the service
while the app is in the background, **even if the app falls into one of the exemptions from background start
restrictions**" (same page, introduction to the while-in-use section).

## The three candidates

| | What it is | Locked phone, call already up | Locked phone, watch asks for a new call | Watch starts it how |
|---|---|---|---|---|
| **Mic foreground service (today)** | `VoiceCallService`, type `microphone`, held for the length of the chat | **Works.** Locking the screen or leaving the app doesn't revoke the type's mic access. ColorOS may still kill the process — see below. | **No.** `startForeground` throws `SecurityException` from the background on 14+; `BOOT_COMPLETED` is barred too. | A Wear `MessageClient` message wakes the phone app (`WearBridgeService`), but the message is not an exemption, so the reply can only be a notification, not a call. |
| **Self-managed `ConnectionService`** | A `PhoneAccount` with `CAPABILITY_SELF_MANAGED` + `MANAGE_OWN_CALLS`; calls placed with `TelecomManager.placeCall`, reported with `addNewIncomingCall` | **Works, and better**: telecom owns the call, so it appears in the system call UI (and on Wear, in the car, over Bluetooth) with Answer/Mute/End; the app still declares `phoneCall\|microphone` because `phoneCall` alone does **not** carry the microphone (Google, issuetracker 327085720: "For recording, the appropriate FGS type (`microphone`) is required … `phonecall` is not sufficient"). | **The strongest of the three, still not free.** A call reported through telecom gives the system something to show on the lock screen, and answering it goes through an activity — a user-visible transition, which is an exemption for the start. But the doc's exemption is about *who* starts the service; whether telecom's bind counts as "a system component starts the service" is not stated anywhere, and VoIP apps report exactly this gap in the tracker. Treat as "likely, must be measured", not as guaranteed. | The phone reports an incoming call; the watch's own call UI (Wear mirrors telecom calls) is then the answer surface — after the phone's incoming-call screen has been answered, or with `USE_FULL_SCREEN_INTENT` to bring it up over the lock screen. |
| **`CompanionDeviceManager`** | An association with the watch (`associate`, `startObservingDevicePresence`), plus `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | Nothing extra for the call itself; it is what keeps the app being woken and keeps the process alive near the watch. | **No**, by itself: it exempts the background FGS *start*, not the while-in-use permission the microphone needs. An app that starts a `microphone` service this way still gets `SecurityException` on 14+. | The message wakes the app, which may post a notification — and tapping that notification *is* an exemption ("starts by interacting with a notification"), so this is the documented way to turn a wrist tap into a call without the phone in hand… at the cost of a tap on the phone. |

Two more facts worth having:

- Kyllene already provides a `VoiceInteractionService` (`HeraldAssistService`), and an app that provides one is on
  the while-in-use exemption list. That exemption is documented for the assistant's own voice session, though, not
  for "some other device asked"; nothing in the docs says a Wear message routed through the assistant service is
  covered, and assuming it would be the kind of thing that works on a Pixel and not on ColorOS.
- `SYSTEM_ALERT_WINDOW` is no longer a shortcut: targeting 15+, the app needs the permission *and* a visible
  overlay window, and a window over the lock screen is exactly what ColorOS likes least.

## ColorOS

OPPO's own battery manager is not documented by Google, and it does what it likes with a foreground service once
the screen goes off: OPPO users report background services killed on screen-off unless the app is pinned in recents,
allowed in the security app's start-up manager, exempt from battery optimisation, and running a persistent
notification ([dontkillmyapp, Oppo](https://dontkillmyapp.com/oppo)). All four were needed on the device that was
measured there. For Thomas's CPH2841 that means: a call that is legal under Android can still be murdered by
ColorOS five minutes into a walk. Two consequences:

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the standard "Don't optimise" prompt) is worth having in onboarding; it
  covers part of it and is the only part an app can ask for itself.
- The rest ("Allow auto start-up", "Allow background activity", lock the app in recents) is an owner errand with a
  screenshot, not code — and it must be redone after ColorOS updates.

## What this means for §11.6

1. **A call that starts on the phone and is then pocketed already works** — this is the case the current build is
   for, and it is the only one that needs no new mechanism. What it needs is the mic-blocked state (so a call that
   isn't recording says so instead of looking like it is) and the reassurance that the service survives screen-off
   on ColorOS.
2. **A call the watch starts with the phone locked needs one of two things**, and both are product decisions rather
   than code:
   - a self-managed `ConnectionService`, so the wrist tap becomes an incoming call the system itself can surface and
     answer (and the same mechanism gives Mute/End on the watch, in the car and over Bluetooth for free — which is
     most of what the custom `WT-REMOTE` chain does by hand today); or
   - the watch records, and the phone only plays the reply. The Wear app already has a recorder and its own
     assistant service, and the watch's microphone is on the wrist rather than in a pocket. That removes the phone's
     microphone from the locked-phone problem entirely, and inverts §11.6's "the phone records" for the
     pocket case only.
3. **The middle path, if neither is taken now**: the wrist tap posts a notification; tapping it starts the call
   (documented exemption), and the mic-blocked state explains itself when it isn't tapped. Honest, and useless in a
   pocket.

## Sources

- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
  — the while-in-use section and its exemption list, the CompanionDeviceManager caveat.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) —
  `microphone` and `phoneCall`, and what each requires.
- [Android 16 behaviour changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all).
- [Android 15 behaviour changes](https://developer.android.com/about/versions/15/behavior-changes-15) —
  `BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`.
- [`ConnectionService`](https://developer.android.com/reference/android/telecom/ConnectionService) — self-managed
  accounts, `MANAGE_OWN_CALLS`, `placeCall` / `addNewIncomingCall`.
- [issuetracker 327085720](https://issuetracker.google.com/issues/327085720) — `phoneCall` does not carry the
  microphone; Google's answer that `microphone` is required alongside it.
- [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
  — associations, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, and the Android 16 presence API.
- [dontkillmyapp: Oppo](https://dontkillmyapp.com/oppo) — ColorOS killing background services on screen-off.
