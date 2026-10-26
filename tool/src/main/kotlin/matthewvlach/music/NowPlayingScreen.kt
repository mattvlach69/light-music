package com.matthewvlach.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp

class NowPlayingScreen(host: SealedLightActivity) : MusicScreen(host) {

    @Composable
    override fun Content() {
        val player by Music.playerFlow.collectAsState()
        player?.let { Body(it) }
    }

    @Composable
    private fun Body(player: LightAudioPlayer) {
        val queue by player.queue.collectAsState()
        val index by player.currentMediaItemIndex.collectAsState()
        val playing by player.isPlaying.collectAsState()
        val position by player.positionMs.collectAsState()
        val duration by player.durationMs.collectAsState()
        val library by Music.library.collectAsState()

        val item = queue.getOrNull(index)
        val track = item?.id?.let { id -> library.tracks.firstOrNull { it.path == id } }

        val addButton = track?.let { t ->
            LightBarButton.LightIcon(LightIcons.ADD, onClick = { navigateTo({ AddToPlaylistScreen(it, t) }) })
        }

        Frame(
            title = "Now playing",
            onBack = { goBack() },
            right = addButton,
            bottom = listOf(
                LightBarButton.LightIcon(LightIcons.REWIND, onClick = {
                    // Like a CD player: restart the song, or go back if near the start.
                    if (position > 3_000) player.seekTo(0) else player.skipToPrevious()
                }),
                LightBarButton.LightIcon(
                    if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = { if (playing) player.pause() else player.play() },
                ),
                LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = { player.skipToNext() }),
            ),
        ) {
            if (item == null) {
                Message("Nothing playing.")
                return@Frame
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = item.metadata.title.ifBlank { track?.title ?: "Untitled" },
                    variant = LightTextVariant.Heading,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = item.metadata.artist ?: track?.artist.orEmpty(),
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = item.metadata.album ?: track?.album.orEmpty(),
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                LightTouchableProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = progress,
                    onValueChange = { f -> if (duration > 0) player.seekTo((f * duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 1.5f.gridUnitsAsDp()),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LightText(formatTime(position), LightTextVariant.Detail, monospace = true)
                    LightText("${index + 1} of ${queue.size}", LightTextVariant.Detail, lighten = true)
                    LightText(
                        if (duration > 0) formatTime(duration) else "--:--",
                        LightTextVariant.Detail,
                        monospace = true,
                    )
                }
            }
        }
    }
}
