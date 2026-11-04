package com.matthewvlach.music

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton

class AudiobooksScreen(host: SealedLightActivity) : MusicScreen(host) {

    override fun willShow() {
        super.willShow()
        Audiobooks.refresh(lightContext)
    }

    @Composable
    override fun Content() {
        val books by Audiobooks.books.collectAsState()
        val loading by Audiobooks.loading.collectAsState()
        val progress by Audiobooks.progress.collectAsState()
        Frame("Audiobooks", onBack = { goBack() }) {
            if (books.isEmpty()) {
                Message(
                    if (loading) "Loading…"
                    else "No audiobooks yet.\n\nRun send-to-phone on your Mac, drag in a book's folder, " +
                        "and type b when it asks if it's an audiobook.",
                )
                return@Frame
            }
            RowList {
                items(books) { book ->
                    ListRow(book.title, bookStatus(book, progress[book.id]), dim = progress[book.id]?.finished == true) {
                        navigateTo({ BookScreen(it, book.id) })
                    }
                }
            }
        }
    }
}

private fun bookStatus(book: Book, p: BookProgress?): String {
    val author = book.author.takeIf { it.isNotBlank() }
    val state = when {
        p?.finished == true -> "finished"
        p != null && (p.partIndex > 0 || p.positionMs > 0) ->
            "chapter ${book.chapterAt(p.partIndex, p.positionMs) + 1} of ${book.chapters.size}"
        book.durationMs > 0 -> formatDuration(book.durationMs)
        else -> null
    }
    return listOfNotNull(author, state).joinToString(" · ")
}

/** A book's chapters. Tap one to play from there. */
class BookScreen(host: SealedLightActivity, private val bookId: String) : MusicScreen(host) {
    @Composable
    override fun Content() {
        val books by Audiobooks.books.collectAsState()
        val progress by Audiobooks.progress.collectAsState()
        val book = books.firstOrNull { it.id == bookId }
        val p = progress[bookId]
        val current = p?.takeIf { !it.finished }?.let { book?.chapterAt(it.partIndex, it.positionMs) }
        val resumable = p != null && !p.finished && (p.partIndex > 0 || p.positionMs > 0)

        Frame(
            book?.title ?: "Audiobook",
            onBack = { goBack() },
            bottom = book?.let { b ->
                listOf(
                    LightBarButton.Text(if (resumable) "RESUME" else "PLAY", onClick = {
                        Audiobooks.play(host, b)
                        navigateTo({ NowPlayingScreen(it) })
                    }),
                    LightBarButton.Text(if (p?.finished == true) "UNFINISH" else "FINISHED", onClick = {
                        Audiobooks.setFinished(b, p?.finished != true)
                    }),
                )
            },
        ) {
            if (book == null) {
                Message("This book isn't on the phone anymore.")
                return@Frame
            }
            RowList {
                itemsIndexed(book.chapters) { i, c ->
                    val length = book.chapterEndMs(i) - c.startMs
                    ListRow(
                        primary = c.title,
                        secondary = listOfNotNull(
                            "Chapter ${i + 1}",
                            formatTime(length).takeIf { length > 0 },
                            "you're here".takeIf { i == current },
                        ).joinToString(" · "),
                    ) {
                        Audiobooks.play(host, book, chapter = i)
                        navigateTo({ NowPlayingScreen(it) })
                    }
                }
            }
        }
    }
}
