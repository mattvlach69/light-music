package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The phone's media volume (the stream music and podcasts play on).
 *
 * Added for the Music tool: a tool screen consumes the volume keys, and LightOS
 * only steps the ringer for them, so a media tool needs its own way to change it.
 */
class LightMediaVolume internal constructor(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val max get() = am.getStreamMaxVolume(STREAM).coerceAtLeast(1)

    private val _level = MutableStateFlow(read())
    /** 0 = silent, 1 = loudest. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Number of steps between silent and loudest. */
    val steps: Int get() = max

    fun stepUp() = adjust(AudioManager.ADJUST_RAISE)
    fun stepDown() = adjust(AudioManager.ADJUST_LOWER)

    fun set(normalized: Float) {
        val v = (normalized.coerceIn(0f, 1f) * max).roundToInt()
        runCatching { am.setStreamVolume(STREAM, v, 0) }
        refresh()
    }

    /** Re-read the level, in case something else changed it. */
    fun refresh() { _level.value = read() }

    private fun adjust(direction: Int) {
        runCatching { am.adjustStreamVolume(STREAM, direction, 0) }
        refresh()
    }

    private fun read(): Float = am.getStreamVolume(STREAM).toFloat() / max

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
