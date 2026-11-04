package com.matthewvlach.music

import android.util.Xml
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

data class Episode(
    val key: String,
    val showId: String,
    val showTitle: String,
    val title: String,
    val audioUrl: String,
    val pubDate: Long,
    val durationMs: Long,
    val description: String,
)

data class Show(
    val id: String,
    val feedUrl: String,
    val title: String,
    val author: String,
    val episodes: List<Episode>,
)

data class EpisodeProgress(val positionMs: Long, val durationMs: Long, val played: Boolean)

data class SearchResult(val title: String, val author: String, val feedUrl: String)

/** Item ids in the player queue start with this, so podcasts and songs can share one player. */
const val PODCAST_ID_PREFIX = "pod:"

/**
 * Podcast subscriptions, episodes, downloads and listening progress.
 *
 * Subscriptions live in shared/podcasts/subscriptions.txt (one feed URL per line), so they can
 * also be edited from the computer. Feeds are cached so shows open offline.
 */
object Podcasts {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _shows = MutableStateFlow<List<Show>>(emptyList())
    val shows: StateFlow<List<Show>> = _shows

    private val _progress = MutableStateFlow<Map<String, EpisodeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, EpisodeProgress>> = _progress

    /** Episode key -> download fraction (0..1) while downloading. */
    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    private lateinit var subsFile: File
    private lateinit var feedDir: File
    private lateinit var audioDir: File
    private lateinit var progressFile: File
    private var lastRefresh = 0L
    private var lastProgressSave = 0L
    private var started = false

    fun init(context: SealedLightContext) {
        if (started) {
            if (System.currentTimeMillis() - lastRefresh > 60 * 60 * 1000) refreshAll()
            return
        }
        started = true
        val shared = File(context.filesDir, "shared")
        subsFile = File(File(shared, PODCAST_DIR).apply { mkdirs() }, "subscriptions.txt")
        feedDir = File(context.filesDir, "podcast-feeds").apply { mkdirs() }
        audioDir = File(context.filesDir, "podcast-audio").apply { mkdirs() }
        progressFile = File(context.filesDir, "podcast-progress.tsv")
        io.launch {
            loadProgress()
            loadCached()
            refreshAll()
        }
        trackProgress()
    }

    // Subscriptions

    private fun subscriptions(): List<String> =
        if (subsFile.exists()) subsFile.readLines().map(String::trim).filter { it.startsWith("http") }.distinct()
        else emptyList()

    private fun writeSubscriptions(urls: List<String>) {
        subsFile.writeText(
            "# One podcast feed (RSS) URL per line.\n" + urls.joinToString("\n", postfix = "\n")
        )
    }

    /** Fetches the feed first, so a bad URL fails here instead of leaving a broken show. */
    suspend fun subscribe(feedUrl: String): Result<Show> = withContext(Dispatchers.IO) {
        runCatching {
            val show = fetchShow(feedUrl)
            val urls = subscriptions()
            if (feedUrl !in urls) writeSubscriptions(urls + feedUrl)
            _shows.value = (_shows.value.filter { it.id != show.id } + show).sortedBy { it.title.lowercase() }
            show
        }
    }

    fun unsubscribe(id: String) {
        val show = show(id) ?: return
        _shows.value = _shows.value.filter { it.id != id }
        io.launch {
            writeSubscriptions(subscriptions().filter { it != show.feedUrl })
            File(feedDir, "$id.xml").delete()
            show.episodes.forEach { downloadFile(it)?.delete() }
            refreshDownloaded()
        }
    }

    fun show(id: String): Show? = _shows.value.firstOrNull { it.id == id }

