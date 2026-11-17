package com.jt.notes.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jt.notes.R

/**
 * The app launcher icon. R.mipmap.ic_launcher is an adaptive icon, which Compose
 * painterResource cannot load (it throws IllegalArgumentException at runtime), so the
 * icon is drawn by a plain ImageView, which handles adaptive icons natively. The
 * rounded clip is applied to the view itself with the same radius as the caller's
 * clip modifier, because a Compose clip does not reliably clip AndroidView content.
 */
@Composable
fun LauncherIcon(modifier: Modifier = Modifier, cornerRadius: Dp) {
    AndroidView(
        factory = { context ->
            val radiusPx = cornerRadius.value * context.resources.displayMetrics.density
            ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
            }
        },
        modifier = modifier,
    )
}
