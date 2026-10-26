package com.matthewvlach.music

import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * App-wide state shared by every screen: the library, playlists, and the one player.
 * The player is detached, so playback carries on after leaving the tool.
 */
object Music {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _library = MutableStateFlow(LibraryIndex.EMPTY)
    val library: StateFlow<LibraryIndex> = _library

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private var scanner: LibraryScanner? = null
    private var playlistStore: PlaylistStore? = null
    @Volatile private var dirty = true

    fun markDirty() { dirty = true }

    /** Rescan the Songs folder and playlists. */
    fun refresh(context: SealedLightContext) {
        if (scanner == null) {
            // Tool Manager serves files/shared (same root as LightFileShare).
            val shared = File(context.filesDir, "shared")
            scanner = LibraryScanner(File(shared, MUSIC_DIR), File(context.filesDir, "library-index.tsv"))
            playlistStore = PlaylistStore(File(shared, PLAYLIST_DIR))
        }
        // Always rescan: unchanged files come from the cache, so this is cheap, and it also
        // catches files copied in over USB, which Tool Manager never hears about.
        dirty = false
        io.launch {
            _library.value = scanner!!.scan()
            _playlists.value = playlistStore!!.list()
            _loading.value = false
        }
    }

    // Playlists

    fun createPlaylist(name: String): Playlist {
        val taken = _playlists.value.map { it.name.lowercase() }.toSet()
        var unique = PlaylistStore.safeName(name)
        var n = 2
        while (unique.lowercase() in taken) unique = "${PlaylistStore.safeName(name)} ${n++}"
        return Playlist(unique, emptyList()).also(::savePlaylist)
    }

    fun savePlaylist(playlist: Playlist) {
        _playlists.value = (_playlists.value.filter { it.name != playlist.name } + playlist)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        io.launch { playlistStore?.save(playlist) }
    }

    fun addToPlaylist(name: String, track: Track) {
        val p = _playlists.value.firstOrNull { it.name == name } ?: return
        savePlaylist(p.copy(paths = p.paths + track.path))
    }

    fun deletePlaylist(name: String) {
        _playlists.value = _playlists.value.filter { it.name != name }
        io.launch { playlistStore?.delete(name) }
    }

    fun playlist(name: String): Playlist? = _playlists.value.firstOrNull { it.name == name }

    // Player

    private var player: LightAudioPlayer? = null

    private val _playerFlow = MutableStateFlow<LightAudioPlayer?>(null)
    /** The live player handle, or null when not connected. Screens observe its flows. */
    val playerFlow: StateFlow<LightAudioPlayer?> = _playerFlow

    /**
     * Connect to the detached player. Every screen shares one handle (only one may exist per
     * process). Screens connect when shown and [disconnect] when the tool is backgrounded.
     * Releasing the handle does not stop the music.
     */
    fun connect(host: SealedLightActivity): LightAudioPlayer {
        player?.let { return it }
        val p = DefaultLightAudio(host).newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        player = p
        _playerFlow.value = p
        return p
    }

    fun disconnect() {
        player?.release()
        player = null
        _playerFlow.value = null
    }

    fun play(host: SealedLightActivity, tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val p = connect(host)
        val order = if (shuffle) tracks.shuffled() else tracks
        val start = if (shuffle) 0 else startIndex.coerceIn(0, tracks.lastIndex)
        main.launch {
            if (!p.awaitReady()) return@launch
            p.setMediaQueue(order.map(Track::toAudioItem), start)
            p.play()
        }
    }

    /** The track playing now, looked up from the player's queue by id (its path). */
    fun trackFor(id: String?): Track? = id?.let { path -> _library.value.tracks.firstOrNull { it.path == path } }
}

fun Track.toAudioItem() = LightAudioItem(
    source = LightAudioSource.FileSource(file),
    metadata = LightMediaMetadata(title = title, artist = artist, album = album, durationMs = durationMs.takeIf { it > 0 }),
    id = path,
)

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}
