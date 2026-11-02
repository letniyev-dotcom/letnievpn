package app.letnievpn.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.letnievpn.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppRow(val pkg: String, val label: String)

/** Приложения, которые ходят мимо VPN. Изменения вступают в силу при следующем подключении. */
@Composable
fun ExcludedAppsDialog(onClose: () -> Unit) {
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // окно диалога тоже на весь экран и с прозрачным статус-баром, как главный экран
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 29) {
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                }
                val ctl = WindowCompat.getInsetsController(w, view)
                ctl.isAppearanceLightStatusBars = !p.dark
                ctl.isAppearanceLightNavigationBars = !p.dark
            }
        }

        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val listState = rememberLazyListState()
        val fade by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / 90f).coerceIn(0f, 1f)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(p.bg)
        ) {
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.primary, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = top + 56.dp, bottom = bottom + 24.dp)
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

            // растворение шапки при прокрутке — тот же градиент, что на главном экране
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(top + 96.dp)
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

            // шапка: заголовок по центру, «назад» слева
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = top)
                    .height(56.dp)
            ) {
                Text(
                    "Приложения без VPN",
                    color = p.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        val k = size.width / 24f
                        drawLine(p.primary, Offset(15f * k, 6f * k), Offset(9f * k, 12f * k), 2f * k, StrokeCap.Round)
                        drawLine(p.primary, Offset(9f * k, 12f * k), Offset(15f * k, 18f * k), 2f * k, StrokeCap.Round)
                    }
                }
            }
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