    fun episode(showId: String, key: String): Episode? = show(showId)?.episodes?.firstOrNull { it.key == key }

    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        lastRefresh = System.currentTimeMillis()
        io.launch {
            try {
                val fresh = subscriptions().mapNotNull { url ->
                    runCatching { fetchShow(url) }.getOrNull()
                        ?: _shows.value.firstOrNull { it.feedUrl == url }
                }
                if (fresh.isNotEmpty() || subscriptions().isEmpty()) {
                    _shows.value = fresh.sortedBy { it.title.lowercase() }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun loadCached() {
        _shows.value = subscriptions().mapNotNull { url ->
            val f = File(feedDir, "${idFor(url)}.xml")
            if (f.exists()) runCatching { f.inputStream().use { parseFeed(url, it) } }.getOrNull() else null
        }.sortedBy { it.title.lowercase() }
        refreshDownloaded()
    }

    private fun fetchShow(feedUrl: String): Show {
        val bytes = httpGet(feedUrl).use { it.readBytes() }
        val show = bytes.inputStream().use { parseFeed(feedUrl, it) }
        File(feedDir, "${show.id}.xml").writeBytes(bytes)
        return show
    }

    // Search (Apple's public podcast directory, no account needed)

    suspend fun search(term: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(term, "UTF-8")
            val json = httpGet("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$q")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val results = JSONObject(json).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).mapNotNull { i ->
                val r = results.getJSONObject(i)
                val feed = r.optString("feedUrl")
                if (feed.isBlank()) null
                else SearchResult(r.optString("collectionName"), r.optString("artistName"), feed)
            }
        }
    }

    // Downloads

    private fun downloadFile(e: Episode): File? {
        if (!::audioDir.isInitialized) return null
        val ext = e.audioUrl.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase()
            .takeIf { it.all(Char::isLetterOrDigit) && it.isNotEmpty() } ?: "mp3"
        return File(audioDir, "${e.key}.$ext")
    }

    private fun refreshDownloaded() {
        _downloaded.value = audioDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .map { it.nameWithoutExtension }.toSet()
    }

    fun download(e: Episode) {
        if (e.key in _downloading.value) return
        _downloading.value = _downloading.value + (e.key to 0f)
        io.launch {
            val target = downloadFile(e)!!
            val part = File(target.path + ".part")
            try {
                val conn = open(e.audioUrl)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastTick = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastTick > 500) {
                                lastTick = now
                                _downloading.value = _downloading.value + (e.key to done.toFloat() / total)
                            }
                        }
                    }
                }
                part.renameTo(target)
            } catch (t: Throwable) {
                part.delete()
            } finally {
                _downloading.value = _downloading.value - e.key
                refreshDownloaded()
            }
        }
    }

    fun deleteDownload(e: Episode) {
        io.launch {
            downloadFile(e)?.delete()
            refreshDownloaded()
        }
    }

    // Playback

    fun play(host: SealedLightActivity, e: Episode) {
        val p = Music.connect(host)
        val file = downloadFile(e)?.takeIf { it.exists() }
        val saved = _progress.value[e.key]
        val start = if (saved == null || saved.played) 0L else saved.positionMs
        if (saved?.played == true) setProgress(e.key, EpisodeProgress(0, saved.durationMs, played = false))
        val item = LightAudioItem(
            source = file?.let { LightAudioSource.FileSource(it) } ?: LightAudioSource.UrlSource(e.audioUrl),
            metadata = LightMediaMetadata(
                title = e.title,
                artist = e.showTitle,
                album = null,
                durationMs = e.durationMs.takeIf { it > 0 },
            ),
            id = PODCAST_ID_PREFIX + e.key,
        )
        main.launch {
            if (!p.awaitReady()) return@launch
            p.setMediaQueue(listOf(item), 0)
            if (start > 0) p.seekTo(start)
            p.speed = _speed.value
            p.play()
        }
    }

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_speed.value) + 1) % SPEEDS.size]
        _speed.value = next
        Music.playerFlow.value?.speed = next
    }

    fun setPlayed(e: Episode, played: Boolean) {
        val old = _progress.value[e.key]
        setProgress(e.key, EpisodeProgress(if (played) old?.positionMs ?: 0 else 0, old?.durationMs ?: e.durationMs, played))
        saveProgress(force = true)
    }

    fun episodeFor(queueId: String?): Episode? {
        val key = queueId?.takeIf { it.startsWith(PODCAST_ID_PREFIX) }?.removePrefix(PODCAST_ID_PREFIX) ?: return null
        return _shows.value.asSequence().flatMap { it.episodes }.firstOrNull { it.key == key }
    }

    /** Remember where each episode was left, while the tool is open. */
    private fun trackProgress() {
        main.launch {
            Music.playerFlow.collectLatest { p ->
                if (p == null) return@collectLatest
                combine(p.positionMs, p.durationMs, p.currentMediaItemIndex, p.queue) { pos, dur, i, q ->
                    Triple(q.getOrNull(i)?.id, pos, dur)
                }.collect { (id, pos, dur) ->
                    val key = id?.takeIf { it.startsWith(PODCAST_ID_PREFIX) }?.removePrefix(PODCAST_ID_PREFIX)
                        ?: return@collect
                    if (pos < 2_000) return@collect
                    val played = dur > 0 && pos >= dur - 30_000
                    setProgress(key, EpisodeProgress(pos, dur, played))
                    saveProgress(force = false)
                }
            }
        }
    }

    private fun setProgress(key: String, p: EpisodeProgress) {
        _progress.value = _progress.value + (key to p)
    }

    fun saveProgress(force: Boolean = true) {
        if (!::progressFile.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressSave < 10_000) return
        lastProgressSave = now
        val snapshot = _progress.value
        io.launch {
            progressFile.writeText(snapshot.entries.joinToString("\n") { (k, v) ->
                "$k\t${v.positionMs}\t${v.durationMs}\t${if (v.played) 1 else 0}"
            })
        }
    }

    private fun loadProgress() {
        if (!progressFile.exists()) return
        _progress.value = progressFile.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) null
            else f[0] to EpisodeProgress(f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[3] == "1")
        }.toMap()
    }

    // Networking

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "LightMusic/1.1 (Light Phone III)")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: error("Redirect without a location")
                conn.disconnect()
                current = URL(URL(current), next).toString()
            } else {
                if (code !in 200..299) error("HTTP $code")
                return conn
            }
        }
        error("Too many redirects")
    }

    private fun httpGet(url: String): InputStream = open(url).inputStream

    // Feed parsing

    private fun parseFeed(feedUrl: String, input: InputStream): Show {
        val id = idFor(feedUrl)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        var title = ""
        var author = ""
        val items = mutableListOf<MutableMap<String, String>>()
        var item: MutableMap<String, String>? = null
        var skipDepth = -1 // inside <image> or similar, where <title> is not the show's
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when {
                        name == "item" || name == "entry" -> item = mutableMapOf()
                        skipDepth >= 0 -> Unit
                        item == null && (name == "image" || name == "itunes:owner") -> skipDepth = parser.depth
                        item == null && name == "title" && title.isEmpty() -> title = parser.nextText().trim()
                        item == null && name == "itunes:author" && author.isEmpty() -> author = parser.nextText().trim()
                        item != null -> {
                            val cur = item
                            when (name) {
                                "enclosure" -> parser.getAttributeValue(null, "url")?.let { cur["url"] = it }
                                "title", "guid", "pubDate", "itunes:duration", "description",
                                "itunes:summary", "content:encoded" -> {
                                    val text = parser.nextText().trim()
                                    if (!cur.containsKey(name)) cur[name] = text
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((parser.name == "item" || parser.name == "entry") && item != null) {
                        items += item
                        item = null
                    } else if (skipDepth >= 0 && parser.depth == skipDepth) {
                        skipDepth = -1
                    }
                }
            }
        }
        val showTitle = title.ifBlank { "Untitled show" }
        val episodes = items.mapNotNull { m ->
            val url = m["url"] ?: return@mapNotNull null
            val guid = m["guid"]?.takeIf { it.isNotBlank() } ?: url
            Episode(
                key = hash("$feedUrl\n$guid"),
                showId = id,
                showTitle = showTitle,
                title = m["title"].orEmpty().ifBlank { "Untitled episode" },
                audioUrl = url,
                pubDate = parseDate(m["pubDate"]),
                durationMs = parseDuration(m["itunes:duration"]),
                description = stripHtml(m["description"] ?: m["itunes:summary"] ?: m["content:encoded"] ?: ""),
            )
        }.sortedByDescending { it.pubDate }
        return Show(id, feedUrl, showTitle, author, episodes)
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        for (f in DATE_FORMATS) {
            runCatching { return SimpleDateFormat(f, Locale.US).parse(s.trim())!!.time }
        }
        return 0
    }

    private fun parseDuration(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(':').map { it.toDoubleOrNull() ?: return 0 }
        val seconds = parts.fold(0.0) { acc, p -> acc * 60 + p }
        return (seconds * 1000).toLong()
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .take(4000)

    private fun idFor(feedUrl: String) = hash(feedUrl)

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)
}

fun formatSpeed(s: Float): String = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

fun formatDuration(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

fun formatDate(ms: Long): String =
    if (ms <= 0) "" else SimpleDateFormat("MMM d, yyyy", Locale.US).format(ms)
