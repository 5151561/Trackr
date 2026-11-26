package com.trackr.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * commonMain 能用的图标（commonMain 没有 material-icons 依赖）。路径取自 v3 设计稿 `redesign_v3.html` 的 `<symbol>`，
 * 都是 Material 图标的 24×24 路径。第 21 步加；别的页面要新图标就往这里加。
 */
object TrackrIcons {
    val Back: ImageVector by lazy { icon("Back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z") }
    val Close: ImageVector by lazy {
        icon("Close", "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    }
    val Check: ImageVector by lazy { icon("Check", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z") }
    val Star: ImageVector by lazy {
        icon(
            "Star",
            "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"
        )
    }
    val StarFilled: ImageVector by lazy {
        icon("StarFilled", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    }
    val Play: ImageVector by lazy { icon("Play", "M8 5v14l11-7z") }
    val Qr: ImageVector by lazy {
        icon(
            "Qr",
            "M3 11h8V3H3v8zm2-6h4v4H5V5zM3 21h8v-8H3v8zm2-6h4v4H5v-4zM13 3v8h8V3h-8zm6 6h-4V5h4v4zM13 13h2v2h-2zM15 15h2v2h-2zM13 17h2v2h-2zM17 17h2v2h-2zM19 19h2v2h-2zM15 19h2v2h-2zM17 13h2v2h-2zM19 15h2v2h-2z"
        )
    }
    val Plus: ImageVector by lazy { icon("Plus", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z") }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = "TrackrIcons.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
