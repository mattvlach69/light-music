package com.matthewvlach.music

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightQueueItem
import com.thelightphone.sdk.ui.LightBarButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val EMPTY_QUEUE: StateFlow<List<LightQueueItem>> = MutableStateFlow(emptyList())

/** True when the player has something queued (playing or paused). */
@Composable
fun rememberHasQueue(): Boolean {
    val player by Music.playerFlow.collectAsState()
    val queue by (player?.queue ?: EMPTY_QUEUE).collectAsState()
    return queue.isNotEmpty()
}

@InitialScreen
class HomeScreen(host: SealedLightActivity) : MusicScreen(host) {

    override fun willShow() {
        super.willShow()
        Music.refresh(lightContext)
    }

    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val playlists by Music.playlists.collectAsState()
        val loading by Music.loading.collectAsState()
        val hasQueue = rememberHasQueue()

        Frame(title = null, onBack = null) {
            if (!loading && library.tracks.isEmpty()) {
                Message(
                    "No music yet.\n\nOpen Tool Manager on your computer, choose Music, " +
                        "and add files to Songs.",
                )
                return@Frame
            }
            RowList {
                if (hasQueue) item {
                    ListRow("Now playing") { navigateTo({ NowPlayingScreen(it) }) }
                }
                item {
                    ListRow("Artists", plural(library.artists.size, "artist")) {
                        navigateTo({ ArtistsScreen(it) })
                    }
                }
                item {
                    ListRow("Albums", plural(library.albums.size, "album")) {
                        navigateTo({ AlbumsScreen(it, artist = null) })
                    }
                }
                item {
                    ListRow("Playlists", plural(playlists.size, "playlist")) {
                        navigateTo({ PlaylistsScreen(it) })
                    }
                }
                item {
                    ListRow("Songs", if (loading) "Loading…" else plural(library.songs.size, "song")) {
                        navigateTo({ SongsScreen(it, artist = null) })
                    }
                }
            }
        }
    }
}

class ArtistsScreen(host: SealedLightActivity) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        Frame("Artists", onBack = { goBack() }) {
            RowList {
                items(library.artists) { artist ->
                    val n = library.albumsBy(artist).size
                    ListRow(artist, plural(n, "album")) { navigateTo({ ArtistScreen(it, artist) }) }
                }
            }
        }
    }
}

class ArtistScreen(host: SealedLightActivity, private val artist: String) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val albums = library.albumsBy(artist)
        Frame(artist, onBack = { goBack() }) {
            RowList {
                item {
                    ListRow("All songs", plural(library.songsBy(artist).size, "song")) {
                        navigateTo({ SongsScreen(it, artist) })
                    }
                }
                items(albums) { album ->
                    ListRow(album.title, plural(album.tracks.size, "song")) {
                        navigateTo({ AlbumScreen(it, album.key) })
                    }
                }
            }
        }
    }
}

class AlbumsScreen(host: SealedLightActivity, private val artist: String?) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val albums = artist?.let(library::albumsBy) ?: library.albums
        Frame(artist ?: "Albums", onBack = { goBack() }) {
            RowList {
                items(albums) { album ->
                    ListRow(album.title, album.artist) { navigateTo({ AlbumScreen(it, album.key) }) }
                }
            }
        }
    }
}

/** Shared body for any ordered list of songs: tap to play from there, or play/shuffle all. */
abstract class TrackListScreen(host: SealedLightActivity) : MusicScreen(host) {

    protected fun playFrom(tracks: List<Track>, index: Int, shuffle: Boolean = false) {
        Music.play(host, tracks, index, shuffle)
        navigateTo({ NowPlayingScreen(it) })
    }

    protected fun playBar(tracks: List<Track>): List<LightBarButton> = listOf(
        LightBarButton.Text("PLAY", onClick = { playFrom(tracks, 0) }),
        LightBarButton.Text("SHUFFLE", onClick = { playFrom(tracks, 0, shuffle = true) }),
    )
}

class AlbumScreen(host: SealedLightActivity, private val key: String) : TrackListScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val album = library.album(key)
        val tracks = album?.tracks.orEmpty()
        Frame(album?.title ?: "Album", onBack = { goBack() }, bottom = playBar(tracks).takeIf { tracks.isNotEmpty() }) {
            RowList {
                itemsIndexed(tracks) { i, t ->
                    val n = if (t.trackNo > 0) "${t.trackNo}. " else ""
                    ListRow("$n${t.title}", if (t.artist != album?.artist) t.artist else formatTime(t.durationMs).takeIf { t.durationMs > 0 }) {
                        playFrom(tracks, i)
                    }
                }
            }
        }
    }
}

class SongsScreen(host: SealedLightActivity, private val artist: String?) : TrackListScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val tracks = artist?.let(library::songsBy) ?: library.songs
        Frame(artist ?: "Songs", onBack = { goBack() }, bottom = playBar(tracks).takeIf { tracks.isNotEmpty() }) {
            RowList {
                itemsIndexed(tracks) { i, t ->
                    ListRow(t.title, if (artist == null) "${t.artist} · ${t.album}" else t.album) { playFrom(tracks, i) }
                }
            }
        }
    }
}
