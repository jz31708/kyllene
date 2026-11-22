# Server-pushed speech

A seat on the Board speaks when it has something to say — a deferred answer, a result worth hearing — not only
when the owner talks to it. This note is the phone's end of that: what Kyllene already receives, the one message
type this fork adds, and the rules the phone follows before it makes a sound.

Status: client types and the queue are in this fork (`shared/core/.../speak/`, `PushMessage.speak`). The gateway
side is owned elsewhere; §5 is the contract it has to meet.

## 1. What already exists

The gateway's **herald-push** plugin (`docs/push-protocol.md`) carries end-to-end encrypted envelopes over ntfy,
both sides connecting outbound. The phone subscribes on its own topic while "Notifications anywhere" is on
(`PushListener`), verifies and decrypts each envelope, then dispatches on `type`:

| Today | Means |
|---|---|
| `bot_message` | a final assistant reply, as a notification |
| `approval` | an approval is waiting |
| `phone_request` | a phone tool call waits; the phone fetches it over the authenticated socket |
| `phone_call` | the agent calls: the phone **rings from the push alone**, even with no route to the gateway |
| `cron_run` | a followed job finished |
| `ping` | the plugin's "send a test" |

`phone_call` is the important precedent: the phone already starts a call from a push, with
`PhoneToolsBridge.ring` → `PhoneTool.CallMe` → a voice chat whose opener is the call's `reason`. Nothing about
that path needs the gateway to be reachable at the moment the push lands.

Two limits of that path matter here. It always **rings a new call**: while a call is on, `ringForAgent` refuses
the push as busy, so it cannot put a line into the call that is already happening. And a turn that arrives in the
call's session from elsewhere is rendered in the transcript but never voiced — the call reads aloud only the reply
to the turn it sent. Server-pushed speech therefore needs its own small path; neither existing one can carry it.

Two other channels exist and neither is a push: the dashboard WebSocket (`/api/ws`, JSON-RPC, events and
server-to-client requests) while the app is connected, and the gateway API server's SSE runs endpoint. The
dashboard socket is the fast path while the app is in the foreground; ntfy is the one that survives the app being
backgrounded or the phone being off the tailnet. Hermes has no FCM or APNs; the phone holds its own connection or
reads its own topic. Both need the app's process alive: the push wakes nothing by itself, and Android will not let
a background app start a media foreground service. That is why a queued line on an idle phone has to become a
notification (or, for `answer_now`, the ring that already exists) rather than speech.


## 2. The one addition: `speak`

A new gateway → phone type in the same envelope, same crypto, same `id`/`ts` rules:

```json
{
  "v": 1, "id": "<uuid>", "ts": 1790000000, "type": "speak",
  "bot": "board", "label": "Board", "session": "<stored session id>",
  "text": "Two decisions are waiting. The Lenovo queue drained at nine.",
  "priority": "answer_now" | "when_ready" | "fyi",
  "call": "<call id, optional>",
  "rev": 42, "expires_on_rev": 42
}
```

- `text` is what is said, verbatim. The gateway keeps a part to about 2000 characters (the envelope limit), splits
  long answers at sentence ends, and sends the parts in order with one `utterance` id and `part`/`parts`.
- `bot` is the seat whose voice says it, so the call can show the right thread and the watch the right floor holder.
- `priority`: `answer_now` jumps the queue (a person is waiting), `when_ready` is the default, `fyi` is dropped
  first when a gateway floods the phone, and nowhere before its time.
- `call` binds the words to one call. Without it, any active call may say them. Hanging up does not replay them:
  they stay in the seat's thread.
- `intent` / `rev` / `expires_on_rev` bind the words to an intent and its revision. A correction advances the
  revision and re-emits the whole corrected text under the same `intent`; items bound to the old revision are
  retired before they start, or stopped after the sentence in progress if they already started. What was heard is
  not repeated: the client speaks only the remainder when the correction continues what it heard, and the whole
  text after a cue when it rewrites it.
