package app.letnievpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Акценты для выбора: первый — розовый по умолчанию светлой темы. */
internal val Accents: List<Int> =
    listOf(0xFFD4527F, 0xFF8E5BD8, 0xFF3E8EDC, 0xFF2FA37A, 0xFFE08A3C).map { it.toInt() }

private val ThemeNames = listOf("Светлая", "Тёмная", "Ночная")
private val BtnNames = listOf("Стекло", "Плоская", "Кольцо")
private val TabNames = listOf("Тема", "Акцент", "Кнопка")

/**
 * Поверх уменьшенных карточек: сверху «Отмена» и «Применить», внизу варианты выбранной вкладки
 * и сами вкладки «Тема», «Акцент», «Кнопка».
 */
@Composable
internal fun LookChrome(
    modifier: Modifier,
    top: Dp,
    nav: Dp,
    sel: Int,
    tab: Int,
    accent: Int,
    btn: Int,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onTab: (Int) -> Unit,
    onSel: (Int) -> Unit,
    onAccent: (Int) -> Unit,
    onBtn: (Int) -> Unit,
) {
    val p = LocalPalette.current
    Box(modifier) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = top + 8.dp, start = 16.dp, end = 16.dp)
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill("Отмена", false, onCancel)
            Text("Оформление", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Pill("Применить", true, onApply)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = nav + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                Crossfade(tab, animationSpec = tween(200), label = "opts") { t ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (t) {
                            0 -> ThemeNames.forEachIndexed { i, n -> Chip(n, i == sel) { onSel(i) } }
                            1 -> Accents.forEach { c -> Dot(c, c == accent) { onAccent(if (c == accent) 0 else c) } }
                            else -> BtnNames.forEachIndexed { i, n -> Chip(n, i == btn) { onBtn(i) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                TabNames.forEachIndexed { i, n -> TabItem(i, n, i == tab) { onTab(i) } }
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (primary) p.primary else p.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (primary) p.onPrimary else p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) p.primary else p.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) p.onPrimary else p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Цветной кружок; выбранный обведён кольцом с зазором. Повторное нажатие сбрасывает акцент на цвет темы. */
@Composable
private fun Dot(argb: Int, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(argb)))
        if (on) {
            Canvas(Modifier.size(48.dp)) {
                drawCircle(p.text, radius = 23.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun TabItem(index: Int, title: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = if (on) p.primary else p.text2
    Column(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (on) lerp(p.surface, p.primary, 0.18f) else p.surface),
            contentAlignment = Alignment.Center
        ) {
            when (index) {
                0 -> ThemeTabIcon(tint, 26.dp)
                1 -> DropIcon(tint, 26.dp)
                else -> PowerIcon(tint, 26.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = tint, fontSize = 12.sp)
    }
}

/** Круг, правая половина закрашена. */
@Composable
private fun ThemeTabIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val c = Offset(12f * k, 12f * k)
        drawCircle(color, radius = 9f * k, center = c, style = Stroke(2f * k))
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(3f * k, 3f * k),
            size = Size(18f * k, 18f * k),
            style = Fill
        )
    }
}

/** Капля. */
@Composable
private fun DropIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path().apply {
            moveTo(12f * k, 3f * k)
            cubicTo(12f * k, 3f * k, 18f * k, 9.2f * k, 18f * k, 13.5f * k)
            arcTo(Rect(6f * k, 7.5f * k, 18f * k, 19.5f * k), 0f, 180f, false)
            cubicTo(6f * k, 9.2f * k, 12f * k, 3f * k, 12f * k, 3f * k)
            close()
        }
        drawPath(path, color, style = Stroke(2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
