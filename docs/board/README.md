# The Board in Kyllene

This fork is Thomas's phone client for the Board: the Life Secretary, the Chair and the CEOs, by text and by
voice, plus the Pixel Watch companion. Upstream is [Giton22/kyllene](https://github.com/Giton22/kyllene) (MIT);
we merge its releases when they help and keep our work in small commits on top.

Decision record: `worktable-spec.md` §11 (calls, doors and devices) and D12 (fork approved).

## What is here

| Note | What it settles |
|---|---|
| [architecture.md](architecture.md) | How the chained voice flow works today, what the app can select, and where the Board plugs in |
| [server-push.md](server-push.md) | The `speak` push type, the phone's speak queue, acknowledgements, and what the gateway must provide |
| [watch-plan.md](watch-plan.md) | What the Wear companion has, what the Board needs, and the port plan |
| [build-and-install.md](build-and-install.md) | Toolchain, the Lenovo build, and installing to the phone and watch |
| [install-c4b.md](install-c4b.md) | Tonight's release-mode Board APKs, the morning install commands and the 20-minute device test |

## The shape of the integration

```
mic ──► VAD ──► dashboard /api/audio/transcribe ──► turn ──► seat (t3-seat/board model id)
                                                                │
speaker ◄── PCM ◄── /api/audio/speak-stream ◄──────────────────┘
   ▲
   └── the speak queue ◄── ntfy push `speak` ◄── wt speak (seat, out of turn)
```

Two properties matter more than the rest:

- **The seat chooses every word.** The phone renders; it never reasons about what to say.
- **A call is a thread.** Text in Kyllene, voice in a call and the same conversation in T3 are one T3 thread, so
  nothing said on the phone is lost when the screen changes.

## Our changes to upstream

Kept deliberately small, one idea per commit, so upstream merges stay cheap:

1. `shared/core/.../push/PushMessage.kt`: the `speak` type and its fields.
2. `shared/core/.../speak/SpeakQueue.kt`: the phone's queue — priority, floor, revision expiry, heard-up-to-N.
3. `shared/core/src/commonTest/.../speak/SpeakQueueTest.kt`: the rules, as tests.
4. `androidApp` / `wearApp` `build.gradle.kts`: the debug build is **Kyllene Board** (`dev.herald.android.board`,
   same suffix on both sides so the watch still pairs) and installs beside the released app. The `board`
   build type is the same identity built like a release (R8, not debuggable) for a real install:
   `:androidApp:assembleBoard` and `:wearApp:assembleBoard`.
5. `shared/core/.../voice/CallAudioFocus.kt` + `AndroidVoice.kt`: a chained call asks for audio focus, holds
   on navigation and phone calls (no ducking of speech), resumes after, and names the route in use; an
   explicit headset-microphone mode moves the mic to the Shokz when asked. See
   [install-c4b.md](install-c4b.md) for what to check on the devices.
6. `androidApp/.../push/PushedSpeech.kt`: a seat's `speak` over `/api/ws` (or the ntfy push) goes through
   the one `SpeakQueue`; the open chained call says it, and a `speak_ack` reports what was heard. With no
   call it becomes a notification. Contract: [server-push.md](server-push.md) §3b.
7. `androidApp/.../words/`: the share sheet's **save to Board** target turns shared text, links and
   screenshots into owner words (`POST /api/worktable/owner-words`), with an on-phone outbox when the route
   is not there yet.
8. `wearApp/.../LifeTileService.kt`, `BoardTileService.kt`, `BoardHaptics.kt`: the watch's two tiles, the
   ~300 ms speech cue, and the mic inversion (the watch toggles the phone's mic and never opens its own).
   Protocol: [watch-plan.md](watch-plan.md) §4.
