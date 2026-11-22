# The watch companion

The Pixel Watch gets **Life** and **Board** call tiles, a mic toggle for quick notes, listening/saved/muted
states, a haptic about 300 ms before a seat speaks, and the floor holder. Kyllene already ships a Wear app; this
note is what it has, what the Board needs, and the smallest honest way to get there.

## 1. What upstream already has

`wearApp/` and `shared/wearlink/` are a working companion, not a stub. The wire is the Wear OS **Data Layer**:
`MessageClient` for JSON payloads, `ChannelClient` for audio. `shared/wearlink/.../WearLink.kt` is the contract —
path constants plus `@Serializable` payloads, 19 `/herald/*` paths and 3 channel prefixes.

| Piece | Can do today |
|---|---|
| `WatchLink` | ask/stop/quiet, chat pick, a WebRTC call, call mute, answer a ring |
| `MainActivity` | talk screen; watch recognizer, else a 16 kHz WAV recording |
| `AskTileService` | one tile: call the default target, show the last reply |
| `AskComplicationService` | short text, mono and small images |
| `WatchRings` / `RingActivity` | a full-screen call-style ring with a vibration pattern |
| `SpeechOut` | ordered TTS clips from the phone, device TTS as a fallback |
| `WatchCall` | WebRTC on the watch's own mic and speaker |

So the plumbing (paths, payloads, channels, tiles, ringing, audio) is done. What is missing is the Board's model of
the world: seats, a floor, what is saved versus heard, and a cue before speech.

## 2. The one design decision that constrains everything

**Upstream's watch is a microphone. `agent-config/mobile`'s watch deliberately is not** — it has no
`RECORD_AUDIO`, is not standalone, and gates its UI on a phone `mic_open` acknowledgement, because a watch mic in a
pocket is a lie about privacy and battery. Porting must pick one, and the Board wants the second: the **Oppo**
records, the watch shows state, toggles the phone's mic, and fires haptics. That makes a mic toggle an *action
message* to the phone, not a capture session on the watch — which is simpler than it sounds, since `WatchLink`
already sends `/herald/stop` and call-mute commands.

Android also refuses a background mic on API 34+ without a foreground service, and `wearApp` has none. Not
capturing on the watch removes that whole class of problem.

## 3. Gap analysis

| Requirement | State | Why |
|---|---|---|
| Life tile | **absent** | only `AskTileService` is bound in the manifest |
| Board tile | **absent** | room and seat data never leave the phone (`shared/core/.../rooms/`) |
| Mic toggle for quick notes | **partial** | the tile only launches an activity; the recorder dies at `onStop`; `toggleMute` is call audio only |
| Listening / saved / muted | **partial** | listening and muted exist in the call UI; nothing is tile-visible, and there is no "saved" |
| Haptic ~300 ms before speech | **absent** | the only vibration is the ring pattern; nothing reads a measured onset |
| Floor holder | **absent** | `CallUpdate.caption` carries no speaker, so it can't be derived |
| ntfy buttons on the watch | **partial** | mirrored notifications only; `NtfyEvent` doesn't parse server `actions`, so server-side buttons are dropped |

## 4. What to build

### 4.1 One phone-published state, two tiles

The watch must never derive the Board from captions. The phone publishes:

```kotlin
// shared/wearlink/WearLink.kt — P→W
@Serializable data class TilesState(val revision: Long, val tiles: List<CallTile>, val error: String? = null)
@Serializable data class CallTile(val id: String, val kind: String,      // "life" | "board"
                                 val title: String, val state: String,   // idle|listening|live|muted
                                 val detail: String? = null)
@Serializable data class BoardState(val revision: Long, val boardId: String,
                                    val seats: List<Seat>, val floor: String?, val pendingNotes: Int)
@Serializable data class Seat(val id: String, val label: String, val color: Long? = null)
// P→W, per spoken line
@Serializable data class SeatSpeech(val turnId: String, val seatId: String, val seq: Int,
                                    val text: String, val onsetEpochMs: Long?, val playingOnWatch: Boolean)
// W→P, a note taken on the wrist: text typed or dictated, or the id of a recorded clip
@Serializable data class QuickNote(val id: String, val text: String = "", val boardId: String?, val seatId: String?)
// W→P, evidence, not permission
@Serializable data class HapticFired(val turnId: String, val seatId: String, val firedEpochMs: Long, val leadMs: Long)
```

