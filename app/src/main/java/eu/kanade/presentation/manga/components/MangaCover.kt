package eu.kanade.presentation.manga.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R

enum class MangaCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),
    ;

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.extraSmall,
        onClick: (() -> Unit)? = null,
    ) {
        val coverModifier = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
        val errorPainter = rememberResourceBitmapPainter(id = R.drawable.cover_error)
        if (data == null) {
            Image(
                painter = errorPainter,
                contentDescription = contentDescription,
                modifier = coverModifier,
                contentScale = ContentScale.Crop,
            )
            return
        }
        AsyncImage(
            model = data,
            placeholder = ColorPainter(CoverPlaceholderColor),
            error = errorPainter,
            contentDescription = contentDescription,
            modifier = coverModifier,
            contentScale = ContentScale.Crop,
        )
    }
}

private val CoverPlaceholderColor = Color(0x1F888888)
