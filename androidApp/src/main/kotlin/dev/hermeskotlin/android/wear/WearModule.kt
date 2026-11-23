package dev.hermeskotlin.android.wear

import dev.hermeskotlin.android.pebble.PebbleOutbox
import dev.hermeskotlin.android.pebble.PebbleRequests
import dev.hermeskotlin.android.pebble.PebbleRinger
import dev.hermeskotlin.android.pebble.RoutingOutbox
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.CatalogueWatchCallWords
import dev.hermeskotlin.ui.voice.WatchCalls
import dev.hermeskotlin.ui.calls.PhoneCallModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * The watch companion's phone side: the relay to Hermes, its live calls, the Data Layer and the Pebble link under them,
 * its request notifications and the accent it follows.
 */
val wearModule = module {
    single { PebbleOutbox(androidContext(), get(), get(), get()) }
    single<WatchOutbox> { RoutingOutbox(PhoneOutbox(androidContext()), get()) }
    single { PebbleRequests(get(), get(), get(), get(), get()) }
    single { PebbleRinger(androidContext(), get(), get(), get(), get()) }
    single { WatchRelay(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), CatalogueWatchCallWords) }
    single { WatchCalls(get(), get(), get(), get(), get()) }
    single { WearRequests(get(), get()) }
    // The Board on the wrist: what the tiles show, and the notes that come back; neither needs a watch to exist.
    single { BoardSync(androidContext(), get(), get()) }
    single {
        BoardNotes(androidContext(), deliver = { text, at ->
            get<dev.hermeskotlin.android.words.OwnerWordOutbox>().deliver(
                dev.hermeskotlin.core.words.OwnerWordDraft(
                    text = text,
                    kind = "text",
                    capturedAt = at / 1000,
                    source = "kyllene-watch",
                ),
            )
        })
    }
    // The wrist's start and stop of the phone's Board call.
    single { BoardCalls(androidContext(), get()) }
    single(createdAtStart = true) { BoardTiles(get(), get(), get(), get()).also { it.start() } }
    single(createdAtStart = true) { WatchProfileNotifier(androidContext(), get(), get(), get()).also { it.start() } }
    single { WatchRinger(androidContext(), get(), get(), get(), get(), get()) }
    single { WatchAccentSync(androidContext(), get(), get()) }
    // A call for an answer picked up on the phone holds its chat as a call from the watch does.
    viewModelOf(::PhoneCallModel)
}
