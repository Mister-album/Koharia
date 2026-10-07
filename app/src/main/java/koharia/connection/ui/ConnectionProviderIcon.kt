package koharia.connection.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import koharia.connection.ConnectionRegistry
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ConnectionProviderIcon(
    providerId: String,
    modifier: Modifier = Modifier,
) {
    val registry = remember { Injekt.get<ConnectionRegistry>() }
    val iconRes = registry.provider(providerId)?.iconRes ?: 0
    ConnectionProviderIcon(iconRes, modifier)
}

@Composable
fun ConnectionProviderIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(iconRes.takeIf { it != 0 } ?: R.mipmap.ic_default_source),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}
