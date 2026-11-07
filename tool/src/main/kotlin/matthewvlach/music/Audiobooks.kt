package com.matthewvlach.music

import android.media.MediaMetadataRetriever
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One audio file of a book. */
data class BookPart(val file: File, val title: String, val durationMs: Long)

/** A chapter: either a whole file, or a marker inside one. [startMs] is within its part. */
data class BookChapter(val title: String, val partIndex: Int, val startMs: Long)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val parts: List<BookPart>,
    val chapters: List<BookChapter>,
) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }

    /** Index of the chapter playing at [positionMs] in part [partIndex]. */
    fun chapterAt(partIndex: Int, positionMs: Long): Int {
        val i = chapters.indexOfLast { it.partIndex < partIndex || (it.partIndex == partIndex && it.startMs <= positionMs) }
        return i.coerceAtLeast(0)
    }

    /** Where chapter [i] ends, within its own part. */
    fun chapterEndMs(i: Int): Long {
        val c = chapters[i]
        val next = chapters.getOrNull(i + 1)
        return if (next != null && next.partIndex == c.partIndex) next.startMs else parts[c.partIndex].durationMs
    }
}

data class BookProgress(val partIndex: Int, val positionMs: Long, val finished: Boolean)

const val BOOK_ID_PREFIX = "book:"

/**
 * Audiobooks live in shared/audiobooks. Each folder is one book (its files are the parts, in
 * track or file-name order), and a single file on its own is a book too. Chapter markers inside
 * .m4b and .mp3 files are read, so one long file still shows its chapters.
 */
object Audiobooks {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _progress = MutableStateFlow<Map<String, BookProgress>>(emptyMap())
    val progress: StateFlow<Map<String, BookProgress>> = _progress

    private lateinit var root: File
    private lateinit var cacheFile: File
    private lateinit var progressFile: File
    private var started = false
    private var lastSave = 0L

    fun refresh(context: SealedLightContext) {
        if (!started) {
            started = true
            root = File(File(context.filesDir, "shared"), BOOK_DIR)
            cacheFile = File(context.filesDir, "audiobook-index.tsv")
            progressFile = File(context.filesDir, "audiobook-progress.tsv")
            trackProgress()
        }
        io.launch {
            if (_progress.value.isEmpty()) loadProgress()
            _books.value = scan()
            _loading.value = false
        }
    }

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** Parse a player queue id like "book:<id>:<part>". */
    fun parseId(queueId: String?): Pair<String, Int>? {
        val rest = queueId?.takeIf { it.startsWith(BOOK_ID_PREFIX) }?.removePrefix(BOOK_ID_PREFIX) ?: return null
        val id = rest.substringBeforeLast(':')
        val part = rest.substringAfterLast(':').toIntOrNull() ?: return null
        return id to part
    }

    // Playback

    /** Play from where you left off, or from chapter [chapter] if given. */
    fun play(host: SealedLightActivity, book: Book, chapter: Int? = null) {
        if (book.parts.isEmpty()) return
        val saved = _progress.value[book.id]
        val (part, pos) = when {
            chapter != null -> book.chapters[chapter].let { it.partIndex to it.startMs }
            saved != null && !saved.finished -> saved.partIndex.coerceIn(0, book.parts.lastIndex) to saved.positionMs
            else -> 0 to 0L
        }
        if (saved?.finished == true && chapter == null) setProgress(book.id, BookProgress(0, 0, false))
        val items = book.parts.mapIndexed { i, p ->
            LightAudioItem(
                source = LightAudioSource.FileSource(p.file),
                metadata = LightMediaMetadata(
                    title = book.title,
                    artist = book.author.ifBlank { null },
                    album = book.title,
                    durationMs = p.durationMs.takeIf { it > 0 },
                ),
                id = "$BOOK_ID_PREFIX${book.id}:$i",
            )
        }
        val p = Music.connect(host)
        main.launch {
            if (!p.awaitReady()) return@launch
            p.setMediaQueue(items, part)
            if (pos > 0) p.seekTo(pos)
            p.speed = Podcasts.speed.value
            p.play()
        }
    }

