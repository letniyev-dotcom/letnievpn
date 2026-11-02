package app.letnievpn.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
fun MainScreen(onPower: () -> Unit, onReconnect: () -> Unit) {
    LetnieTheme { Content(onPower, onReconnect) }
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
): Modifier = if (p.dark) {
    this.clip(shape).background(flatColor, shape)
} else {
    this
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
}

@Composable
private fun Content(onPower: () -> Unit, onReconnect: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current

    val subs by Repo.subs.collectAsState()
    val smart by Repo.smart.collectAsState()
    val selected by Repo.selected.collectAsState()
    val pings by Repo.pings.collectAsState()
    val status by Repo.vpn.collectAsState()
    val vpnServer by Repo.vpnServer.collectAsState()
    val addError by Repo.lastError.collectAsState()

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Читаем состояния, чтобы экран перерисовывался при их смене
    @Suppress("UNUSED_VARIABLE") val touch = listOf(smart, selected, pings, subs)
    val best = Repo.best()
    val current = Repo.current()
    val shown: Server? = if (status == VpnStatus.OFF) current
    else subs.flatMap { it.servers }.firstOrNull { it.id == vpnServer } ?: current

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = top + 56.dp, bottom = bottom + 28.dp, start = 12.dp, end = 12.dp)
        ) {
            PowerBlock(status, shown, smart, pings, onPower)
            SmartCard(smart) {
                if (smart) {
                    Repo.setSmart(false)
                    if (Repo.selected.value == null) Repo.selected.value = best?.id
                } else {
                    Repo.setSmart(true)
                }
                onReconnect()
            }
            Spacer(Modifier.height(12.dp))
            SubsCard(subs, best, current, pings, addError) { id ->
                Repo.select(id)
                onReconnect()
            }
        }

        // растворение шапки при прокрутке
        Box(
            Modifier
                .fillMaxWidth()
                .height(top + 74.dp)
                .background(
                    Brush.verticalGradient(
                        0f to p.bg, 0.6f to p.bg, 1f to p.bg.copy(alpha = 0f)
                    )
                )
        )

        // шапка: логотип и заголовок вместе по центру, справа «+»
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
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // «+»: молча берём подписку из буфера обмена, без сообщений и всплывашек
                        val text = readClipboard(ctx)
                        if (text.isNotBlank()) Repo.scope.launch { Repo.addFromText(text) }
                    },
                contentAlignment = Alignment.Center
            ) {
                PolyIcon(PLUS, p.primary, 26.dp)
            }
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
        if (on) p.primary.copy(alpha = 0.45f) else Color(0x33783C50), tween(500), label = "shadow"
    )
    val ringAlpha by animateFloatAsState(
        if (status == VpnStatus.CONNECTING) 1f else 0f, tween(450), label = "ring"
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(148.dp)
                    .glass(p, CircleShape, a, b, 14.dp, shadow, flat)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPower
                    ),
                contentAlignment = Alignment.Center
            ) {
                PowerIcon(iconColor, 56.dp)
            }
            // кольцо загрузки: плавно появляется вокруг кнопки и исчезает после подключения
            if (ringAlpha > 0.01f) Ring(p.primary, ringAlpha)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(
                when (status) {
                    VpnStatus.OFF -> R.string.status_off
                    VpnStatus.CONNECTING -> R.string.status_connecting
                    VpnStatus.ON -> R.string.status_on
                }
            ),
            color = if (on) p.primary else p.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        val sub = when {
            shown == null -> stringResource(R.string.hint_tap)
            status == VpnStatus.OFF && smart -> stringResource(R.string.smart_prefix, shown.name)
            status == VpnStatus.ON -> {
                val ms = pings[shown.id]
                if (ms != null && ms > 0) "${shown.name} · $ms мс" else shown.name
            }
            else -> shown.name
        }
        Text(
            sub,
            color = p.text2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
            maxLines = 1,
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
    Canvas(Modifier.requiredSize(156.dp).graphicsLayer { this.alpha = alpha }) {
        val r = 76.dp.toPx()
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
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(p, shape, p.ga, p.gb, 8.dp, Color(0x26783C50), p.surface)
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.smart_title),
                color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(R.string.smart_desc),
                color = p.text2, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
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
    val x by animateDpAsState(if (on) 24.dp else 4.dp, tween(300), label = "x")
    Box(
        Modifier
            .size(52.dp, 32.dp)
            .clip(RoundedCornerShape(16.dp))
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
                .size(24.dp)
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
    error: String,
    onPick: (String) -> Unit,
) {
    val p = LocalPalette.current
    val shut = remember { mutableStateMapOf<String, Boolean>() }
    val res = LocalContext.current.resources

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.surface)
            .padding(vertical = 8.dp)
    ) {
        if (subs.isEmpty()) {
            Text(
                stringResource(R.string.empty_subs),
                color = p.text2, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)
            )
            if (error.isNotBlank()) {
                Text(
                    "Не добавилось: $error",
                    color = p.primary, fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                )
            }
        }
        subs.forEach { sub ->
            val closed = shut[sub.id] == true
            val rot by animateFloatAsState(if (closed) -90f else 0f, tween(250), label = "chev")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { shut[sub.id] = !closed }
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sub.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val n = res.getQuantityString(R.plurals.servers, sub.servers.size, sub.servers.size)
                    Text(
                        if (sub.info.isBlank()) n else "$n · ${sub.info}",
                        color = p.text2, fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
                Box(Modifier.rotate(rot)) { PolyIcon(CHEVRON, p.text2, 24.dp) }
            }
            if (!closed) {
                sub.servers.forEach { s ->
                    ServerRow(s, s.id == current?.id, s.id == best?.id, pings[s.id]) { onPick(s.id) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, best: Boolean, ping: Int?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val nameColor = if (selected) p.primary else p.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            s.cc,
            color = if (selected) p.primary else p.text2,
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
            modifier = Modifier.width(34.dp)
        )
        Text(
            s.name,
            color = nameColor, fontSize = 15.sp, fontWeight = FontWeight.Medium,
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
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp)
        )
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) PolyIcon(CHECK, p.primary, 22.dp)
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
