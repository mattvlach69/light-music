package com.matthewvlach.music

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import kotlinx.coroutines.flow.StateFlow

const val MUSIC_DIR = "music"
const val PLAYLIST_DIR = "playlists"
const val PODCAST_DIR = "podcasts"
const val BOOK_DIR = "audiobooks"

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override fun getToolManagerManifest(): ClientToolManifest = ClientToolManifest(
        title = "Music",
        roots = listOf(
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Songs",
                    path = MUSIC_DIR,
                    headerText = "Add MP3, M4A, FLAC, OGG or WAV files. Tags are used for artist and album. " +
                        "Untagged files can sit in Artist/Album folders instead.",
                )
            ),
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Playlists",
                    path = PLAYLIST_DIR,
                    headerText = "Playlists are .m3u files. One song path per line, relative to Songs.",
                )
            ),
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Podcasts",
                    path = PODCAST_DIR,
                    headerText = "subscriptions.txt lists your shows: one podcast feed (RSS) URL per line.",
                )
            ),
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Audiobooks",
                    path = BOOK_DIR,
                    headerText = "One folder per book. A single .m4b or .mp3 file works too, and its chapters are read.",
                )
            ),
        ),
    )

    // Files changed from the computer. Rescan next time the tool opens.
    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        Music.markDirty()
    }

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit
    override suspend fun onPushNotification(data: ByteArray) = Unit
}
