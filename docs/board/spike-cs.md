# Spike: a Board call through telecom (`spike/connection-service`)

The research in [call-while-locked.md](call-while-locked.md) says a `microphone` foreground service may only be
created while the app is visible, and that a self-managed `ConnectionService` is the one mechanism that might get
around it — a call is a call, and telecom owns it. This branch measures that instead of assuming it. Nothing here is
wired into the shipped call path.

## What the branch adds

| Piece | What it does |
|---|---|
| `TelecomSpike` (`android/telecom/TelecomSpike.kt`) | Registers a `PhoneAccount` with `CAPABILITY_SELF_MANAGED` (as `com.hermeskotlin.android.telecom`), logs the registration, and reports an incoming call on it. |
| `BoardConnectionService` | `ConnectionService` behind that account; the connection is self-managed, rings, and logs every system callback: answer, reject, disconnect, abort, hold, mute, audio state, and `onShowIncomingCallUi`. |
| `BoardCallAudioService` | A `phoneCall\|microphone` foreground service with a `CallStyle` notification, started **from the connection** (a background start) that opens `AudioRecord` and logs the level once a second. |
| `BoardCalls.start()` | When Kyllene is out of sight (the case under study) the wrist's ask is reported to telecom; in sight, the app's own way in is untouched. |
| `HermesApplication` | Registers the account at start. |

Everything is `adb logcat -s WT-CS` on the phone. The tag is kept for the spike only.

## The three questions, and the lines that answer them

1. **Can the watch start a new Board call while the phone is locked and Kyllene isn't visible, and does audio
   actually record?**
   - `incoming: place reason=watch` → `incoming: placed` (telecom accepted it) or `incoming: refused …`.
   - `connection: created` → `probe: foreground started types=…` (`phoneCall|microphone`) or
     `probe: startForeground threw SecurityException` — the answer to "may we hold a microphone from here".
   - `probe: first sound at …ms` with a level above zero, or a run of `probe: …ms of exact zeroes`.
2. **Do the system's call controls on the watch (mute, end) drive it, and could they replace `WT-REMOTE`?**
   - `call: mute=true|false from the system's UI`, `call: disconnect from the system's UI`,
     `call: audio-state route=… muted=…`, `call: hold/unhold`.
   - If those lines appear when the controls are used, the custom `WT-REMOTE` command channel for mute and End is
     redundant; the floor and haptics stay ours.
3. **How does it behave under ColorOS?**
   - `probe: t=…ms` continuing once the screen is off. A gap in those lines (with the process still alive) is
     ColorOS taking the call down; `probe: service destroyed` before the 60 s unanswered end is the same thing said
     plainly.

## Running it

```bash
A=/opt/android-sdk/platform-tools/adb
PHONE=$($A devices | awk '/192\.168\.1\./ && !/192\.168\.1\.10/ {print $1; exit}')
WATCH=$($A devices | awk '/192\.168\.1\.10/ {print $1; exit}')

# 1. The build, and what it is.
$A -s "$PHONE" install -r ~/apk/spike-cs/androidApp-board.apk
$A -s "$WATCH" install -r ~/apk/spike-cs/wearApp-board.apk

# 2. Clear the log, put the phone out of sight and lock it.
$A -s "$PHONE" logcat -c
$A -s "$PHONE" shell input keyevent KEYCODE_HOME
$A -s "$PHONE" shell input keyevent KEYCODE_POWER      # screen off: the state being measured

# 3. The wrist's ask, without touching the watch: the tile's own entry point.
$A -s "$WATCH" shell am start -n dev.herald.android.board/dev.hermeskotlin.wear.BoardCallActivity \
  --ez board_call_start true

# 4. What happened.
$A -s "$PHONE" logcat -d -s WT-CS

# 5. Put it back.
$A -s "$PHONE" shell input keyevent KEYCODE_POWER
```

Step 3 is the tile's own code path (`BoardCallActivity.EXTRA_START`), not a new one. Steps 2 and 5 turn his screen
off and on again, which is the only way to measure the locked state; nothing else on the device is touched.

The controls in question 2 are tapped on the watch's call UI, which is his hand, not the cable — the spike logs
whatever the system sends, and the report says which of those were seen.

## What would make this a product

- The call screen the system asks for (`onShowIncomingCallUi`) has to be the app's own voice chat, and it has to be
  allowed up over the keyguard; if it isn't, the call is a call without a UI and the wrist would need its own.
- The voice chat's audio has to move onto the call's route (`setAudioModeIsVoip` + telecom's route changes), or the
  probe and the chat would fight for the microphone.
- The probe is replaced by the chat's own recorder, and `VoiceCallService` retires in favour of the call's service.
