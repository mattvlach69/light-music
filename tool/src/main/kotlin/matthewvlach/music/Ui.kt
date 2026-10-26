package com.matthewvlach.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

const val ROW_UNITS = 3.5f

/** Top bar, a body, and an optional bottom bar, in the Light theme. */
@Composable
fun Frame(
    title: String?,
    onBack: (() -> Unit)?,
    right: LightBarButton? = null,
    bottom: List<LightBarButton>? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = title?.let { LightTopBarCenter.Text(it) },
                rightButton = right,
            )
            Column(Modifier.weight(1f).fillMaxWidth()) { body() }
            if (!bottom.isNullOrEmpty()) LightBottomBar(items = bottom)
        }
    }
}

/** A scrolling list of same-height rows. */
@Composable
fun ColumnScope.RowList(content: LazyListScope.() -> Unit) {
    LightLazyScrollView(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = ROW_UNITS,
        content = content,
    )
}

@Composable
fun ListRow(primary: String, secondary: String? = null, dim: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(end = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = primary,
            variant = LightTextVariant.Copy,
            lighten = dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary != null) {
            LightText(
                text = secondary,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Centered message for empty or loading states. */
@Composable
fun ColumnScope.Message(text: String) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Paragraph, lighten = true)
    }
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/**
 * Base for every screen. Holds the shared player connection: connect when a screen shows,
 * let go when the tool is backgrounded (the music keeps playing, it is detached).
 */
abstract class MusicScreen(protected val host: com.thelightphone.sdk.SealedLightActivity) :
    com.thelightphone.sdk.SimpleLightScreen<Unit>(host) {
    override fun willShow() { Music.connect(host) }
    override fun onAppPause() { Music.disconnect() }
}
