package com.batterymanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.batterymanager.app.platform.apps.LaunchableAppInfo

/**
 * A single row: optional checkbox, icon, and name.
 *
 * No Select All, no per-app actions beyond the checkbox yet - those come
 * with later Architecture-doc components. [selectable] controls whether a
 * checkbox is shown at all - callers must not pass `selectable = true` for
 * an app that fails `isSelectable` (see `MainViewModel`/`AppSelectability`),
 * since this composable has no way to enforce that safety rule itself.
 */
@Composable
fun AppRow(
    app: LaunchableAppInfo,
    selectable: Boolean,
    selected: Boolean,
    onToggleSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectable) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected(app.packageName) }
            )
        } else {
            // Reserve the same width a Checkbox would occupy so app names
            // still line up in a column, whether or not that particular
            // row has a checkbox.
            Spacer(modifier = Modifier.width(CHECKBOX_SLOT_WIDTH))
        }

        // Drawable has no Compose renderer of its own; core-ktx's
        // Drawable.toBitmap() (already a project dependency) is the
        // documented way to bridge it into an ImageBitmap without pulling
        // in an image-loading library for what's just a launcher icon.
        val iconBitmap = remember(app.packageName) {
            val width = app.icon.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_PX
            val height = app.icon.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_PX
            app.icon.toBitmap(width, height).asImageBitmap()
        }
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** Fallback pixel size for drawables that report no intrinsic size. */
private const val DEFAULT_ICON_PX = 108

/** Matches Material 3 [Checkbox]'s default minimum touch target width. */
private val CHECKBOX_SLOT_WIDTH = 48.dp
