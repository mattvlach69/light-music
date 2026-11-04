package com.matthewvlach.music

import java.io.File
import java.io.RandomAccessFile

/** A chapter marker inside one audio file. */
data class Chapter(val title: String, val startMs: Long)

/**
 * Reads chapter markers stored inside an audio file:
 * - .m4b / .m4a: Nero "chpl" chapters, or a QuickTime chapter text track
 * - .mp3: ID3v2 CHAP frames
 * Returns an empty list when the file has none or can't be read.
 */
object ChapterReader {

    fun read(file: File): List<Chapter> = runCatching {
        when (file.extension.lowercase()) {
            "m4b", "m4a", "mp4", "aac" -> RandomAccessFile(file, "r").use { Mp4Chapters(it).read() }
            "mp3" -> RandomAccessFile(file, "r").use { id3Chapters(it) }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
        .filter { it.startMs >= 0 }
        .sortedBy { it.startMs }
        .distinctBy { it.startMs }
}

// MP4

private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
    val bodyStart get() = start + headerSize
    val end get() = start + size
}

private class Mp4Chapters(private val f: RandomAccessFile) {

    private class Track(
        var id: Int = 0,
        var timescale: Long = 0,
        var handler: String = "",
        var chapterRefs: List<Int> = emptyList(),
        var stts: List<Pair<Long, Long>> = emptyList(), // (count, delta)
        var stsc: List<Triple<Long, Long, Long>> = emptyList(), // (firstChunk, samplesPerChunk, descIndex)
        var sizes: List<Long> = emptyList(),
        var chunkOffsets: List<Long> = emptyList(),
    )

    fun read(): List<Chapter> {
        val moov = children(0, f.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovKids = children(moov.bodyStart, moov.end)

        // Nero chapters: moov/udta/chpl
        moovKids.firstOrNull { it.type == "udta" }?.let { udta ->
            children(udta.bodyStart, udta.end).firstOrNull { it.type == "chpl" }?.let { chpl ->
                val chapters = readChpl(chpl)
                if (chapters.isNotEmpty()) return chapters
            }
        }

        // QuickTime chapters: a text track, referenced by the audio track's tref/chap
        val tracks = moovKids.filter { it.type == "trak" }.map(::readTrack)
        val referenced = tracks.flatMap { it.chapterRefs }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in referenced }
            ?: tracks.firstOrNull { it.handler == "text" }
            ?: return emptyList()
        return readTextSamples(chapterTrack)
    }

    private fun children(from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(f::readFully).toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = f.readLong()
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header) break
            out += Box(type, pos, header, size)
            pos += size
        }
        return out
    }

    private fun readChpl(box: Box): List<Chapter> {
        f.seek(box.bodyStart)
        val version = f.readUnsignedByte()
        f.skipBytes(3)
        if (version > 0) f.skipBytes(4)
        val count = f.readUnsignedByte()
        return (0 until count).map {
            val start100ns = f.readLong()
            val len = f.readUnsignedByte()
            val title = ByteArray(len).also(f::readFully).toString(Charsets.UTF_8)
            Chapter(title.trim(), start100ns / 10_000)
        }
    }

    private fun readTrack(trak: Box): Track {
        val t = Track()
        for (b in children(trak.bodyStart, trak.end)) when (b.type) {
            "tkhd" -> {
                f.seek(b.bodyStart)
                val version = f.readUnsignedByte()
                f.skipBytes(3 + if (version == 1) 16 else 8)
                t.id = f.readInt()
            }
            "tref" -> children(b.bodyStart, b.end).firstOrNull { it.type == "chap" }?.let { chap ->
                f.seek(chap.bodyStart)
                t.chapterRefs = (0 until ((chap.size - chap.headerSize) / 4).toInt()).map { f.readInt() }
            }
            "mdia" -> readMdia(b, t)
        }
        return t
    }

    private fun readMdia(mdia: Box, t: Track) {
        for (b in children(mdia.bodyStart, mdia.end)) when (b.type) {
            "mdhd" -> {
                f.seek(b.bodyStart)
                val version = f.readUnsignedByte()
                f.skipBytes(3 + if (version == 1) 16 else 8)
                t.timescale = f.readInt().toLong() and 0xFFFFFFFFL
            }
            "hdlr" -> {
                f.seek(b.bodyStart + 8)
                t.handler = ByteArray(4).also(f::readFully).toString(Charsets.ISO_8859_1)
            }
            "minf" -> children(b.bodyStart, b.end).firstOrNull { it.type == "stbl" }?.let { readStbl(it, t) }
        }
    }

    private fun readStbl(stbl: Box, t: Track) {
        for (b in children(stbl.bodyStart, stbl.end)) {
            f.seek(b.bodyStart + 4) // skip version/flags
            when (b.type) {
                "stts" -> t.stts = List(f.readInt()) { f.u32() to f.u32() }
                "stsc" -> t.stsc = List(f.readInt()) { Triple(f.u32(), f.u32(), f.u32()) }
                "stsz" -> {
                    val fixed = f.u32()
                    val count = f.readInt()
                    t.sizes = if (fixed != 0L) List(count) { fixed } else List(count) { f.u32() }
                }
                "stco" -> t.chunkOffsets = List(f.readInt()) { f.u32() }
                "co64" -> t.chunkOffsets = List(f.readInt()) { f.readLong() }
            }
        }
    }

    private fun readTextSamples(t: Track): List<Chapter> {
        if (t.timescale <= 0 || t.sizes.isEmpty() || t.chunkOffsets.isEmpty()) return emptyList()
        // Where each sample starts in the file.
        val offsets = ArrayList<Long>(t.sizes.size)
        var sample = 0
        for (chunk in 1..t.chunkOffsets.size) {
            val run = t.stsc.lastOrNull { it.first <= chunk } ?: break
            var pos = t.chunkOffsets[chunk - 1]
            repeat(run.second.toInt()) {
                if (sample >= t.sizes.size) return@repeat
                offsets += pos
                pos += t.sizes[sample]
                sample++
            }
        }
        // When each sample starts in time.
        val starts = ArrayList<Long>(t.sizes.size)
        var time = 0L
        for ((count, delta) in t.stts) repeat(count.toInt()) {
            starts += time
            time += delta
        }
        return offsets.indices.mapNotNull { i ->
            val startTicks = starts.getOrNull(i) ?: return@mapNotNull null
            f.seek(offsets[i])
            val len = f.readUnsignedShort()
            if (len <= 0 || len > t.sizes[i] - 2) return@mapNotNull Chapter("", startTicks * 1000 / t.timescale)
            val bytes = ByteArray(len).also(f::readFully)
            Chapter(decodeText(bytes).trim(), startTicks * 1000 / t.timescale)
        }
    }

    private fun decodeText(b: ByteArray): String =
        if (b.size >= 2 && (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() || b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))
            String(b, Charsets.UTF_16)
        else String(b, Charsets.UTF_8)

    private fun RandomAccessFile.u32(): Long = readInt().toLong() and 0xFFFFFFFFL
}

