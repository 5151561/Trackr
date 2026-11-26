package com.trackr.ui.screens.routeplan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.resources.ic_close
import com.trackr.core.designsystem.resources.ic_my_location
import com.trackr.core.designsystem.resources.ic_search
import com.trackr.core.designsystem.resources.ic_swap_vertical
import com.trackr.core.designsystem.theme.extendedColors
import com.trackr.core.model.AmapTipItem
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_plan_clear_from
import com.trackr.feature.route.resources.route_plan_clear_to
import com.trackr.feature.route.resources.route_plan_from_hint
import com.trackr.feature.route.resources.route_plan_go
import com.trackr.feature.route.resources.route_plan_my_location
import com.trackr.feature.route.resources.route_plan_my_location_from
import com.trackr.feature.route.resources.route_plan_my_location_to
import com.trackr.feature.route.resources.route_plan_swap
import com.trackr.feature.route.resources.route_plan_to_hint
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 顶部悬浮搜索岛：起点 / 终点两行 + 互换 + 规划，下面是输入提示 */
@Composable
internal fun SearchConsole(
    originText: String,
    destText: String,
    tipsField: TipsField?,
    tips: List<AmapTipItem>,
    onEvent: (RoutePlanEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // 光标落在哪个框。空着的时候弹一条「我的位置」选项，两个框一样
    var originFocused by remember { mutableStateOf(false) }
    var destFocused by remember { mutableStateOf(false) }
    val focusedField = when {
        destFocused -> TipsField.Dest
        originFocused -> TipsField.Origin
        else -> null
    }
    val myLocationField = focusedField?.takeIf { field ->
        when (field) {
            TipsField.Origin -> originText.isBlank()
            TipsField.Dest -> destText.isBlank()
        }
    }
    // 已经弹出「我的位置」时不再叠别的提示；提示只给正在编辑的那个框，避免旧结果串台
    val visibleTips = if (myLocationField == null && tipsField == focusedField) tips else emptyList()

    Column(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp))
                .testTag("top_search_console"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlaceInputRow(
                        value = originText,
                        placeholder = stringResource(RouteRes.string.route_plan_from_hint),
                        dotColor = MaterialTheme.extendedColors.success,
                        inputTag = "input_origin",
                        clearDescription = stringResource(RouteRes.string.route_plan_clear_from),
                        imeAction = ImeAction.Default,
                        onValueChange = { onEvent(RoutePlanEvent.OriginChanged(it)) },
                        onClear = { onEvent(RoutePlanEvent.ClearOrigin) },
                        onSearch = {},
                        onFocusChanged = { originFocused = it },
                        // 选中的「我的位置」用主色写，让用户看见它真的在生效
                        highlightValue = stringResource(RouteRes.string.route_plan_my_location)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp)
                            .height(0.8.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    )

                    PlaceInputRow(
                        value = destText,
                        placeholder = stringResource(RouteRes.string.route_plan_to_hint),
                        dotColor = MaterialTheme.colorScheme.error,
                        inputTag = "input_dest",
                        clearDescription = stringResource(RouteRes.string.route_plan_clear_to),
                        imeAction = ImeAction.Search,
                        onValueChange = { onEvent(RoutePlanEvent.DestChanged(it)) },
                        onClear = { onEvent(RoutePlanEvent.ClearDest) },
                        onSearch = { onEvent(RoutePlanEvent.Submit) },
                        onFocusChanged = { destFocused = it },
                        highlightValue = stringResource(RouteRes.string.route_plan_my_location)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 互换 / 规划上下叠放，和两行输入对齐（7d1cc92 压成横排后用户要求恢复原高度）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onEvent(RoutePlanEvent.SwapOriginDest) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .testTag("btn_swap_origin_dest")
                    ) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_swap_vertical),
                            contentDescription = stringResource(RouteRes.string.route_plan_swap),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { onEvent(RoutePlanEvent.Submit) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("btn_trigger_plan")
                    ) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_search),
                            contentDescription = stringResource(RouteRes.string.route_plan_go),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = myLocationField != null || visibleTips.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TipsCard(
                myLocationField = myLocationField,
                tips = visibleTips,
                onPickMyLocation = { field -> onEvent(RoutePlanEvent.PickMyLocation(field)) },
                onPick = { tip -> tipsField?.let { onEvent(RoutePlanEvent.PickTip(it, tip)) } }
            )
        }
    }
}

@Composable
private fun PlaceInputRow(
    value: String,
    placeholder: String,
    dotColor: Color,
    inputTag: String,
    clearDescription: String,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    /** 这段文字用主色写（选中的「我的位置」）；其他文字用常规色 */
    highlightValue: String? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .testTag(inputTag),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (highlightValue != null && value == highlightValue) {
                    primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                innerTextField()
            }
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_close),
                    contentDescription = clearDescription,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 空输入框点进去时弹出的「我的位置」选项（起点 / 终点两个框共用） */
@Composable
private fun MyLocationRow(field: TipsField, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("tip_my_location")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_my_location),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(RouteRes.string.route_plan_my_location),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    when (field) {
                        TipsField.Origin -> RouteRes.string.route_plan_my_location_from
                        TipsField.Dest -> RouteRes.string.route_plan_my_location_to
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 输入提示下拉卡片：空输入框时上面一条「我的位置」，下面是高德输入提示 */
@Composable
private fun TipsCard(
    myLocationField: TipsField?,
    tips: List<AmapTipItem>,
    onPickMyLocation: (TipsField) -> Unit,
    onPick: (AmapTipItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .heightIn(max = 280.dp)
            .shadow(elevation = 6.dp, shape = MaterialTheme.shapes.medium)
            .testTag("route_plan_tips"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (myLocationField != null) {
                MyLocationRow(field = myLocationField, onClick = { onPickMyLocation(myLocationField) })
            }
            if (tips.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    // 高德提示可能重名，key 带上序号保证唯一
                    itemsIndexed(
                        items = tips,
                        key = { index, tip -> "$index|${tip.name}|${tip.location}" },
                        contentType = { _, _ -> "tip" }
                    ) { _, tip ->
                        TipRow(tip = tip, onClick = { onPick(tip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TipRow(tip: AmapTipItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isStation = tip.address.contains("地铁") || tip.name.contains("地铁") || tip.name.contains("站")
        Icon(
            imageVector = vectorResource(if (isStation) Res.drawable.ic_bus else Res.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tip.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = listOfNotNull(
                tip.district.takeIf { it.isNotBlank() },
                tip.address.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
