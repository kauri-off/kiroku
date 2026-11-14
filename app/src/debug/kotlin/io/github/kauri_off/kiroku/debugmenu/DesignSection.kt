package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.SectionHeader

@Composable
fun DesignRoute() {
    val context = LocalContext.current
    DesignSection(onGallery = { context.startActivity(GalleryActivity.intent(context)) })
}

// The way to the component gallery.
@Composable
fun DesignSection(onGallery: () -> Unit) {
    SectionHeader(stringResource(R.string.debug_menu_design))
    ListItem(
        onClick = onGallery,
        supportingContent = { Text(stringResource(R.string.debug_menu_gallery_detail)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        content = { Text(stringResource(R.string.gallery_title)) },
    )
}