// MP3 (ID3v2.3 / 2.4 CHAP frames)

private fun id3Chapters(f: RandomAccessFile): List<Chapter> {
    val header = ByteArray(10).also(f::readFully)
    if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return emptyList()
    val major = header[3].toInt()
    if (major !in 3..4) return emptyList()
    val tagSize = syncsafe(header, 6)
    val tag = ByteArray(tagSize).also(f::readFully)
    var pos = 0
    if (header[5].toInt() and 0x40 != 0) { // extended header
        pos += if (major == 4) syncsafe(tag, 0) else (be32(tag, 0) + 4)
    }
    return frames(tag, pos, tag.size, major).filter { it.first == "CHAP" }.mapNotNull { (_, body) ->
        var p = body.indexOf(0.toByte())
        if (p < 0 || p + 17 > body.size) return@mapNotNull null
        p++
        val startMs = be32(body, p).toLong() and 0xFFFFFFFFL
        p += 16
        val title = frames(body, p, body.size, major).firstOrNull { it.first == "TIT2" }?.second?.let(::id3Text)
        Chapter(title.orEmpty().trim(), startMs)
    }
}

private fun frames(b: ByteArray, from: Int, to: Int, major: Int): List<Pair<String, ByteArray>> {
    val out = mutableListOf<Pair<String, ByteArray>>()
    var p = from
    while (p + 10 <= to) {
        val id = String(b, p, 4, Charsets.ISO_8859_1)
        if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
        val size = if (major == 4) syncsafe(b, p + 4) else be32(b, p + 4)
        if (size <= 0 || p + 10 + size > to) break
        out += id to b.copyOfRange(p + 10, p + 10 + size)
        p += 10 + size
    }
    return out
}

private fun id3Text(body: ByteArray): String {
    if (body.isEmpty()) return ""
    val data = body.copyOfRange(1, body.size)
    val s = when (body[0].toInt()) {
        1 -> String(data, Charsets.UTF_16)
        2 -> String(data, Charsets.UTF_16BE)
        3 -> String(data, Charsets.UTF_8)
        else -> String(data, Charsets.ISO_8859_1)
    }
    return s.trimEnd('\u0000')
}

private fun syncsafe(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0x7F shl 21) or (b[p + 1].toInt() and 0x7F shl 14) or
        (b[p + 2].toInt() and 0x7F shl 7) or (b[p + 3].toInt() and 0x7F)

private fun be32(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xFF shl 24) or (b[p + 1].toInt() and 0xFF shl 16) or
        (b[p + 2].toInt() and 0xFF shl 8) or (b[p + 3].toInt() and 0xFF)
