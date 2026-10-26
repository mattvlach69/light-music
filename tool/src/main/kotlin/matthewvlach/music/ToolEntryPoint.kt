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
