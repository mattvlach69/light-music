package com.matthewvlach.music

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterReaderTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())
    private val expected = listOf(
        Chapter("Opening", 0), Chapter("Chapter Two: The Road", 4000), Chapter("Épilogue", 9000),
    )

    @Test fun m4b() = assertEquals(expected, ChapterReader.read(res("book.m4b")))
    @Test fun mp3Id3v24() = assertEquals(expected, ChapterReader.read(res("book.mp3")))
    @Test fun mp3Id3v23() = assertEquals(expected, ChapterReader.read(res("book3.mp3")))
    @Test fun noChapters() = assertEquals(emptyList(), ChapterReader.read(res("plain.mp3")))
}