New paths: `/herald/tiles` (both ways), `/herald/board`, `/herald/board/speech`, `/herald/board/notes`,
`/herald/board/note/saved`, `/herald/board/haptic`. Unknown keys are already ignored on both sides, so an older
watch and a newer phone keep working.

### 4.2 Two tiles

`LifeTileService` and `BoardTileService` beside `AskTileService`, with its layout helper extracted into
`tiles/CallTileService.kt`. Tiles are `TileService`s (not plain layouts): they must answer within seconds from
`WatchStore`, so the phone pushes `TilesState` into prefs whenever state changes, and the tile reads the last
state rather than asking. Refresh with `TileService.getUpdater().requestUpdateAll()` when the phone says so. A
tile click is a `LaunchAction` (open the call screen) or a `LoadAction` (a short list of canned asks) — never a
capture.

### 4.3 Mic toggle

A `/herald/board/notes` message with `text` for a typed/dictated note, or no text and the watch records as it
already does for asks (`recordingPath(id)`). The phone saves it, answers `note/saved`, and the tile shows
**saved** — not "listening" and not "sent", because a note is saved before anything acts on it. The phone's own
mic stays the call's mic; the watch never opens one for a call.

### 4.4 The 300 ms lead

Reuse the design from `agent-config/mobile/modules/worktable-audio`:
`HapticScheduler` computes a cue at `onset - 300 ms`, drops it when the prediction is more than 250 ms late, and
corrects from the measured onset; `OnsetPredictor` clamps 350–2500 ms of TTS lead-up. In Kyllene the phone knows
when playback actually starts (`VoiceController` sees the first PCM frame), so:

1. the phone sends `SeatSpeech` with a predicted `onsetEpochMs` as soon as a queued item is accepted;
2. the watch schedules one soft pulse at `onsetEpochMs - 300 ms` on its own clock, and drops it if the clock has
   already passed `onset - 250 ms`;
3. when playback really starts, the phone corrects `onsetEpochMs`; a pulse that already fired is reported by
   `HapticFired` so the measured lead can be tuned later;
4. speech never waits on the watch. No watch, no haptic, same words.

Watch time may differ from phone time by seconds, so `onsetEpochMs` is anchored by a round trip (`/herald/status`
already answers) rather than assumed — a haptic that lands after the voice is worse than none.

### 4.5 Floor holder

`BoardState.floor` painted on the Board tile and in the call screen, with the seat's colour from the registry. The
phone already knows who is speaking (`SpeakQueue.playing.seat` and the call's opener); it publishes, the watch
renders.

## 5. Order of work

| Step | What ships | Depends on |
|---|---|---|
| 1 | `TilesState` + `LifeTileService` + `BoardTileService`, fed by a hand-published state in `FakePhone` | nothing |
| 2 | `QuickNote` + `note/saved`, keyboard and dictation first, recorded clip second | 1 |
| 3 | `SeatSpeech` + `HapticScheduler` + `HapticFired`, tuned against a real watch | the speak queue |
| 4 | `BoardState` floor and seat colours | a seat registry on the phone |

Step 1 is demoable without a gateway change, which is why it goes first.

## 6. Constraints worth knowing

- Watch and phone share `applicationId` and use split version codes; an install that drifts does not pair.
- `USE_FULL_SCREEN_INTENT` is Play-restricted; the ring stays a call/alarm use, as it is now.
- ntfy's server-side `actions` need `NtfyEvent` to parse them before a watch button can carry one; until then the
  watch mirrors the phone's own notification buttons, and the phone must be on.
- Tile content must fit a round 192 dp screen: a title, one line of detail, one state word.
