package app.letnievpn.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.letnievpn.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private class AppRow(val pkg: String, val label: String)

private val SheetEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Приложения, которые ходят мимо VPN — шторка снизу, с лёгким плавным затемнением фона.
 * Кладётся внутрь корневого Box экрана, поверх остального.
 * Изменения вступают в силу при следующем подключении.
 */
@Composable
fun BoxScopeExcludedSheet(visible: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = visible, onBack = onClose)

    // лёгкое плавное затемнение фона; тап мимо шторки закрывает её
    val p = LocalPalette.current
    val dim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 420 else 300, easing = SheetEase),
        label = "dim"
    )
    if (dim > 0.001f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dim }
                .background(Color.Black.copy(alpha = if (p.dark) 0.28f else 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = visible,
                    onClick = onClose
                )
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(420, easing = SheetEase)) { it } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(300, easing = SheetEase)) { it } + fadeOut(tween(220)),
        ) {
            SheetContent(onClose)
        }
    }
}

@Composable
private fun SheetContent(onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val excluded by Repo.excluded.collectAsState()
    // порядок фиксируем при открытии: выбранные сверху, дальше по алфавиту — строки не прыгают при нажатии
    val startExcluded = remember { Repo.excluded.value }

    val apps by produceState<List<AppRow>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .filter { it.pkg != ctx.packageName }
                .sortedWith(
                    compareByDescending<AppRow> { it.pkg in startExcluded }
                        .thenBy { it.label.lowercase() }
                )
        }
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val fade by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 60f).coerceIn(0f, 1f)
        }
    }

    // свайп вниз по верхней части закрывает шторку
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val screenH = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .clip(shape)
            .background(p.bg)
    ) {
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = p.primary, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
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

        // ручка + зона для свайпа
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value > 140f) {
                                    offsetY.animateTo(screenH, tween(220))
                                    onClose()
                                } else {
                                    offsetY.animateTo(0f, tween(260, easing = SheetEase))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetY.animateTo(0f, tween(260, easing = SheetEase)) } }
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
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
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
