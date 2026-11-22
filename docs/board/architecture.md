# Architecture: how the phone talks to Hermes

Everything here was read from this fork's source at `ab4daa7b` (upstream `master`, 22 November 2026) and from
hermes-agent's own client contract notes (`docs/hermes-protocol-research.md`). Line references are to this tree.

## 1. Which door the app uses

Kyllene talks to the Hermes **dashboard server**, not the gateway API server: JSON-RPC over
`/api/ws` plus the dashboard's REST routes, the same way Hermes Desktop does.

| Channel | What | Notes |
|---|---|---|
| `WS /api/ws` | 250-odd JSON-RPC methods and 75 events, server→client requests (`srq-*`) | the app's only live channel; `session.events.since` replays what it missed |
| REST | sessions, profiles, models, audio, files, cron, plugins | `api/sessions`, `api/audio/*`, `api/profiles`, `api/config` |
| ntfy (herald-push plugin) | encrypted notifications, ring, phone tool calls | the only channel that reaches a backgrounded phone, and only while its process lives |
| Wear Data Layer | phone ↔ watch messages and audio channels | see [watch-plan.md](watch-plan.md) |

There is **no SSE client and no FCM** in the app (`grep event-stream` finds nothing; no Firebase dependency).
The gateway API server on `:8642` is unused by Kyllene, and it has no audio routes at all: `/api/audio/*` answers
404 there while the dashboard serves them (`hermes_cli/web_routers/audio.py`). That matters for the Board, because
the spec's media path assumes those routes: they live on the dashboard the phone already signs in to.

## 2. The chained voice flow, end to end

`VoiceController.startChat` is the only entry (`shared/ui/.../voice/VoiceController.kt:233`). It takes one chat
app-wide (`VoiceChats`, `:1350`), stops read-aloud and dictation, asks `/api/audio/voice-live/status`, and falls
back to `converseChained` (`:325`) unless GPT-Live is both configured and available.

1. **Route and lease.** The call audio route is taken only when barge-in is on (`:336`, `VOICE_COMMUNICATION` +
   `AcousticEchoCanceler`); the TTS lease `android:conversation` is held for the call (`:338`, released at `:376`).
2. **Mic and VAD.** 16 kHz mono `AudioRecord`; end-of-speech is `EndOfSpeech` (`Speech.kt:30`), threshold 0.025, a
   300 ms noise calibration, and a pause the owner picks: 1250 / 2000 / 3000 ms (`VoicePause`). `sendNow` ends a
   recording by hand (`:456`).
3. **Transcribe.** `POST api/audio/transcribe` with a base64 data URL → `{transcript}` (`AudioApi.kt:77`). The
   profile's own STT provider runs server-side, so replacing Groq with Voxtral changes nothing here.
4. **Turn.** `prompt.submit` with the runtime session id, text, `surface:"voice-live"` and `voice_context`
   (`ChatSession.kt:311`). **No model field.** The seat is whatever the session's model is.
5. **Speech back, streaming.** In parallel with the send, a WebSocket opens
   `api/audio/speak-stream?profile=`; text frames go up, PCM comes down and plays as it arrives
   (`SpeechStream.kt:76`, `AudioApi`-adjacent `playPcm`). Sentence splitting and the "speakable" filter (fences,
   `MEDIA:` lines) are client-side. If the socket is missing, `POST api/audio/speak` per chunk is the fallback.
6. **Barge-in and echo.** A second listen runs over playback (`listenOver`, `:599`); a detected utterance holds
   the player, transcribes, is compared against the TTS it just heard (`isTtsEcho`), and only then cancels the
   reply. "Stop" is a phrase list (`Speech.kt:427`), not a wake word. Echo holds stop listening over a reply nobody
   talks through (`MAX_ECHO_HOLDS = 2`).
7. **Mute** stops the listening loop without ending the call; `skipSpeech` skips the current reply.

GPT-Live is the other engine: a WebRTC call (`api/audio/voice-live/session`) whose model calls the full agent as a
tool. It has no output hold — the app cannot make it say an exact sentence — which is why pushed speech is a
chained-path feature in this fork.

