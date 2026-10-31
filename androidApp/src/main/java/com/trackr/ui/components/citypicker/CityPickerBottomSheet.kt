package com.trackr.ui.components.citypicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TransitCity
import com.trackr.ui.components.common.DataSourceBadge
import com.trackr.ui.components.common.TrackrBottomSheet

/**
 * 城市项条目卡片 (统一用于搜索过滤列表与省份展开列表，消除近 100 行重复渲染逻辑)
 */
@Composable
fun CityPickerItemRow(
    city: TransitCity,
    isCurrent: Boolean,
    isLocated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProvinceBadge: Boolean = false,
    provinceName: String? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = city.cityName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (showProvinceBadge) {
                    val prov = provinceName ?: city.province.ifBlank {
                        ProvinceCityCatalog.inferProvince(city.cityName)
                    }
                    if (prov.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = prov,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "当前",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                if (isLocated) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "定位",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                if (city.cityId.isNotBlank()) {
                    DataSourceBadge(
                        source = DataSourceType.CHELAILE,
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (!city.mygolbsCityCode.isNullOrBlank()) {
                    DataSourceBadge(
                        source = DataSourceType.MYGOLBS,
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * 全国 480+ 城市两级（省份 → 城市）选择器 BottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerBottomSheet(
    currentCity: TransitCity,
    locatedCity: TransitCity?,
    selectedProvince: String,
    cities: List<TransitCity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectProvince: (String) -> Unit,
    onSelectCity: (TransitCity) -> Unit,
    onRelocate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hotCities: List<TransitCity>? = null,
    allCities: List<TransitCity> = cities
) {
    val effectiveCities = if (allCities.isNotEmpty()) allCities else cities

    TrackrBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            // 标题与关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "切换城市",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "按 省 / 直辖市 分级快速查找全国 480+ 城市",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 搜索过滤框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索城市中文名或拼音（如：玉林 / yulin）") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "搜索")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (searchQuery.isNotBlank()) {
                // 搜索结果直接展示
                val matchingCities = remember(effectiveCities, searchQuery) {
                    val q = searchQuery.trim().lowercase()
                    effectiveCities.filter {
                        it.cityName.lowercase().contains(q) ||
                            it.pinyin.lowercase().contains(q) ||
                            (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "找到 ${matchingCities.size} 个相关城市",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = matchingCities,
                        key = { it.uniqueKey },
                        contentType = { "matching_city" }
                    ) { city ->
                        CityPickerItemRow(
                            city = city,
                            isCurrent = city.matchesCity(currentCity),
                            isLocated = city.matchesCity(locatedCity),
                            onClick = { onSelectCity(city) },
                            showProvinceBadge = true,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            } else {
                // 非搜索状态：顶部定位与热门城市
                val effectiveHotCities = remember(effectiveCities, hotCities) {
                    if (hotCities != null) {
                        hotCities
                    } else {
                        val hot = effectiveCities.filter { it.isHot }
                        if (hot.isNotEmpty()) hot.take(8) else effectiveCities.take(8)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (locatedCity != null) "定位: ${locatedCity.cityName}" else "已选: ${currentCity.cityName}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRelocate() },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "重新定位",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 热门城市 Chip 行
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    items(
                        items = effectiveHotCities,
                        key = { "hot_${it.uniqueKey}" },
                        contentType = { "hot_city" }
                    ) { city ->
                        FilterChip(
                            selected = city.matchesCity(currentCity),
                            onClick = { onSelectCity(city) },
                            label = { Text(city.cityName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 省份-城市双列联动选择器
                val provinceMap = remember(effectiveCities) {
                    val map = linkedMapOf<String, MutableList<TransitCity>>()
                    for (p in ProvinceCityCatalog.PROVINCE_ORDER) {
                        map[p] = mutableListOf()
                    }
                    for (city in effectiveCities) {
                        val prov = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        val list = map.getOrPut(prov) { mutableListOf() }
                        list.add(city)
                    }
                    map.filterValues { it.isNotEmpty() }
                }

                val availableProvinces = remember(provinceMap) { provinceMap.keys.toList() }
                val activeProvince = if (selectedProvince in provinceMap.keys) {
                    selectedProvince
                } else {
                    availableProvinces.firstOrNull() ?: "直辖市"
                }
                val provinceCities = provinceMap[activeProvince].orEmpty()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    // 左侧省份列表 (width 116.dp)
                    Surface(
                        modifier = Modifier
                            .width(116.dp)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = availableProvinces,
                                key = { it },
                                contentType = { "available_province" }
                            ) { prov ->
                                val isSelected = prov == activeProvince
                                val count = provinceMap[prov]?.size ?: 0
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectProvince(prov) },
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        Color.Transparent
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .height(18.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        } else {
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }

                                        Text(
                                            text = prov,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.weight(1f)
                                        )

                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 右侧城市列表 (weight 1f, padding(start = 10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 10.dp)
                    ) {
                        Text(
                            text = "$activeProvince · 共 ${provinceCities.size} 城",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = provinceCities,
                                key = { it.uniqueKey },
                                contentType = { "province_city" }
                            ) { city ->
                                CityPickerItemRow(
                                    city = city,
                                    isCurrent = city.matchesCity(currentCity),
                                    isLocated = city.matchesCity(locatedCity),
                                    onClick = { onSelectCity(city) },
                                    showProvinceBadge = false,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
