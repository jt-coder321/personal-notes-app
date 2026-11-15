package com.jt.notes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.jt.notes.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows a picture attached to a note, loaded off the main thread and scaled down. */
@Composable
fun NoteImage(
    name: String,
    maxPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, name, maxPx) {
        value = withContext(Dispatchers.IO) { Images.load(context, name, maxPx)?.asImageBitmap() }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = "Picture", modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(LocalContentColor.current.copy(alpha = 0.08f)))
    }
}
