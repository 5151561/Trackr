package com.trackr.ui.components.transitcode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.component.TrackrBottomSheet
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_qr_code
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.ui.resources.Res as UiRes
import com.trackr.core.ui.resources.ui_code_chooser_remember
import com.trackr.core.ui.resources.ui_code_chooser_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 乘车码平台选择（「我的」里选了「每次询问」时才弹，v3）。
 * 勾上「以后都用这个」就记成默认，下次点按钮直接打开。
 *
 * @param onSelect 选了哪个平台、要不要记成默认
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitCodeChooserSheet(
    onDismiss: () -> Unit,
    onSelect: (provider: TransitCodeProvider, saveAsDefault: Boolean) -> Unit
) {
    var rememberChoice by rememberSaveable { mutableStateOf(false) }
    val dimens = TrackrTheme.dimens

    TrackrBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spaceXl)
                .padding(bottom = dimens.spaceXl)
        ) {
            Text(
                text = stringResource(UiRes.string.ui_code_chooser_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(dimens.spaceL))
            ProviderRow(
                label = TransitCodeProvider.ALIPAY.displayName,
                brand = ALIPAY_BLUE,
                onClick = { onSelect(TransitCodeProvider.ALIPAY, rememberChoice) }
            )
            Spacer(modifier = Modifier.height(dimens.spaceM))
            ProviderRow(
                label = TransitCodeProvider.UNIONPAY.displayName,
                brand = UNIONPAY_RED,
                onClick = { onSelect(TransitCodeProvider.UNIONPAY, rememberChoice) }
            )
            Spacer(modifier = Modifier.height(dimens.spaceM))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberChoice = !rememberChoice },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                Text(
                    text = stringResource(UiRes.string.ui_code_chooser_remember),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(label: String, brand: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = brand.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TrackrTheme.dimens.cardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = brand, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_qr_code),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(7.dp)
                )
            }
            Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceM))
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 支付宝、云闪付的品牌色（只用在选择弹层的图标底色上） */
private val ALIPAY_BLUE = Color(0xFF1677FF)
private val UNIONPAY_RED = Color(0xFFE60012)
