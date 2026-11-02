package app.letnievpn.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.letnievpn.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal class AppRow(val pkg: String, val label: String)

/** Список приложений и иконки грузим заранее, чтобы шторка открывалась без работы на главном потоке. */
internal object ExcludedAppsCache {
    @Volatile var apps: List<AppRow>? = null
    val icons = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(ctx: Context) = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != ctx.packageName }
        list.forEach { a ->
            if (!icons.containsKey(a.pkg)) {
                try {
                    icons[a.pkg] = pm.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap()
                } catch (_: Exception) {
                }
            }
        }
        apps = list
    }
}

private val RevealOpen = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val RevealShut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

// на конце дуги cos(π/2) в Float чуть меньше нуля, а дробная степень отрицательного числа — NaN, и контур пропадает целиком
private fun sn(t: Float, e: Float) = sin(t).coerceIn(0f, 1f).pow(e)
private fun cs(t: Float, e: Float) = cos(t).coerceIn(0f, 1f).pow(e)

/**
 * Скругления «как в iOS» (CSS corner-shape: superellipse(k)): вместо дуги окружности угол — кривая
 * |x|^n + |y|^n = 1, где n = 2^k. Плавный переход в прямую сторону, без заметного «излома» кривизны.
 */
private class SquircleShape(private val radius: Dp, private val k: Float = 1.7f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = minOf(with(density) { radius.toPx() }, w / 2f, h / 2f)
        val e = 2f / 2f.pow(k)
        val steps = 28
        val path = Path()
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        // верхний правый
        for (i in 1..steps) {
            val t = i / steps.toFloat() * (PI / 2).toFloat()
            path.lineTo(w - r + r * sn(t, e), r - r * cs(t, e))
        }
        path.lineTo(w, h - r)
        // нижний правый
        for (i in 1..steps) {
            val t = i / steps.toFloat() * (PI / 2).toFloat()
            path.lineTo(w - r + r * cs(t, e), h - r + r * sn(t, e))
        }
        path.lineTo(r, h)
        // нижний левый
        for (i in 1..steps) {
            val t = i / steps.toFloat() * (PI / 2).toFloat()
            path.lineTo(r - r * sn(t, e), h - r + r * cs(t, e))
        }
        path.lineTo(0f, r)
        // верхний левый
        for (i in 1..steps) {
            val t = i / steps.toFloat() * (PI / 2).toFloat()
            path.lineTo(r - r * cs(t, e), r - r * sn(t, e))
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Окно раскрывается из своего центра вверх и вниз — как в макете: клип растёт от узкой полосы до полной высоты. */
private class RevealShape(private val p: Float, private val minH: Float, private val rStart: Float, private val rEnd: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = minH + (size.height - minH) * p
        val top = (size.height - h) / 2f
        val r = rStart + (rEnd - rStart) * p
        return Outline.Rounded(RoundRect(0f, top, size.width, top + h, CornerRadius(r, r)))
    }
}

/**
 * Приложения, которые ходят мимо VPN — окно внизу экрана с непрерывными скруглениями и лёгким затемнением фона.
 * Кладётся внутрь корневого Box экрана, поверх остального.
 * Открытие, затемнение и прозрачность идут через graphicsLayer — без перекомпоновки и пересчёта раскладки.
 * Изменения вступают в силу при следующем подключении.
 */
@Composable
fun BoxScopeExcludedSheet(visible: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current

    val reveal = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val shown by remember { derivedStateOf { reveal.value > 0.001f || alpha.value > 0.001f } }

    // заранее готовим список приложений; после закрытия обновляем (могли поставить новое приложение)
    LaunchedEffect(visible) { if (!visible || ExcludedAppsCache.apps == null) ExcludedAppsCache.load(ctx) }

    LaunchedEffect(visible) {
        coroutineScope {
            if (visible) {
                launch { reveal.animateTo(1f, tween(600, easing = RevealOpen)) }
                launch { alpha.animateTo(1f, tween(160, easing = LinearEasing)) }
            } else {
                launch { reveal.animateTo(0f, tween(340, easing = RevealShut)) }
                launch { delay(200); alpha.animateTo(0f, tween(140, easing = LinearEasing)) }
            }
        }
    }

    BackHandler(enabled = visible, onBack = onClose)

    if (shown || visible) {
        // лёгкое затемнение фона, тап мимо окна закрывает
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = reveal.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = if (p.dark) 0.28f else 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = visible,
                    onClick = onClose
                )
        )
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, bottom = navBottom + 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            SheetContent(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .graphicsLayer {
                        this.alpha = alpha.value
                        val rv = reveal.value
                        if (rv < 0.999f) {
                            shape = RevealShape(rv, 52.dp.toPx(), 26.dp.toPx(), 24.dp.toPx())
                            clip = true
                        }
                    }
            )
        }
    }
}

@Composable
private fun SheetContent(modifier: Modifier) {
    val p = LocalPalette.current
    val excluded by Repo.excluded.collectAsState()
    // порядок фиксируем при открытии: выбранные сверху, дальше по алфавиту — строки не прыгают при нажатии
    val startExcluded = remember { Repo.excluded.value }
    val apps by produceState<List<AppRow>?>(null) {
        while (ExcludedAppsCache.apps == null) delay(30)
        val base = ExcludedAppsCache.apps!!
        value = withContext(Dispatchers.Default) {
            base.sortedWith(compareByDescending<AppRow> { it.pkg in startExcluded }.thenBy { it.label.lowercase() })
        }
    }
    val listAlpha by animateFloatAsState(if (apps != null) 1f else 0f, tween(260), label = "list")

    val listState = rememberLazyListState()
    val fade by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 60f).coerceIn(0f, 1f)
        }
    }
    val shape = remember { SquircleShape(46.dp, 1.7f) }

    Box(
        modifier
            .clip(shape)
            .background(p.surface)
    ) {
        val list = apps
        if (list != null) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha },
                state = listState,
                contentPadding = PaddingValues(top = 68.dp, bottom = 24.dp)
            ) {
                items(list, key = { it.pkg }) { app ->
                    val on = app.pkg in excluded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { Repo.toggleExcluded(app.pkg) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            app.label,
                            color = p.text,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        CheckBox(on)
                    }
                }
            }
        }

        // полупрозрачный фейд под заголовком: список проглядывает сквозь него
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .graphicsLayer { alpha = fade }
                .background(
                    Brush.verticalGradient(
                        0.00f to p.surface.copy(alpha = 0.80f),
                        0.45f to p.surface.copy(alpha = 0.72f),
                        0.60f to p.surface.copy(alpha = 0.50f),
                        0.75f to p.surface.copy(alpha = 0.28f),
                        0.90f to p.surface.copy(alpha = 0.08f),
                        1.00f to p.surface.copy(alpha = 0f),
                    )
                )
        )

        Text(
            "Приложения без VPN",
            color = p.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp)
        )
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(ExcludedAppsCache.icons[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun CheckBox(on: Boolean) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (on) p.primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (on) p.primary else p.text2, shape),
        contentAlignment = Alignment.Center
    ) {
        if (on) {
            Canvas(Modifier.size(14.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.1f, size.height * 0.55f)
                    lineTo(size.width * 0.4f, size.height * 0.85f)
                    lineTo(size.width * 0.92f, size.height * 0.15f)
                }
                drawPath(path, p.onPrimary, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
