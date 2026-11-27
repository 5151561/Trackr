package com.trackr.ui.screens.stopdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.component.TrackrBottomSheet
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.platform.ExternalMapApp
import com.trackr.feature.stop.resources.Res as StopRes
import com.trackr.feature.stop.resources.stop_choose_map
import com.trackr.ui.icons.TrackrIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 「导航到此站」装了两家以上地图时的选择弹层（只装一家直接跳，不弹）。
 * 样子和乘车码的选择弹层保持一致：一行一家，点哪家跳哪家。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapChooserSheet(
    apps: List<ExternalMapApp>,
    onDismiss: () -> Unit,
    onSelect: (ExternalMapApp) -> Unit
) {
    val dimens = TrackrTheme.dimens
    TrackrBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spaceXl)
                .padding(bottom = dimens.spaceXl)
        ) {
            Text(
                text = stringResource(StopRes.string.stop_choose_map),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(dimens.spaceL))
            apps.forEachIndexed { index, app ->
                if (index > 0) Spacer(modifier = Modifier.height(dimens.spaceM))
                MapAppRow(app = app, onClick = { onSelect(app) })
            }
        }
    }
}

@Composable
private fun MapAppRow(app: ExternalMapApp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TrackrTheme.dimens.cardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                TrackrIcons.Navigation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceM))
            Text(
                app.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                TrackrIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