- `expires` bounds a `fyi` item's life in seconds, so stale news is never said after the moment passed.

`id` doubles as the dedupe key: ntfy can redeliver, and a message id already seen in the last 24 h is ignored
(`PushGuard`), which is exactly the semantics the queue wants.


## 3. What the phone does with it

`SpeakQueue` is a small, pure state machine (common Kotlin, unit-tested in `SpeakQueueTest`):

1. **Offer.** A repeat, an empty utterance, or one already past its revision is refused.
2. **Order.** `answer_now`, then arrival order.
3. **Floor.** Nothing starts unless a call is active, the owner isn't speaking, and audio focus is the app's
   (`SpeakFloor`). Barge-in wins: a partial utterance from the owner stops playback and never becomes a command.
4. **Say it.** The words go through the same path a reply takes — the gateway's TTS (`/api/audio/speak-stream`
   on the dashboard, PCM back) — so the seat's voice is whatever voice the profile already uses. No second player.
5. **Track what was heard.** `hear(chars)` records a known-character position during playback;
   `finished(outcome)` keeps the portion heard even when the item is stopped or goes stale.
6. **Retire.** `advanceTo(rev)` drops queued items bound to the old revision and acknowledges them as `expired`.
7. **Hang up.** Unheard items leave the queue; they are still in the seat's thread, where the owner reads them.

The phone also needs a cue: about 300 ms before audible speech, a short soft tone in the earpiece/speaker and a
haptic on the watch, scheduled against measured audible onset rather than against the request. That is the watch
companion's job (`docs/board/watch-plan.md`).

## 4. Acknowledgements

Reserved in the push protocol's phone → gateway direction (the plugin does not read `reply_topic` in 0.1):

```json
{ "v": 1, "id": "<uuid>", "ts": 1790000004, "type": "speak_ack",
  "speak": "<the speak message id>", "heard": 96,
  "outcome": "played" | "stopped" | "expired" | "skipped" }
```

`heard` is the number of characters audible, from zero. Until the reply topic is accepted, the same information
can go over the dashboard socket as an RPC call, so a seat can tell "you heard half of that" from "you heard none".
The gateway must treat an ack as evidence, never as permission: it changes what the seat knows, not what it may do.

## 5. What the gateway must provide

Owner: the Voice chief (C4). The phone needs, in order of value:

1. **A producer** for `speak` items behind `wt speak --priority ... --expires-on-rev N`, with the queue's own
   ordering and expiry, and a call id when a call is on. The existing `phone_call` push is the same shape of work.
2. **A revision source.** Every capture that changes intent bumps a revision; queued items carry it. Without this,
   a correction cannot retire a queued answer.
3. **TTS on the dashboard server.** The audio routes the phone uses (`/api/audio/transcribe`,
   `/api/audio/speak-stream`, `/api/audio/speak`, `/api/audio/voice-live/status`) exist on the dashboard
   (`hermes_cli/web_routers/audio.py`), not on the gateway API server on :8642, which answers 404 for all four.
   Voxtral replaces Groq/Edge behind those routes; the phone needs no change for that.
4. **The seat as a model id.** `t3-seat/board` and friends are model ids on the profile's provider list, so a call
   can select one per chat, exactly as it selects any other model.

## 6. Fallback

No call active, or the phone cannot take audio focus: the item becomes a notification ("Board has something to
say"), and tapping it opens the seat's call. A lost connection never suppresses the saved tone (there is nothing
saved to play), and a partial utterance can stop playback but never becomes a command.

## 7. What this fork does not do yet

- No automatic start of a call on a `speak` push: the queue is wired into the push types and the core, and the
  call start reuses `phone_call`'s existing path. This is the first thing to land after the gateway produces items.
- The ack is not sent yet; the type is defined so the transport can be chosen without changing keys.
- The watch haptic lead is specified, not implemented.
