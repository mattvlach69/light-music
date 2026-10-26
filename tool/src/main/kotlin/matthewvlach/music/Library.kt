package com.matthewvlach.music

import android.media.MediaMetadataRetriever
import java.io.File

/** One song. [path] is relative to the Songs folder and is its stable id. */
data class Track(
    val path: String,
    val file: File,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNo: Int,
    val discNo: Int,
    val durationMs: Long,
) {
    val albumKey: String get() = "$albumArtist\u0000$album"
}

data class Album(val key: String, val title: String, val artist: String, val tracks: List<Track>)

data class Playlist(val name: String, val paths: List<String>)

private val byName = String.CASE_INSENSITIVE_ORDER
private fun sortName(s: String) = s.removePrefix("The ").removePrefix("the ")

/** An immutable snapshot of everything on the phone, with the views the screens need. */
class LibraryIndex(val tracks: List<Track>) {
    private val byPath = tracks.associateBy { it.path }

    val songs: List<Track> = tracks.sortedWith(compareBy(byName) { it.title })

    val albums: List<Album> = tracks.groupBy { it.albumKey }.map { (key, ts) ->
        val first = ts.first()
        Album(key, first.album, first.albumArtist, ts.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title })))
    }.sortedWith(compareBy(byName) { it.title })

    val artists: List<String> = tracks.map { it.albumArtist }.distinct().sortedWith(compareBy(byName) { sortName(it) })

    fun albumsBy(artist: String): List<Album> = albums.filter { it.artist == artist }
    fun album(key: String): Album? = albums.firstOrNull { it.key == key }
    fun songsBy(artist: String): List<Track> =
        tracks.filter { it.albumArtist == artist || it.artist == artist }.sortedWith(compareBy(byName) { it.title })

    /** Playlist entries that still exist on the phone, in order. */
    fun resolve(playlist: Playlist): List<Track> = playlist.paths.mapNotNull { byPath[it] }

    companion object {
        val EMPTY = LibraryIndex(emptyList())
    }
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"

/**
 * Walks the Songs folder and reads tags. Results are cached by path, size and modified time,
 * so only new or changed files are opened on later scans.
 */
class LibraryScanner(private val musicRoot: File, private val cacheFile: File) {

    fun scan(): LibraryIndex {
        musicRoot.mkdirs()
        val cache = readCache()
        val files = musicRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .toList()
        val tracks = files.map { file ->
            val path = file.relativeTo(musicRoot).invariantSeparatorsPath
            val stamp = "${file.length()}:${file.lastModified()}"
            cache[path]?.takeIf { it.first == stamp }?.second?.copy(file = file)
                ?: readTrack(file, path)
        }
        writeCache(tracks)
        return LibraryIndex(tracks)
    }

    private fun readTrack(file: File, path: String): Track {
        val tags = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    mapOf(
                        "title" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        "artist" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        "albumArtist" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        "album" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        "track" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        "disc" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                        "duration" to extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    )
                } finally {
                    release()
                }
            }
        }.getOrDefault(emptyMap())

        // Fallback: Artist/Album/01 Title.mp3
        val parts = path.split('/')
        val folderAlbum = parts.getOrNull(parts.size - 2)
        val folderArtist = parts.getOrNull(parts.size - 3)
        val stem = file.nameWithoutExtension
        val numbered = Regex("""^(\d{1,3})[\s._-]+(.+)$""").find(stem)

        fun tag(k: String) = tags[k]?.trim()?.takeIf { it.isNotEmpty() }
        val artist = tag("artist") ?: folderArtist ?: UNKNOWN_ARTIST
        return Track(
            path = path,
            file = file,
            title = tag("title") ?: numbered?.groupValues?.get(2) ?: stem,
            artist = artist,
            album = tag("album") ?: folderAlbum ?: UNKNOWN_ALBUM,
            albumArtist = tag("albumArtist") ?: artist,
            trackNo = leadingInt(tag("track")) ?: numbered?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            discNo = leadingInt(tag("disc")) ?: 1,
            durationMs = tag("duration")?.toLongOrNull() ?: 0L,
        )
    }

    // Tab-separated, one track per line. Tabs and newlines are stripped from values.
    private fun readCache(): Map<String, Pair<String, Track>> = runCatching {
        if (!cacheFile.isFile) return emptyMap()
        cacheFile.readLines().mapNotNull { line ->
            val c = line.split('\t')
            if (c.size != 10) return@mapNotNull null
            c[0] to (c[1] to Track(
                path = c[0], file = File(musicRoot, c[0]), title = c[2], artist = c[3], album = c[4],
                albumArtist = c[5], trackNo = c[6].toIntOrNull() ?: 0, discNo = c[7].toIntOrNull() ?: 1,
                durationMs = c[8].toLongOrNull() ?: 0L,
            ))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeCache(tracks: List<Track>) {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        runCatching {
            cacheFile.writeText(tracks.joinToString("\n") { t ->
                listOf(
                    t.path, "${t.file.length()}:${t.file.lastModified()}", t.title, t.artist, t.album,
                    t.albumArtist, t.trackNo, t.discNo, t.durationMs, "",
                ).joinToString("\t") { clean(it.toString()) }
            })
        }
    }
}

private fun leadingInt(s: String?): Int? = s?.takeWhile { it.isDigit() }?.toIntOrNull()

/** Playlists are plain .m3u files, so they can be edited on the phone or from the computer. */
class PlaylistStore(private val dir: File) {

    fun list(): List<Playlist> {
        dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("m3u", "m3u8") }
            .orEmpty()
            .map { f -> Playlist(f.nameWithoutExtension, parse(f.readLines())) }
            .sortedWith(compareBy(byName) { it.name })
    }

    fun save(playlist: Playlist) {
        dir.mkdirs()
        File(dir, "${safeName(playlist.name)}.m3u").writeText(
            (listOf("#EXTM3U") + playlist.paths).joinToString("\n") + "\n"
        )
    }

    fun delete(name: String) {
        File(dir, "${safeName(name)}.m3u").delete()
        File(dir, "${safeName(name)}.m3u8").delete()
    }

    companion object {
        fun safeName(name: String) = name.replace(Regex("""[/\\:*?"<>|]"""), "-").trim().ifEmpty { "Playlist" }

        /** Accepts "Artist/Album/song.mp3", "music/Artist/...", "./..." and Windows slashes. */
        fun parse(lines: List<String>): List<String> = lines
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("./").removePrefix("$MUSIC_DIR/") }
    }
}