    fun setFinished(book: Book, finished: Boolean) {
        setProgress(book.id, if (finished) BookProgress(book.parts.lastIndex, 0, true) else BookProgress(0, 0, false))
        saveProgress(force = true)
    }

    private fun trackProgress() {
        main.launch {
            Music.playerFlow.collectLatest { p ->
                if (p == null) return@collectLatest
                combine(p.positionMs, p.durationMs, p.currentMediaItemIndex, p.queue) { pos, dur, i, q ->
                    Triple(q.getOrNull(i)?.id, pos, dur) to q.size
                }.collect { (state, queueSize) ->
                    val (queueId, pos, dur) = state
                    val (id, part) = parseId(queueId) ?: return@collect
                    if (pos < 2_000 && part == 0) return@collect
                    val finished = part == queueSize - 1 && dur > 0 && pos >= dur - 30_000
                    setProgress(id, BookProgress(part, pos, finished))
                    saveProgress(force = false)
                }
            }
        }
    }

    /** Save where the current book is right now (called when the tool goes to the background). */
    fun recordNow(p: com.thelightphone.sdk.audio.LightAudioPlayer) {
        val q = p.queue.value
        val (id, part) = parseId(q.getOrNull(p.currentMediaItemIndex.value)?.id) ?: return
        val pos = p.positionMs.value
        val dur = p.durationMs.value
        if (pos < 2_000 && part == 0) return
        setProgress(id, BookProgress(part, pos, part == q.size - 1 && dur > 0 && pos >= dur - 30_000))
        saveProgress(force = true)
    }

    private fun setProgress(id: String, p: BookProgress) {
        _progress.value = _progress.value + (id to p)
    }

    fun saveProgress(force: Boolean = true) {
        if (!::progressFile.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 10_000) return
        lastSave = now
        val snapshot = _progress.value
        io.launch {
            progressFile.writeText(snapshot.entries.joinToString("\n") { (k, v) ->
                "$k\t${v.partIndex}\t${v.positionMs}\t${if (v.finished) 1 else 0}"
            })
        }
    }

