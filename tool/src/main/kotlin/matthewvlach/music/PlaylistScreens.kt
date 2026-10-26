package com.matthewvlach.music

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class PlaylistsScreen(host: SealedLightActivity) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val playlists by Music.playlists.collectAsState()
        Frame("Playlists", onBack = { goBack() }) {
            RowList {
                item {
                    ListRow("New playlist", dim = true) {
                        navigateTo({ NameEditorScreen(it, "New playlist") }) { name ->
                            if (!name.isNullOrBlank()) {
                                val created = Music.createPlaylist(name)
                                navigateTo({ PlaylistScreen(it, created.name) })
                            }
                        }
                    }
                }
                items(playlists) { p ->
                    ListRow(p.name, plural(library.resolve(p).size, "song")) {
                        navigateTo({ PlaylistScreen(it, p.name) })
                    }
                }
            }
        }
    }
}

class PlaylistScreen(host: SealedLightActivity, private val name: String) : TrackListScreen(host) {
    @Composable
    override fun Content() {
        val library by Music.library.collectAsState()
        val playlists by Music.playlists.collectAsState()
        val playlist = playlists.firstOrNull { it.name == name }
        val tracks = playlist?.let(library::resolve).orEmpty()
        var editing by remember { mutableStateOf(false) }

        val bottom = when {
            editing -> listOf(
                LightBarButton.Text("DELETE", onClick = {
                    Music.deletePlaylist(name)
                    goBack()
                }),
                LightBarButton.Text("DONE", onClick = { editing = false }),
            )
            tracks.isEmpty() -> listOf(LightBarButton.Text("EDIT", onClick = { editing = true }))
            else -> playBar(tracks) + LightBarButton.Text("EDIT", onClick = { editing = true })
        }

        Frame(name, onBack = { goBack() }, bottom = bottom) {
            if (tracks.isEmpty()) {
                Message(
                    if (editing) "Tap DELETE to remove this playlist."
                    else "Empty playlist.\n\nWhile a song plays, tap + on Now playing to add it here.",
                )
                return@Frame
            }
            RowList {
                itemsIndexed(tracks) { i, t ->
                    ListRow(
                        primary = if (editing) "Remove: ${t.title}" else t.title,
                        secondary = t.artist,
                    ) {
                        if (editing) removeRow(playlist!!, tracks, i) else playFrom(tracks, i)
                    }
                }
            }
        }
    }

    /** Remove the i-th visible song, keeping entries for files that are missing right now. */
    private fun removeRow(playlist: Playlist, visible: List<Track>, i: Int) {
        val path = visible[i].path
        // The same song may appear more than once: remove the occurrence that matches this row.
        val occurrence = visible.take(i).count { it.path == path }
        var seen = 0
        val idx = playlist.paths.indexOfFirst { it == path && seen++ == occurrence }
        if (idx >= 0) Music.savePlaylist(playlist.copy(paths = playlist.paths.toMutableList().apply { removeAt(idx) }))
    }
}

/** Choose a playlist for one song. Returns to Now playing when done. */
class AddToPlaylistScreen(host: SealedLightActivity, private val track: Track) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val playlists by Music.playlists.collectAsState()
        Frame("Add to playlist", onBack = { goBack() }) {
            RowList {
                item {
                    ListRow("New playlist", dim = true) {
                        navigateTo({ NameEditorScreen(it, "New playlist") }) { name ->
                            if (!name.isNullOrBlank()) {
                                val created = Music.createPlaylist(name)
                                Music.addToPlaylist(created.name, track)
                                goBack()
                            }
                        }
                    }
                }
                items(playlists) { p ->
                    val already = track.path in p.paths
                    ListRow(p.name, if (already) "Already has this song" else null) {
                        Music.addToPlaylist(p.name, track)
                        goBack()
                    }
                }
            }
        }
    }
}

/** Full-screen keyboard for naming a playlist. Returns the text, or null on back. */
class NameEditorScreen(host: SealedLightActivity, private val title: String) : SimpleLightScreen<String?>(host) {
    @Composable
    override fun Content() {
        val keyboard = rememberKeyboardOptions()
        val text = rememberTextFieldState("")
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = title,
                state = text,
                keyboardOptionsFlow = keyboard,
                onSubmit = { goBack(it.toString().trim()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                singleLine = true,
                initialCaps = true,
            )
        }
    }
}
