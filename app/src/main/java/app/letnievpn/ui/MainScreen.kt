package app.letnievpn.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.letnievpn.R
import app.letnievpn.data.Repo
import app.letnievpn.data.Server
import app.letnievpn.data.Sub
import app.letnievpn.data.VpnStatus
import kotlinx.coroutines.launch

@Composable
fun MainScreen(onPower: () -> Unit, onReconnect: () -> Unit, onPickAvatar: (String) -> Unit) {
    // размер шрифта не раздуваем системным масштабом — иначе блоки выходят громоздкими
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = minOf(d.fontScale, 1f))) {
        LetnieTheme { Content(onPower, onReconnect, onPickAvatar) }
    }
}

// ── стекло: объёмная заливка в светлой теме, плоская в тёмной ─────────────

private fun Modifier.glass(
    p: Palette,
    shape: Shape,
    a: Color,
    b: Color,
    elevation: Dp,
    shadowColor: Color,
    flatColor: Color,
): Modifier = this
    .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
    .background(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset.Infinite), shape)
    .border(
        1.5.dp,
        Brush.linearGradient(
            listOf(p.rimHi, p.rimLo, p.rimLo, p.rimHi),
            start = Offset.Zero,
            end = Offset.Infinite
        ),
        shape
    )

@Composable
private fun Content(onPower: () -> Unit, onReconnect: () -> Unit, onPickAvatar: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current

    val subs by Repo.subs.collectAsState()
    val smart by Repo.smart.collectAsState()
    val selected by Repo.selected.collectAsState()
    val pings by Repo.pings.collectAsState()
    val status by Repo.vpn.collectAsState()
    val vpnServer by Repo.vpnServer.collectAsState()
    val connError by Repo.connError.collectAsState()
    val tunnelMs by Repo.tunnelDelay.collectAsState()

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Читаем состояния, чтобы экран перерисовывался при их смене
    @Suppress("UNUSED_VARIABLE") val touch = listOf(smart, selected, pings, subs)
    val best = Repo.best()
    val current = Repo.current()
    val shown: Server? = if (status == VpnStatus.OFF) current
    else subs.flatMap { it.servers }.firstOrNull { it.id == vpnServer } ?: current

    val scroll = rememberScrollState()
    // 0 — меню закрыто, 1 — меню из трёх точек, 2 — окно исключений
    var menuMode by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = top + 56.dp, bottom = bottom + 28.dp, start = 12.dp, end = 12.dp)
        ) {
            PowerBlock(status, shown, smart, pings, tunnelMs, connError, onPower)
            SmartCard(smart) {
                if (smart) {
                    Repo.setSmart(false)
                    if (Repo.selected.value == null) Repo.selected.value = best?.id
                } else {
                    Repo.setSmart(true)
                }
                onReconnect()
            }
            Spacer(Modifier.height(10.dp))
            SubsCard(subs, best, shown, pings, if (status != VpnStatus.OFF) shown?.id else null, scroll, top, bottom, onPickAvatar) { id ->
                Repo.select(id)
                onReconnect()
            }
        }

        // растворение шапки при прокрутке: проявляется постепенно вместе со скроллом,
        // а сам градиент плавный (кривая ease), без резкой границы
        Box(
            Modifier
                .fillMaxWidth()
                .height(top + 96.dp)
                .graphicsLayer { alpha = (scroll.value / 90f).coerceIn(0f, 1f) }
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

        // шапка: слева смена темы, логотип и заголовок по центру, справа три точки с меню
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = top)
                .height(56.dp)
        ) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(p.primary),
                    modifier = Modifier.size(22.dp, 24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_name),
                    color = p.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { Repo.setThemeDark(!p.dark) },
                contentAlignment = Alignment.Center
            ) {
                // в тёмной теме показываем солнце (переключить на светлую), в светлой — луну
                ThemeIcon(p.dark, p.primary, 24.dp)
            }
            val dotsAlpha by animateFloatAsState(
                if (menuMode == 0) 1f else 0f,
                tween(150, delayMillis = if (menuMode == 0) 280 else 0),
                label = "dots"
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(48.dp)
                    .graphicsLayer { alpha = dotsAlpha }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = menuMode == 0
                    ) { menuMode = 1 },
                contentAlignment = Alignment.Center
            ) {
                DotsIcon(p.primary, 24.dp)
            }
        }

        BoxScopeHeaderMenu(menuMode, { menuMode = it }) {
            // «Добавить подписку»: молча берём из буфера обмена, без сообщений и всплывашек
            val text = readClipboard(ctx)
            if (text.isNotBlank()) Repo.scope.launch { Repo.addFromText(text) }
        }
    }
}

