package com.matthewvlach.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/** Your shows, plus a way to find new ones. */
class PodcastsScreen(host: SealedLightActivity) : MusicScreen(host) {

    override fun willShow() {
        super.willShow()
        Podcasts.init(lightContext)
    }

    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val refreshing by Podcasts.refreshing.collectAsState()
        Frame(
            "Podcasts",
            onBack = { goBack() },
            right = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = { Podcasts.refreshAll() }),
        ) {
            RowList {
                item {
                    ListRow("Find a show", if (refreshing) "Checking for new episodes…" else null, dim = true) {
                        navigateTo({ NameEditorScreen(it, "Search podcasts", submitLabel = "SEARCH") }) { term ->
                            if (!term.isNullOrBlank()) navigateTo({ SearchResultsScreen(it, term) })
                        }
                    }
                }
                items(shows) { show ->
                    val latest = show.episodes.firstOrNull()
                    ListRow(show.title, latest?.let { "Latest: ${formatDate(it.pubDate)}" } ?: show.author) {
                        navigateTo({ ShowScreen(it, show.id) })
                    }
                }
            }
        }
    }
}

/** Search Apple's podcast directory, or paste a feed URL directly. Tap a result to follow it. */
class SearchResultsScreen(host: SealedLightActivity, private val term: String) : MusicScreen(host) {
    @Composable
    override fun Content() {
        var results by remember { mutableStateOf<List<SearchResult>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val isUrl = term.startsWith("http://") || term.startsWith("https://")

        fun follow(feedUrl: String, title: String) {
            adding = title
            error = null
            scope.launch {
                Podcasts.subscribe(feedUrl)
                    .onSuccess { show ->
                        adding = null
                        goBack()
                        navigateTo({ ShowScreen(it, show.id) })
                    }
                    .onFailure {
                        adding = null
                        error = "Couldn't load that show. Check the connection and try again."
                    }
            }
        }

        LaunchedEffect(term) {
            if (isUrl) follow(term, "that feed")
            else Podcasts.search(term)
                .onSuccess { results = it }
                .onFailure { error = "Couldn't search. Check the connection and try again." }
        }

        Frame(term.take(40), onBack = { goBack() }) {
            val list = results
            when {
                adding != null -> Message("Adding $adding…")
                error != null -> Message(error!!)
                isUrl -> Message("Loading…")
                list == null -> Message("Searching…")
                list.isEmpty() -> Message("No shows found.")
                else -> RowList {
                    items(list) { r ->
                        ListRow(r.title, r.author) { follow(r.feedUrl, r.title) }
                    }
                }
            }
        }
    }
}

/** One show's episodes, newest first. */
class ShowScreen(host: SealedLightActivity, private val showId: String) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val progress by Podcasts.progress.collectAsState()
        val downloaded by Podcasts.downloaded.collectAsState()
        val downloading by Podcasts.downloading.collectAsState()
        var confirmRemove by remember { mutableStateOf(false) }
        val show = shows.firstOrNull { it.id == showId }

        Frame(
            show?.title ?: "Show",
            onBack = { goBack() },
            bottom = listOf(
                LightBarButton.Text(if (confirmRemove) "TAP TO CONFIRM" else "UNFOLLOW", onClick = {
                    if (confirmRemove) {
                        Podcasts.unsubscribe(showId)
                        goBack()
                    } else confirmRemove = true
                }),
            ),
        ) {
            if (show == null) {
                Message("This show isn't followed anymore.")
                return@Frame
            }
            if (show.episodes.isEmpty()) {
                Message("No episodes yet.")
                return@Frame
            }
            RowList {
                items(show.episodes) { e ->
                    val p = progress[e.key]
                    val detail = listOfNotNull(
                        formatDate(e.pubDate).takeIf { it.isNotEmpty() },
                        episodeStatus(e, p),
                        when {
                            e.key in downloading -> "downloading ${((downloading[e.key] ?: 0f) * 100).toInt()}%"
                            e.key in downloaded -> "saved"
                            else -> null
                        },
                    ).joinToString(" · ")
                    ListRow(e.title, detail, dim = p?.played == true) {
                        navigateTo({ EpisodeScreen(it, showId, e.key) })
                    }
                }
            }
        }
    }
}

private fun episodeStatus(e: Episode, p: EpisodeProgress?): String? = when {
    p?.played == true -> "played"
    p != null && p.positionMs > 0 && p.durationMs > 0 -> "${formatDuration(p.durationMs - p.positionMs)} left"
    e.durationMs > 0 -> formatDuration(e.durationMs)
    else -> null
}

/** Play, save for offline, or mark one episode played. */
class EpisodeScreen(
    host: SealedLightActivity,
    private val showId: String,
    private val key: String,
) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val progress by Podcasts.progress.collectAsState()
        val downloaded by Podcasts.downloaded.collectAsState()
        val downloading by Podcasts.downloading.collectAsState()
        val episode = shows.firstOrNull { it.id == showId }?.episodes?.firstOrNull { it.key == key }
        val p = progress[key]
        val resumable = p != null && !p.played && p.positionMs > 0

        Frame(
            episode?.showTitle ?: "Episode",
            onBack = { goBack() },
            bottom = episode?.let { e ->
                listOf(
                    LightBarButton.Text(if (resumable) "RESUME" else "PLAY", onClick = {
                        Podcasts.play(host, e)
                        navigateTo({ NowPlayingScreen(it) })
                    }),
                )
            },
        ) {
            if (episode == null) {
                Message("This episode isn't available anymore.")
                return@Frame
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
            ) {
                LightText(episode.title, LightTextVariant.Heading, maxLines = 4, overflow = TextOverflow.Ellipsis)
                LightText(
                    listOfNotNull(
                        formatDate(episode.pubDate).takeIf { it.isNotEmpty() },
                        episodeStatus(episode, p),
                    ).joinToString(" · "),
                    LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                )

                val fraction = downloading[key]
                when {
                    fraction != null -> ListRow("Downloading ${(fraction * 100).toInt()}%", dim = true) {}
                    key in downloaded -> ListRow("Remove download", "Saved on the phone") { Podcasts.deleteDownload(episode) }
                    else -> ListRow("Download", "Listen without a connection") { Podcasts.download(episode) }
                }
                if (p?.played == true) {
                    ListRow("Mark as unplayed") { Podcasts.setPlayed(episode, false) }
                } else {
                    ListRow("Mark as played") { Podcasts.setPlayed(episode, true) }
                }

                if (episode.description.isNotBlank()) {
                    LightText(
                        episode.description,
                        LightTextVariant.Paragraph,
                        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp(), bottom = 2f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
