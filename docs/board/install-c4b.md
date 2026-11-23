# C4b — installing tonight's Board builds

Built on the Lenovo, 2026-11-23, from `task/c4b-kyllene` (12 commits of `board/voice-door` plus tonight's work).
Both APKs are **release-mode** (`board` build type: R8, not debuggable), signed with the standard Android
debug key because this fork has no release keystore, and carry the `.board` application id so they install
beside Thomas's released Kyllene without touching its data, sign-in or gateway pairing.

| What | Path | applicationId |
|---|---|---|
| Phone | `androidApp/build/outputs/apk/board/androidApp-board.apk` | `dev.herald.android.board` |
| Watch | `wearApp/build/outputs/apk/board/wearApp-board.apk` | `dev.herald.android.board` |

The watch and the phone must be installed together: the Wear Data Layer only connects apps whose package
names match.

## The morning commands (one block per device)

Wireless debugging must be on for each device; the phone normally has it off. The pairing code is typed once
and changes every time the pairing dialog is reopened, so it belongs in a secure card, not in a note.

```bash
# 1. Find both devices (mDNS first, port sweep as fallback).
phone discover
phone status

# 2. Phone: install over the last Board build (never the released Kyllene).
phone install ~/.t3/worktrees/kyllene-c4b/androidApp/build/outputs/apk/board/androidApp-board.apk
phone packages | grep -i herald          # expect dev.herald.android and dev.herald.android.board
phone launch dev.herald.android.board

# 3. Watch: same, through the watch target.
phone --watch discover
phone --watch status
phone --watch install ~/.t3/worktrees/kyllene-c4b/wearApp/build/outputs/apk/board/wearApp-board.apk
phone --watch packages | grep -i herald
```

If a device is not reachable, do not enable developer options or toggle settings: stop there and install
when Thomas turns wireless debugging on.

## The 20-minute device test

1. **Share sheet.** From Chrome, share a page to "Kyllene Board: save to Board". From Photos, share a
   screenshot. Both should answer "Saved to your words" (or "Kept on the phone" when the ledger route is not
   live yet), and the text/link must appear unchanged in the Board's owner words.
2. **Audio focus.** Start a Board voice call on the Oppo with the Shokz connected. Start Google Maps
   navigation: the seat must pause, the navigation prompt must be audible, and the seat must resume
   afterwards. Take a phone call: the same hold, and the call audio must go to the phone, not to the seat.
3. **Shokz routing.** With the OpenRun Pro 2 connected, playback stays on A2DP and the Oppo records; switching
   to headset-mic mode moves both to the headset. Connect or disconnect the Shokz mid-call: the route must not
   change silently (the call screen names the output).
4. **Server-pushed speech.** With a call open, have C4a's `wt speak` send a short line. It should be said once,
   in the seat's voice, after the current sentence; a second `speak` while the owner talks must wait. Watch:
   the soft pulse ~300 ms before the seat speaks.
5. **Watch.** Life and Board tiles show their last state; tapping Board opens the call screen. The mic button
   toggles the **phone's** mic (the watch must not light its own microphone indicator); a quick note shows
   "saved". The floor holder is the seat currently speaking.
6. **Hang up.** Unheard lines do not play after the call; they stay in the seat's thread.

Record which of these were real-device results and which were only rehearsal.
