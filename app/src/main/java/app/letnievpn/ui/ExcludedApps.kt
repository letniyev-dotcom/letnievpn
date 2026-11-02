package app.letnievpn.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.letnievpn.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

private val OpenEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val CloseEase = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val BackEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Приложения, которые ходят мимо VPN — шторка снизу с лёгким затемнением фона.
 * Кладётся внутрь корневого Box экрана, поверх остального.
 * Движение шторки и затемнение идут только через graphicsLayer — без перекомпоновки и пересчёта раскладки.
 * Изменения вступают в силу при следующем подключении.
 */
@Composable
fun BoxScopeExcludedSheet(visible: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenH = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val closeDist = with(LocalDensity.current) { 120.dp.toPx() }

    // положение шторки: screenH — спрятана, 0 — открыта
    val ty = remember { Animatable(screenH) }
    var drag by remember { mutableFloatStateOf(0f) }
    val shown by remember { derivedStateOf { ty.value < screenH - 1f } }

    // заранее готовим список приложений; после закрытия обновляем (могли поставить новое приложение)
    LaunchedEffect(visible) { if (!visible || ExcludedAppsCache.apps == null) ExcludedAppsCache.load(ctx) }

    LaunchedEffect(visible) {
        if (visible) ty.animateTo(0f, tween(520, easing = OpenEase))
        else ty.animateTo(screenH, tween(340, easing = CloseEase))
    }

    BackHandler(enabled = visible, onBack = onClose)

    if (shown || visible) {
        // лёгкое затемнение фона: яркость привязана к положению шторки, тап мимо закрывает
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - (ty.value + drag) / screenH).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = if (p.dark) 0.28f else 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = visible,
                    onClick = onClose
                )
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetContent(
                modifier = Modifier.graphicsLayer { translationY = ty.value + drag },
                onDragDelta = { drag = (drag + it).coerceAtLeast(0f) },
                onDragEnd = {
                    scope.launch {
                        val d = drag
                        ty.snapTo(d)
                        drag = 0f
                        if (d > closeDist) onClose() else ty.animateTo(0f, tween(300, easing = BackEase))
                    }
                },
            )
        }
    }
}

@Composable
private fun SheetContent(modifier: Modifier, onDragDelta: (Float) -> Unit, onDragEnd: () -> Unit) {
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

    // список появляется, когда шторка уже выехала — иначе сборка строк съедает кадры анимации
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(420); ready = true }
    val listAlpha by animateFloatAsState(if (ready && apps != null) 1f else 0f, tween(260), label = "list")

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val fade by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 60f).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(p.bg)
    ) {
        val list = apps
        if (ready && list != null) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha },
                state = listState,
                contentPadding = PaddingValues(top = 64.dp, bottom = bottom + 16.dp)
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
                            .padding(horizontal = 20.dp, vertical = 8.dp),
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

        // фейд под заголовком — тот же градиент, что на главном экране
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .graphicsLayer { alpha = fade }
                .background(
                    Brush.verticalGradient(
                        0.00f to p.bg,
                        0.40f to p.bg,
                        0.52f to p.bg.copy(alpha = 0.90f),
                        0.64f to p.bg.copy(alpha = 0.65f),
                        0.76f to p.bg.copy(alpha = 0.38f),
                        0.88f to p.bg.copy(alpha = 0.14f),
                        1.00f to p.bg.copy(alpha = 0f),
                    )
                )
        )

        // ручка + заголовок; свайп вниз по этой зоне закрывает шторку
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dy -> change.consume(); onDragDelta(dy) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.text2.copy(alpha = 0.45f))
            )
            Text(
                "Приложения без VPN",
                color = p.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            )
        }
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