private fun readClipboard(ctx: Context): String = try {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = cm.primaryClip
    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
} catch (e: Exception) {
    ""
}

// ── кнопка включения ─────────────────────────────────────────────────────

@Composable
private fun PowerBlock(
    status: VpnStatus,
    shown: Server?,
    smart: Boolean,
    pings: Map<String, Int>,
    tunnelMs: Int,
    connError: String,
    onPower: () -> Unit,
) {
    val p = LocalPalette.current
    val on = status == VpnStatus.ON
    val active = status != VpnStatus.OFF

    val iconColor by animateColorAsState(if (active) p.primary else p.text2, tween(500), label = "icon")
    val a by animateColorAsState(
        if (on) lerp(p.ga, p.primary, 0.26f) else p.ga, tween(500), label = "a"
    )
    val b by animateColorAsState(
        if (on) lerp(p.gb, p.primary, 0.06f) else p.gb, tween(500), label = "b"
    )
    val flat by animateColorAsState(
        if (on) lerp(p.surface, p.primary, 0.20f) else p.surface, tween(500), label = "flat"
    )
    val shadow by animateColorAsState(
        if (on) p.primary.copy(alpha = 0.45f) else if (p.dark) Color(0x99000000) else Color(0x33783C50), tween(500), label = "shadow"
    )
    val ringAlpha by animateFloatAsState(
        if (status == VpnStatus.CONNECTING) 1f else 0f, tween(450), label = "ring"
    )

    Column(
        Modifier.fillMaxWidth().animateContentSize(tween(300, easing = FastOutSlowInEasing)).padding(top = 28.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(132.dp)
                    .glass(p, CircleShape, a, b, 14.dp, shadow, flat)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPower
                    ),
                contentAlignment = Alignment.Center
            ) {
                PowerIcon(iconColor, 50.dp)
            }
            // кольцо загрузки: плавно появляется вокруг кнопки и исчезает после подключения
            if (ringAlpha > 0.01f) Ring(p.primary, ringAlpha)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                when (status) {
                    VpnStatus.OFF -> R.string.status_off
                    VpnStatus.CONNECTING -> R.string.status_connecting
                    VpnStatus.ON -> R.string.status_on
                }
            ),
            color = if (on) p.primary else p.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
        val sub = when {
            status == VpnStatus.OFF && connError.isNotBlank() -> "Не подключилось: $connError"
            shown == null -> stringResource(R.string.hint_tap)
            status == VpnStatus.OFF && smart -> stringResource(R.string.smart_prefix, shown.name)
            status == VpnStatus.ON -> {
                // реальная задержка через туннель, пока она замерена; иначе пинг до сервера
                val ms = tunnelMs.takeIf { it > 0 } ?: pings[shown.id]
                if (ms != null && ms > 0) "${shown.name} · $ms мс" else shown.name
            }
            else -> shown.name
        }
        Text(
            sub,
            color = if (status == VpnStatus.OFF && connError.isNotBlank()) p.primary else p.text2,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Ring(color: Color, alpha: Float) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Canvas(Modifier.requiredSize(140.dp).graphicsLayer { this.alpha = alpha }) {
        val r = 68.dp.toPx()
        val c = center
        rotateScope(angle, c) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 98f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun PowerIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val w = 2.2f * s
        drawLine(color, Offset(12f * s, 3f * s), Offset(12f * s, 11f * s), strokeWidth = w, cap = StrokeCap.Round)
        // дуга «кнопки питания»: центр (12; 12.61), радиус 8, разрыв сверху
        drawArc(
            color = color,
            startAngle = 225.6f,
            sweepAngle = -271.2f,
            useCenter = false,
            topLeft = Offset(4f * s, 4.61f * s),
            size = Size(16f * s, 16f * s),
            style = Stroke(w, cap = StrokeCap.Round)
        )
    }
}

// ── умное подключение ────────────────────────────────────────────────────

@Composable
private fun SmartCard(smart: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(p, shape, p.ga, p.gb, 8.dp, if (p.dark) Color(0x99000000) else Color(0x26783C50), p.surface)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.smart_title),
                color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(R.string.smart_desc),
                color = p.text2, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Switch(smart, onToggle)
    }
}