    private fun loadProgress() {
        if (!progressFile.exists()) return
        _progress.value = progressFile.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) null
            else f[0] to BookProgress(f[1].toIntOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[3] == "1")
        }.toMap()
    }

    // Scanning

    private class FileInfo(
        val title: String?, val artist: String?, val album: String?,
        val track: Int, val disc: Int, val durationMs: Long, val chapters: List<Chapter>,
    )

    private fun scan(): List<Book> {
        root.mkdirs()
        val cache = readCache()
        val fresh = mutableMapOf<String, Pair<String, FileInfo>>()

        fun info(file: File): FileInfo {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val stamp = "${file.length()}:${file.lastModified()}"
            val hit = cache[path]?.takeIf { it.first == stamp }?.second ?: readInfo(file)
            fresh[path] = stamp to hit
            return hit
        }

        val entries = root.listFiles().orEmpty().sortedWith(compareBy(naturalOrder) { it.name })
        val books = entries.mapNotNull { entry ->
            val files = if (entry.isDirectory) entry.walkTopDown().filter { it.isAudio() }.toList()
            else listOf(entry).filter { it.isAudio() }
            if (files.isEmpty()) return@mapNotNull null
            val infos = files.map { it to info(it) }
                .sortedWith(compareBy<Pair<File, FileInfo>>({ it.second.disc }, { it.second.track })
                    .thenComparing({ it.first.relativeTo(root).invariantSeparatorsPath }, naturalOrder))
            buildBook(entry, infos)
        }
        writeCache(fresh)
        return books.sortedWith(compareBy(naturalOrder) { it.title })
    }

    private fun buildBook(entry: File, files: List<Pair<File, FileInfo>>): Book {
        val first = files.first().second
        val folderName = if (entry.isDirectory) entry.name else entry.nameWithoutExtension
        val single = files.size == 1
        val parts = files.map { (f, i) ->
            BookPart(f, i.title ?: cleanName(f.nameWithoutExtension), i.durationMs)
        }
        val chapters = mutableListOf<BookChapter>()
        files.forEachIndexed { idx, (_, info) ->
            val inside = info.chapters
            if (inside.size > 1 || (single && inside.isNotEmpty())) {
                inside.forEachIndexed { n, c ->
                    chapters += BookChapter(c.title.ifBlank { "Chapter ${chapters.size + 1}" }, idx, c.startMs)
                    if (n == 0 && c.startMs > 0) {
                        // Audio before the first marker: start the part at 0 under the part's name.
                        chapters.add(chapters.size - 1, BookChapter(parts[idx].title, idx, 0))
                    }
                }
            } else {
                chapters += BookChapter(parts[idx].title, idx, 0)
            }
        }
        return Book(
            id = hash(entry.relativeTo(root).invariantSeparatorsPath),
            title = first.album ?: (if (single) first.title else null) ?: cleanName(folderName),
            author = first.artist.orEmpty(),
            parts = parts,
            chapters = chapters,
        )
    }

    private fun readInfo(file: File): FileInfo {
        val tags = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    fun t(k: Int) = extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
                    listOf(
                        t(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        t(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: t(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: t(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                        t(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        t(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        t(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                        t(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    )
                } finally {
                    release()
                }
            }
        }.getOrDefault(List(6) { null })
        fun lead(s: String?) = s?.takeWhile { it.isDigit() }?.toIntOrNull()
        return FileInfo(
            title = tags[0], artist = tags[1], album = tags[2],
            track = lead(tags[3]) ?: 0, disc = lead(tags[4]) ?: 1,
            durationMs = tags[5]?.toLongOrNull() ?: 0, chapters = ChapterReader.read(file),
        )
    }

    // Cache: path, stamp, title, artist, album, track, disc, duration, chapters ("ms|title" joined by \u001F)
    private fun readCache(): Map<String, Pair<String, FileInfo>> = runCatching {
        if (!cacheFile.isFile) return emptyMap()
        cacheFile.readLines().mapNotNull { line ->
            val c = line.split('\t')
            if (c.size != 9) return@mapNotNull null
            val chapters = c[8].split('\u001F').filter { it.isNotEmpty() }.mapNotNull {
                val ms = it.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                Chapter(it.substringAfter('|'), ms)
            }
            c[0] to (c[1] to FileInfo(
                c[2].ifEmpty { null }, c[3].ifEmpty { null }, c[4].ifEmpty { null },
                c[5].toIntOrNull() ?: 0, c[6].toIntOrNull() ?: 1, c[7].toLongOrNull() ?: 0, chapters,
            ))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeCache(entries: Map<String, Pair<String, FileInfo>>) {
        fun clean(s: String?) = s.orEmpty().replace(Regex("[\\t\\n\\u001F|]"), " ")
        runCatching {
            cacheFile.writeText(entries.entries.joinToString("\n") { (path, v) ->
                val (stamp, i) = v
                listOf(
                    path, stamp, clean(i.title), clean(i.artist), clean(i.album), i.track.toString(),
                    i.disc.toString(), i.durationMs.toString(),
                    i.chapters.joinToString("\u001F") { "${it.startMs}|${clean(it.title)}" },
                ).joinToString("\t")
            })
        }
    }

    private fun File.isAudio() = isFile && extension.lowercase() in BOOK_EXTENSIONS

    private fun cleanName(s: String) =
        s.replace('_', ' ').replace(Regex("""^\d{1,3}[\s._-]+"""), "").trim().ifEmpty { s }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val BOOK_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav")

    /** "Part 2" before "Part 10". */
    private val naturalOrder = Comparator<String> { a, b ->
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }
}