## 3. Sessions, profiles and models: can a session pick `t3-seat/board`?

**Yes, if the gateway lists it.** The model is per session, chosen server-side:

- `model.options` (`ModelCatalog.kt:194`) lists providers and models; the picker only offers rows from it.
- Picking one sends `config.set {key:"model", value:"<model> --provider <provider> --session"}` for a live chat
  (`ChatSession.kt:1809`) — session-scoped, so it never rewrites the profile default — or `session.create` with
  `model`/`provider` for a new chat.
- `prompt.submit` carries no model, so a call's seat is fixed for the session until it is changed.
- Bots are profiles with their own model and soul (`profiles.configure`), so "Life Secretary" is naturally a
  profile, not a model id.

Consequence for the Board: **the seat bridge is addressable from the phone with no new protocol.** A provider
plugin that registers `t3-seat` with models `board`, `life`, `travel-deals` appears in the existing picker, and a
call picks it like any other model. Two gaps are ours, not Hermes's:

1. no screen accepts a raw provider/model string, so a seat that the gateway does not list cannot be typed in;
2. there is no "seat" concept in the call UI — the picker shows a model name, not "Board".

The cheapest honest shape is one **session per seat** ("Board", "Life", "Travel & Deals"), each with
`model = t3-seat/<seat>`, plus a call target per seat (`CallTarget` already supports a session target and is
validated against `api/sessions/{id}/messages`). The seat switcher then becomes a list of sessions, which is UI we
already have.

## 4. Receiving speech the phone did not ask for

Today: impossible without starting a new call. The ring path carries text (`phone_call` push → `ring` →
`CallMe` → `VoiceController.startChat(opener = reason)`), but it refuses while a call is on, and a turn that
arrives in the call's session from elsewhere is drawn in the transcript and never voiced, because the call only
reads the reply to the turn it sent. See [server-push.md](server-push.md) for the `speak` type and the queue.

Two extension points exist and neither is enough alone:

- `SessionWatcher` already attaches to sessions the UI isn't showing and exposes `turnStarts`/`turnEnds` — a good
  place to *notice* that a seat said something;
- `ChatHost` is an app-level singleton holding the open `ChatSession`, so a pushed line can be routed to the right
  conversation without a screen being open.

What is missing is a player that outlives a screen: `SpeechPlayer` is a singleton, but `VoiceController` is
per-screen. Pushed speech needs a small call-level owner (a service or an app-scoped controller) rather than a new
player.

## 5. What the Board needs, and what changes here

| Board requirement (§11) | Today | Change |
|---|---|---|
| Text with the Life Secretary, Chair and CEOs | sessions and profiles exist; seat model ids work once listed | one session per seat; a seat picker in the call UI |
| Voice with any seat | chained flow, per-session model | nothing beyond the seat session |
| Server-initiated speech, queued and stale-aware | absent | `speak` push + `SpeakQueue` (this fork) + a call-level owner in `VoiceController` |
| Barge-in, echo rejection, TTS lease | present | reuse as-is |
| Full duplex, owner-voice guard | barge-in yes; speaker verification absent | not in this fork's first slice |
| Setting (driving/walking/desk) | absent | `ActivityRecognition` client, carried in the turn's `voice_context` |
| Seat voices | one profile voice at a time | a per-profile `voice.tts` setting, gateway side |
| Watch tiles, haptics, floor | partial | [watch-plan.md](watch-plan.md) |

## 6. Two facts worth carrying into design reviews

- **Audio focus is not managed for calls.** Only read-aloud asks for `GAIN_TRANSIENT` and pauses on loss; a voice
  chat requests no focus and never looks at telephony or navigation. The spec's "navigation, phone calls and safety
  alerts don't hold audio focus" needs a focus gate added, not wired.
- **The push wakes nothing.** A push arrives only into a live process; Android forbids starting a media foreground
  service from the background. So "a seat says something while the phone is in a pocket" ends as a notification or
  a ring, never as surprise audio. That is a platform boundary, not a design choice.