@Composable
private fun Switch(on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (on) p.primary else p.surface, tween(300), label = "track")
    val thumb by animateColorAsState(if (on) p.onPrimary else p.text2, tween(300), label = "thumb")
    val x by animateDpAsState(if (on) 22.dp else 4.dp, tween(300), label = "x")
    Box(
        Modifier
            .size(46.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
    ) {
        Box(
            Modifier
                .offset(x = x, y = 4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

// ── подписки и серверы ───────────────────────────────────────────────────

@Composable
private fun SubsCard(
    subs: List<Sub>,
    best: Server?,
    current: Server?,
    pings: Map<String, Int>,
    /** Сервер, к которому сейчас подключены: остаётся в списке, даже если пинг не прошёл. */
    keepId: String?,
    scroll: ScrollState,
    top: Dp,
    bottom: Dp,
    onAvatar: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalPalette.current
    val shut = remember { mutableStateMapOf<String, Boolean>() }
    val res = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dens = LocalDensity.current
    val view = LocalView.current
    val gapPx = with(dens) { 10.dp.toPx() }
    // зоны у краёв экрана: если держать подписку там, список сам прокручивается
    val zonePx = with(dens) { 72.dp.toPx() }
    val topLimitPx = with(dens) { (top + 64.dp).toPx() }
    val bottomInsetPx = with(dens) { bottom.toPx() }
    val tops = remember { mutableMapOf<String, Float>() }
    var pointerY by remember { mutableStateOf(0f) }

    // порядок на время перетаскивания; пересоздаётся, когда меняется состав или порядок в хранилище
    val ids = subs.map { it.id }
    val order = remember(ids) { mutableStateListOf<String>().apply { addAll(ids) } }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val anims = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }

    // перетащили за середину соседа — меняемся с ним местами, сосед плавно доезжает на новое место
    fun step() {
        val cur = dragId ?: return
        val i = order.indexOf(cur)
        if (i < 0) return
        val h = (heights[cur] ?: return).toFloat()
        if (i < order.lastIndex) {
            val nid = order[i + 1]
            val nh = (heights[nid] ?: 0).toFloat()
            if (dragDy > (nh + gapPx) / 2f) {
                order.removeAt(i)
                order.add(i + 1, cur)
                dragDy -= nh + gapPx
                anims[nid]?.let { a -> scope.launch { a.snapTo(h + gapPx); a.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) } }
                return
            }
        }
        if (i > 0) {
            val pid = order[i - 1]
            val ph = (heights[pid] ?: 0).toFloat()
            if (dragDy < -(ph + gapPx) / 2f) {
                order.removeAt(i)
                order.add(i - 1, cur)
                dragDy += ph + gapPx
                anims[pid]?.let { a -> scope.launch { a.snapTo(-(h + gapPx)); a.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) } }
            }
        }
    }

    fun drop() {
        val cur = dragId ?: return
        val a = anims[cur]
        val rest = dragDy
        scope.launch {
            a?.snapTo(rest)
            dragId = null
            dragDy = 0f
            a?.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        Repo.reorderSubs(order.toList())
    }

    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val y = pointerY
            val upEdge = topLimitPx + zonePx
            val downEdge = view.height - bottomInsetPx - zonePx
            val v = when {
                y < upEdge -> -((upEdge - y) / zonePx).coerceIn(0f, 1f)
                y > downEdge -> ((y - downEdge) / zonePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (v != 0f) {
                val moved = scroll.scrollBy(v * 16f)
                // список поехал под пальцем — подписка должна остаться под ним
                if (moved != 0f) {
                    dragDy += moved
                    step()
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (subs.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)) {
                Text(
                    stringResource(R.string.empty_subs),
                    color = p.text2, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)
                )
            }
        }
        order.toList().forEach { id ->
            val sub = subs.firstOrNull { it.id == id } ?: return@forEach
            key(id) {
                val anim = remember { Animatable(0f) }.also { anims[id] = it }
                val lifted = dragId == id
                val lift by animateFloatAsState(if (lifted) 1.02f else 1f, tween(160), label = "lift")
                // недоступные серверы (пинг не прошёл) скрываются сами и возвращаются, когда снова отвечают
                val shownCount = sub.servers.count { pings[it.id] != -1 || it.id == keepId }
                val closed = shut[sub.id] == true
                // состояние читаем только в graphicsLayer: во время анимации экран не перекомпонуется
                val rot = animateFloatAsState(if (closed) -90f else 0f, tween(320, easing = FastOutSlowInEasing), label = "chev")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (lifted) 1f else 0f)
                        .onSizeChanged { heights[id] = it.height }
                        .onGloballyPositioned { tops[id] = it.positionInWindow().y }
                        .graphicsLayer {
                            translationY = if (lifted) dragDy else anim.value
                            scaleX = lift
                            scaleY = lift
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.surface)
                        // удержание поднимает подписку, дальше её можно двигать вверх и вниз
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    dragId = id
                                    dragDy = 0f
                                    pointerY = (tops[id] ?: 0f) + start.y
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDy += amount.y
                                    pointerY = (tops[id] ?: 0f) + dragDy + change.position.y
                                    step()
                                },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() },
                            )
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { shut[sub.id] = !closed }
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(sub) { onAvatar(sub.id) }
                        Spacer(Modifier.width(2.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                sub.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val n = res.getQuantityString(R.plurals.servers, shownCount, shownCount)
                            Text(
                                if (sub.info.isBlank()) n else "$n · ${sub.info}",
                                color = p.text2, fontSize = 12.sp, lineHeight = 16.sp
                            )
                        }
                        Box(Modifier.graphicsLayer { rotationZ = rot.value }) { PolyIcon(CHEVRON, p.text2, 22.dp) }
                    }
                    Collapsible(expanded = !closed) {
                        Column {
                            // серверы без ответа уходят и возвращаются плавно, а не рывком
                            sub.servers.forEach { s ->
                                key(s.id) {
                                    ServerSlot(
                                        s, s.id == current?.id, s.id == best?.id, pings[s.id],
                                        pings[s.id] != -1 || s.id == keepId
                                    ) { onPick(s.id) }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Плавное раскрытие и сворачивание списка. Высота, прозрачность и небольшой сдвиг идут от одного
 * прогресса, поэтому содержимое не пропадает раньше, чем уходит место под ним. Прогресс читается
 * только на этапах измерения и отрисовки: во время анимации ничего не перекомпонуется.
 */
@Composable
private fun Collapsible(expanded: Boolean, content: @Composable () -> Unit) {
    val progress = animateFloatAsState(
        if (expanded) 1f else 0f,
        tween(340, easing = FastOutSlowInEasing),
        label = "collapse"
    )
    val alive by remember { derivedStateOf { progress.value > 0.001f } }
    if (!expanded && !alive) return
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val k = progress.value
                val h = (placeable.height * k).roundToInt()
                val shift = ((k - 1f) * placeable.height * 0.2f).roundToInt()
                layout(placeable.width, h) { placeable.place(0, shift) }
            }
            .clipToBounds()
            .graphicsLayer { alpha = progress.value }
    ) { content() }
}

/**
 * Маленькая круглая аватарка рядом с названием подписки. Пока картинки нет — кружок с «+»,
 * нажатие открывает галерею. Область нажатия 44 dp, чтобы попадать пальцем.
 */
@Composable
private fun Avatar(sub: Sub, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bmp by produceState<ImageBitmap?>(null, sub.avatar) {
        value = sub.avatar?.let { name -> withContext(Dispatchers.IO) { Repo.loadAvatar(name) } }
    }
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (b == null) p.primary.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (b != null) {
                Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                PolyIcon(PLUS, p.primary, 18.dp)
            }
        }
    }
}

/** Строка сервера, которая плавно сворачивается, когда сервер не отвечает, и так же плавно возвращается. */
@Composable
private fun ServerSlot(s: Server, selected: Boolean, best: Boolean, ping: Int?, visible: Boolean, onClick: () -> Unit) {
    // пока строка уходит, оставляем последний рабочий пинг, а не «—»
    val last = remember { arrayOf(ping) }
    if (ping != -1) last[0] = ping
    Collapsible(expanded = visible) {
        ServerRow(s, selected, best, if (ping == -1) last[0] else ping, onClick)
    }
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, best: Boolean, ping: Int?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val nameColor = if (selected) p.primary else p.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            s.cc,
            color = if (selected) p.primary else p.text2,
            fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
            modifier = Modifier.width(32.dp)
        )
        Text(
            s.name,
            color = nameColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            when {
                ping == null -> "…"
                ping < 0 -> "—"
                else -> "$ping мс"
            },
            color = if (best && (ping ?: -1) > 0) p.primary else p.text2,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp)
        )
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) PolyIcon(CHECK, p.primary, 20.dp)
        }
    }
}

// ── иконки: просто знак, без подложек ───────────────────────────────────

private val PLUS = listOf(
    19f to 13f, 13f to 13f, 13f to 19f, 11f to 19f, 11f to 13f, 5f to 13f,
    5f to 11f, 11f to 11f, 11f to 5f, 13f to 5f, 13f to 11f, 19f to 11f
)
private val CHEVRON = listOf(
    7.41f to 8.59f, 12f to 13.17f, 16.59f to 8.59f, 18f to 10f, 12f to 16f, 6f to 10f
)
private val CHECK = listOf(
    9f to 16.17f, 4.83f to 12f, 3.41f to 13.41f, 9f to 19f, 21f to 7f, 19.59f to 5.59f
)

@Composable
private fun PolyIcon(points: List<Pair<Float, Float>>, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x * k, y * k) else path.lineTo(x * k, y * k)
        }
        path.close()
        drawPath(path, color)
    }
}
