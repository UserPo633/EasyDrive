package com.easydrive.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import com.easydrive.app.domain.HandoverRecord
import com.easydrive.app.domain.WarningScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easydrive.app.EasyDriveApp
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.WarningEvent
import com.easydrive.app.domain.ActiveWarning
import com.easydrive.app.domain.AppController
import com.easydrive.app.domain.CheckinReport
import com.easydrive.app.domain.PracticeTask
import com.easydrive.app.domain.Severity
import com.easydrive.app.domain.TaskStatus
import com.easydrive.app.domain.TelltaleGuide
import com.easydrive.app.vehicle.NaviDestination
import com.easydrive.app.vehicle.Read
import com.easydrive.app.vehicle.Restriction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E141B)
private val CardBg = Color(0xFF18212B)
private val Accent = Color(0xFF3B8BFF)
private val Ok = Color(0xFF3DDC97)
private val Warn = Color(0xFFFFB020)
private val Stop = Color(0xFFFF5A5F)
private val Muted = Color(0xFF8A97A6)
private val TextMain = Color(0xFFF2F5F8)

internal enum class Tab(val label: String, val icon: ImageVector) {
    CHECKIN("체크인", Icons.Filled.List),
    PRACTICE("손으로 익히기", Icons.Filled.Build),
    WARNINGS("차량 경고", Icons.Filled.Warning),
    RETURN("반납", Icons.Filled.ExitToApp),
}

class MainActivity : ComponentActivity() {
    private val barInsets = mutableStateOf(BarInsets())
    private val startTab = mutableStateOf(Tab.CHECKIN)

    /**
     * 화면을 지정해 여는 방법(시연·점검용):
     * `adb shell am start -n com.easydrive.app/.ui.MainActivity --es tab practice` (checkin·practice·warnings·return)
     */
    private fun applyIntent(intent: Intent?) {
        val tab = when (intent?.getStringExtra("tab")) {
            "checkin" -> Tab.CHECKIN
            "practice" -> Tab.PRACTICE
            "warnings" -> Tab.WARNINGS
            "return" -> Tab.RETURN
            else -> return
        }
        startTab.value = tab
        // 실습 화면으로 바로 열면 화면이 그려지기를 기다리지 않고 차량 상태 확인을 시작한다.
        if (tab == Tab.PRACTICE) (application as EasyDriveApp).controller.startPractice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as EasyDriveApp).controller
        applyIntent(intent)
        // 시스템 바 영역은 앱이 직접 비운다. Pleos Connect 에뮬레이터(v2.1.0)에서는 위·아래 시스템 바가
        // 앱 창 위에 겹쳐 그려지는데 인셋이 창에 전달되지 않아, 맡겨 두면 제목과 버튼이 바에 가려진다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets -> updateBarInsets(insets); insets }
        setContent { EasyDriveRoot(controller, startTab = startTab.value, insets = barInsets.value) }
    }

    override fun onResume() {
        super.onResume()
        updateBarInsets(ViewCompat.getRootWindowInsets(window.decorView))
        // 기본 차량 화면을 다녀오면 콜백이 유지됐다고 가정하지 않고 다시 읽는다.
        (application as EasyDriveApp).controller.refreshReport()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBarInsets(ViewCompat.getRootWindowInsets(window.decorView))
    }

    /** 시스템 바가 차지하는 영역을 ① 창에 전달된 인셋 ② WindowMetrics ③ 화면 구성 정보 순으로 찾는다. */
    private fun updateBarInsets(dispatched: WindowInsetsCompat?) {
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val d = dispatched?.getInsetsIgnoringVisibility(types) ?: Insets.NONE
        var m = Insets.NONE
        var winH = window.decorView.height
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val wm = windowManager.currentWindowMetrics
            val i = wm.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            m = Insets.of(i.left, i.top, i.right, i.bottom)
            if (winH <= 0) winH = wm.bounds.height()
        }
        var result = BarInsets(maxOf(d.left, m.left), maxOf(d.top, m.top), maxOf(d.right, m.right), maxOf(d.bottom, m.bottom))
        var source = "insets"
        if (result.top == 0 && result.bottom == 0) {
            val cfg = resources.configuration
            val usableH = (cfg.screenHeightDp * resources.displayMetrics.density).toInt()
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBar = if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
            result = barInsetsFromConfiguration(winH, usableH, cfg.toString(), statusBar)
            source = "configuration"
        }
        if (result != barInsets.value) {
            Log.i("EasyDrive", "bar insets=$result source=$source dispatched=$d metrics=$m window=${window.decorView.width}x$winH")
            barInsets.value = result
        }
    }
}

/** 시스템 바에 가려지는 창 가장자리(px). */
internal data class BarInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

private val BOUNDS = Regex("""m(App)?Bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

/**
 * 인셋이 전달되지 않을 때의 마지막 수단. 창 높이보다 '앱이 쓸 수 있는 높이'(Configuration.screenHeightDp)가
 * 작으면 그 차이가 시스템 바에 가려지는 부분이다. 위·아래로 나누는 기준은 구성 정보의 창 영역(mBounds)과
 * 앱 영역(mAppBounds)이다. Pleos Connect Emulator v2.1.0에서 확인한 두 경우:
 *  - 전체 화면: 창 (0,0)-(1280,720), 앱 영역 아래 580, 쓸 수 있는 높이 504 → 위 76(상단 바), 아래 140(하단 바)
 *  - 오른쪽 패널: 창 (853,90)-(2535,1440) = 앱 영역, 쓸 수 있는 높이 1210 → 창이 상단 바 아래에서 시작하므로 아래 140
 * 구성 정보를 읽지 못하면 상태 바 높이만큼을 위쪽으로, 나머지를 아래쪽으로 본다.
 */
internal fun barInsetsFromConfiguration(windowHeightPx: Int, usableHeightPx: Int, configText: String, statusBarPx: Int): BarInsets {
    val missing = windowHeightPx - usableHeightPx
    if (windowHeightPx <= 0 || usableHeightPx <= 0 || missing < 8) return BarInsets()
    var bounds: List<Int>? = null
    var app: List<Int>? = null
    for (m in BOUNDS.findAll(configText)) {
        val r = m.groupValues.drop(2).map { it.toInt() }
        if (m.groupValues[1] == "App") { if (app == null) app = r } else if (bounds == null) bounds = r
    }
    val b = bounds; val a = app
    if (b != null && a != null && b[3] - b[1] == windowHeightPx) {
        var bottom = (b[3] - a[3]).coerceIn(0, missing)
        var top = (a[1] - b[1]).coerceIn(0, missing - bottom)
        val rest = missing - bottom - top
        // 남는 높이: 창이 화면 맨 위에서 시작하면 상단 바가 덮는 것이고, 아래에서 시작하면 하단 바가 덮는 것이다.
        if (b[1] <= 0) top += rest else bottom += rest
        return BarInsets(top = top, bottom = bottom)
    }
    val top = statusBarPx.coerceIn(0, missing)
    return BarInsets(top = top, bottom = missing - top)
}

/** 테마까지 포함한 앱 최상위 화면(화면 캡처 테스트에서도 같은 코드를 쓴다). */
@Composable
internal fun EasyDriveRoot(c: AppController, startTab: Tab = Tab.CHECKIN, insets: BarInsets = BarInsets()) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF06101F), background = Bg, surface = CardBg)) {
        val pad = with(LocalDensity.current) {
            PaddingValues(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())
        }
        Box(Modifier.fillMaxSize().background(Bg).padding(pad)) {
            ScaledToDisplay { EasyDriveScreen(c, startTab) }
        }
    }
}

/**
 * 한글은 기본 줄바꿈 규칙에서 낱말 중간('성/에 제거')에서도 줄이 바뀐다. 낱말 안의 글자 사이에
 * 줄바꿈 금지 문자(U+2060)를 넣어 띄어쓰기에서만 줄이 바뀌게 하고, 숫자와 단위 사이 공백은 줄바꿈 없는 공백으로 바꾼다.
 * 아주 긴 낱말은 좁은 카드에서 넘치지 않게 그대로 둔다.
 */
internal fun keepWords(text: String): String {
    if (text.length < 2) return text
    val sb = StringBuilder(text.length * 2)
    for ((i, word) in text.replace(Regex("(\\d) (km|%)"), "$1\u00A0$2").split(' ').withIndex()) {
        if (i > 0) sb.append(' ')
        if (word.length > MAX_KEEP_WORD) sb.append(word) else word.forEachIndexed { k, ch -> if (k > 0) sb.append('\u2060'); sb.append(ch) }
    }
    return sb.toString()
}
internal const val MAX_KEEP_WORD = 18

/** 이 파일의 모든 글자는 이 함수를 거쳐 그린다(낱말 단위 줄바꿈 적용). */
@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) = androidx.compose.material3.Text(
    text = keepWords(text), modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
    lineHeight = lineHeight, maxLines = maxLines, softWrap = softWrap, overflow = overflow,
)

/** 화면 설계 기준(dp): 가로 1280 × 세로 720. 이보다 큰 화면에서는 같은 비율로 키운다. */
internal const val DESIGN_HEIGHT_DP = 720f
/** 키운 뒤에도 이 폭은 남도록 배율을 제한한다(창을 반으로 나눠 쓰는 경우). */
internal const val MIN_EFFECTIVE_WIDTH_DP = 640f
/** 가로 3칸 배치가 넉넉하게 들어가는 폭. */
internal const val COMFORT_WIDTH_DP = 1100f
/** 가로 배치를 지키려고 배율을 낮출 때의 하한(이보다 작아지면 글자가 작아져 세로 배치로 바꾼다). */
internal const val MIN_WIDE_SCALE = 1.4f
/** 배율을 적용한 뒤의 폭이 이보다 좁으면 세로로 쌓는 배치로 바꾼다. */
internal const val NARROW_BELOW_DP = 900f

/**
 * 화면 배율. Pleos Connect Emulator v2.1.0의 기기 정의는 2560x1440px·160dpi(=2560x1440dp)라서,
 * 휴대폰·태블릿 기준 dp로 그리면 글자와 버튼이 절반 크기로 보인다. 창 높이에 맞춰 통째로 키우되
 * 설계 기준보다 작은 화면에서는 줄이지 않고(글자가 작아지지 않게) 세로 스크롤에 맡긴다.
 */
internal fun uiScale(widthDp: Int, heightDp: Int): Float {
    val byHeight = (heightDp / DESIGN_HEIGHT_DP).coerceIn(1f, 2.5f)
    // 높이에 맞춰 키워도 가로 3칸 배치가 넉넉하면 그대로 쓴다.
    if (widthDp / byHeight >= COMFORT_WIDTH_DP) return byHeight
    // 조금 모자라면(Pleos 홈 화면 오른쪽 패널 1682x1210dp) 배율을 약간 낮춰 가로 배치를 지킨다.
    val byWidth = widthDp / COMFORT_WIDTH_DP
    if (byWidth >= MIN_WIDE_SCALE) return byWidth
    // 그보다 좁으면 세로로 쌓는 배치에 맞춰, 폭이 남는 만큼만 키운다.
    return minOf(byHeight, widthDp / MIN_EFFECTIVE_WIDTH_DP).coerceIn(1f, 2.5f)
}

/** 배율 적용 후 폭이 좁으면(창 분할·세로 화면) 카드를 세로로 쌓고 메뉴를 위로 올린다. */
internal fun isNarrow(widthDp: Int, heightDp: Int): Boolean = widthDp / uiScale(widthDp, heightDp) < NARROW_BELOW_DP

private val LocalNarrow = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun ScaledToDisplay(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val scale = uiScale(cfg.screenWidthDp, cfg.screenHeightDp)
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        LocalNarrow provides isNarrow(cfg.screenWidthDp, cfg.screenHeightDp),
        content = content,
    )
}

/** 넓은 화면에서는 가로로 나란히, 좁은 화면에서는 세로로 쌓는다. */
@Composable
private fun Cells(vararg items: @Composable (Modifier) -> Unit) {
    if (LocalNarrow.current) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { items.forEach { it(Modifier.fillMaxWidth()) } }
    } else {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun EasyDriveScreen(c: AppController, startTab: Tab) {
    val restriction by c.restriction.state.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    var tab by remember(startTab) { mutableStateOf(startTab) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (restriction != Restriction.PARKED_OK) {
            LockedScreen(restriction)
            return@Box
        }
        val pendingCount = events.count { it.whileRestricted && !it.acknowledged }
        val body: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().padding(if (LocalNarrow.current) 20.dp else 28.dp)) {
                val pending = events.filter { it.whileRestricted && !it.acknowledged }
                if (pending.isNotEmpty() && tab != Tab.WARNINGS) {
                    Banner("주행 중 켜진 경고 ${pending.size}개가 있어요. 눌러서 확인하세요.") { tab = Tab.WARNINGS }
                    Spacer(Modifier.height(16.dp))
                }
                when (tab) {
                    Tab.CHECKIN -> CheckinTab(c, onOpen = { tab = it })
                    Tab.PRACTICE -> PracticeTab(c)
                    Tab.WARNINGS -> WarningsTab(c)
                    Tab.RETURN -> ReturnTab(c)
                }
            }
        }
        if (LocalNarrow.current) {
            Column(Modifier.fillMaxSize()) { TopTabs(tab, pendingCount) { tab = it }; body() }
        } else {
            Row(Modifier.fillMaxSize()) { NavRail(tab, pendingCount) { tab = it }; body() }
        }
    }
}

/** 좁은 창에서 쓰는 위쪽 메뉴(왼쪽 메뉴와 같은 항목). */
@Composable
private fun TopTabs(current: Tab, pendingCount: Int, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF121A22)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tab.entries.forEach { t ->
            val selected = t == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(t) }.heightIn(min = 76.dp).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = if (selected) Accent else Muted, modifier = Modifier.size(28.dp))
                Text(
                    t.label + if (t == Tab.WARNINGS && pendingCount > 0) " $pendingCount" else "",
                    color = if (selected) TextMain else Muted, fontSize = 18.sp, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun LockedScreen(r: Restriction) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, tint = Muted, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            if (r == Restriction.LOCKED) "주행 중에는 EasyDrive를 잠가 둡니다" else "주행 규제 상태를 확인할 수 없어 잠가 두었습니다",
            color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text("경고는 계기판을 따르세요. 주행이 끝나면 이 화면이 다시 열립니다.", color = Muted, fontSize = 24.sp)
    }
}

@Composable
private fun NavRail(current: Tab, pendingCount: Int, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(248.dp).background(Color(0xFF121A22)).padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("EasyDrive", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
        Tab.entries.forEach { t ->
            val selected = t == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(t) }.heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, null, tint = if (selected) Accent else Muted, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.label, color = if (selected) TextMain else Muted, fontSize = 22.sp, maxLines = 1, softWrap = false)
                if (t == Tab.WARNINGS && pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Warn).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("$pendingCount", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn.copy(alpha = 0.16f))
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = Warn, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = TextMain, fontSize = 22.sp)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).padding(20.dp)) { content() }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    if (sub != null) Text(sub, color = Muted, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
}

private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
private fun dayTime(ms: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))

/** 되돌릴 수 없는 동작 전에 한 번 더 묻는다. */
@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = { Text(body, color = TextMain.copy(alpha = 0.9f), fontSize = 21.sp) },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.heightIn(min = 76.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(confirm, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 76.dp)) { Text("취소", fontSize = 21.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        },
    )
}

// ------------------------------------------------------------------ 체크인

@Composable
private fun CheckinTab(c: AppController, onOpen: (Tab) -> Unit) {
    val r by c.report.collectAsStateWithLifecycle()
    val saved by c.checkin.collectAsStateWithLifecycle()
    var askOverwrite by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!r.loading) c.refreshQuietly() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // 인수 기록을 저장한 뒤에는 '지금 상태'로 보여 줘, 주행 후 값으로 기준을 덮어쓰는 실수를 막는다.
                Title(
                    if (saved == null) "인수 체크인 리포트" else "지금 차량 상태",
                    if (r.loading) "차량에서 읽는 중…" else "차량에서 읽은 시각 ${time(r.readAtMillis)}",
                )
            }
            OutlinedButton(onClick = { c.refreshReport() }, modifier = Modifier.heightIn(min = 76.dp)) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("다시 읽기", fontSize = 21.sp)
            }
        }
        val hasWarnings = (r.warnings as? Read.Ok)?.value?.any { it.explain.severity != Severity.INFO } == true
        Cells(
            { m -> StatusItem(m, "차량 경고", warningsSummary(r.warnings, r.warningScope, r.warningItems), onClick = if (hasWarnings) ({ onOpen(Tab.WARNINGS) }) else null) },
            { m -> StatusItem(m, r.energyLabel ?: "배터리·연료", r.energyPercent.fmt { "$it%" }) },
            { m ->
                val shown = r.rangeKm.fmt { "${it.toInt()} km" }
                val note = destinationNote(r.destination, r.rangeKm)
                StatusItem(m, "주행가능거리", if (note != null) shown.copy(note = note, strongNote = true) else shown)
            },
        )
        Spacer(Modifier.height(16.dp))
        Cells(
            { m -> StatusItem(m, "누적 주행거리", r.odometerKm.fmt { "%,d km".format(it.toInt()) }) },
            { m -> StatusItem(m, "도어·트렁크", openSummary(r.openDoors)) },
            { m -> StatusItem(m, "창문", openSummary(r.openWindows)) },
        )
        Spacer(Modifier.height(22.dp))
        val s = saved
        if (s == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { c.saveCheckin() }, enabled = !r.loading, modifier = Modifier.heightIn(min = 76.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("인수 기록 저장", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text("저장하면 반납 때 비교할 수 있어요", color = Muted, fontSize = 20.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onOpen(Tab.PRACTICE) }, modifier = Modifier.heightIn(min = 76.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("다음: 출발 전 3가지 익히기 ›", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("인수 기록 저장됨 · ${dayTime(s.savedAtMillis)}", color = TextMain, fontSize = 20.sp)
                    // 오래된 기록은 앞 이용자의 것일 수 있다.
                    val days = ((System.currentTimeMillis() - s.savedAtMillis) / 86_400_000L).toInt()
                    if (days >= 3) Text("${days}일 전 기록이에요. 내 기록이 아니면 지우고 새로 저장하세요.", color = Warn, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { askOverwrite = true }, modifier = Modifier.heightIn(min = 60.dp)) { Text("지금 값으로 다시 저장", fontSize = 19.sp) }
                        OutlinedButton(onClick = { askClear = true }, modifier = Modifier.heightIn(min = 60.dp)) { Text("이전 기록 지우기", fontSize = 19.sp) }
                    }
                }
            }
        }
    }
    if (askOverwrite) ConfirmDialog(
        "인수 기록을 바꿀까요?", "처음 저장한 인수 기록이 지금 차량 값으로 바뀝니다. 주행한 뒤라면 반납 때 비교가 달라져요.",
        "지금 값으로 저장", onConfirm = { c.saveCheckin() }, onDismiss = { askOverwrite = false },
    )
    if (askClear) ConfirmDialog(
        "이전 기록을 지울까요?", "이 차에 남아 있던 인수 기록과 경고등 이력을 지웁니다. 되돌릴 수 없어요.",
        "지우기", onConfirm = { c.finishRental() }, onDismiss = { askClear = false },
    )
}

private data class Shown(val text: String, val tone: Color, val note: String? = null, val strongNote: Boolean = false)

/**
 * 내비게이션이 길 안내 중이면 목적지까지 남은 거리를 주행가능거리와 나란히 적는다.
 * 남은 거리가 주행가능거리의 80%를 넘으면 충전 계획을 확인하라고만 알린다(도착 가능 여부를 단정하지 않는다).
 */
internal fun destinationNote(dest: Read<NaviDestination>, rangeKm: Read<Float>): String? {
    val d = (dest as? Read.Ok)?.value ?: return null
    val km = d.remainMeters / 1000
    if (km <= 0) return null
    val head = "${d.name ?: "목적지"}까지 ${km}km"
    val range = (rangeKm as? Read.Ok)?.value ?: return head
    return when {
        km > range -> "$head · 주행가능거리보다 멀어요. 충전 계획을 확인하세요"
        km > range * 0.8f -> "$head · 주행가능거리와 비슷해요. 충전 계획을 확인하세요"
        else -> head
    }
}

private fun <T> Read<T>.fmt(f: (T) -> String): Shown = when (this) {
    is Read.Ok -> Shown(f(value), TextMain)
    Read.Unsupported -> Shown("― 지원 안 함", Muted)
    is Read.Unavailable -> Shown("― 확인 불가", Muted, reason)
}

private fun warningsSummary(r: Read<List<ActiveWarning>>, scope: WarningScope, items: List<String>): Shown = when (r) {
    is Read.Ok -> {
        // 주차 브레이크처럼 정상적으로 켜져 있는 표시(INFO)는 '확인 필요'로 세지 않는다.
        val need = r.value.filter { it.explain.severity != Severity.INFO }
        // '경고 없음'이 차 전체가 정상이라는 뜻으로 읽히지 않게, 무엇을 읽은 결과인지 바로 아래에 적는다.
        val basis = when {
            scope != WarningScope.BASIC -> "차량이 알린 것 기준"
            items.size <= 2 -> "읽은 경고 ${items.size}종(${items.joinToString("·")}) 기준"
            else -> "읽은 경고 ${items.size}종 기준"
        }
        when {
            // 읽은 경고가 한두 종뿐이면 '경고 없음'이 차 전체가 정상이라는 뜻으로 읽힌다. 무엇이 꺼져 있는지만 중립색으로 말한다.
            need.isEmpty() && scope == WarningScope.BASIC && items.size == 1 ->
                Shown("${items[0]}: 꺼짐", TextMain, "앱이 읽는 경고는 이 1종뿐이에요. 계기판 경고등과 타이어는 직접 확인하세요", strongNote = true)
            need.isEmpty() && scope == WarningScope.BASIC && items.size == 2 ->
                Shown("켜진 경고 없음", TextMain, "$basis · 계기판 경고등과 타이어는 직접 확인", strongNote = true)
            need.isEmpty() -> Shown("경고 없음", Ok, "$basis · 계기판 경고등과 타이어는 직접 확인", strongNote = true)
            need.any { it.explain.severity == Severity.STOP } -> Shown("▲ 확인 필요 ${need.size}개", Stop, need.joinToString { it.explain.name } + " · 풀이 보기 ›")
            else -> Shown("▲ 확인 필요 ${need.size}개", Warn, need.joinToString { it.explain.name } + " · 풀이 보기 ›")
        }
    }
    Read.Unsupported -> Shown("― 지원 안 함", Muted, "계기판을 직접 확인하세요")
    is Read.Unavailable -> Shown("― 확인 불가", Muted, r.reason)
}

private fun openSummary(r: Read<List<String>>): Shown = when (r) {
    is Read.Ok -> if (r.value.isEmpty()) Shown("모두 닫힘", Ok) else Shown("▲ 열림 ${r.value.size}곳", Warn, r.value.joinToString())
    Read.Unsupported -> Shown("― 지원 안 함", Muted)
    is Read.Unavailable -> Shown("― 확인 불가", Muted, r.reason)
}

@Composable
private fun StatusItem(modifier: Modifier, label: String, s: Shown, onClick: (() -> Unit)? = null) {
    Card((if (onClick != null) modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick) else modifier).heightIn(min = if (LocalNarrow.current) 96.dp else 156.dp)) {
        Column {
            Text(label, color = Muted, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(s.text, color = s.tone, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (s.note != null) Text(s.note, color = if (s.strongNote) TextMain.copy(alpha = 0.85f) else Muted, fontSize = if (s.strongNote) 19.sp else 18.sp, maxLines = if (s.strongNote) 4 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ 실습

@Composable
private fun PracticeTab(c: AppController) {
    val tasks by c.tasks.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        c.startPractice()
        onDispose { c.stopPractice() }
    }
    val blocked by c.practiceBlocked.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        val core = tasks.filter { !it.extra }
        // 더 익혀 둘 조작은 이 차가 확인해 줄 수 있는 것만 보여 준다.
        val extras = tasks.filter { it.extra && it.status in setOf(TaskStatus.WAITING, TaskStatus.ALREADY_SET, TaskStatus.DONE) }
        Title("출발 전 3가지만 손으로 익혀요", "실제 레버·버튼을 조작하면 차량이 확인해 줍니다. 차량이 확인한 것만 완료로 표시해요.")
        if (blocked) {
            // 기어가 P가 아니면 조작을 시키지 않는다.
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text("기어를 P에 두면 과제가 열려요", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("실습은 주차 상태(P단)에서만 합니다. 지금까지 확인한 과제는 그대로 남아 있어요.", color = Muted, fontSize = 21.sp)
                }
            }
            return@Column
        }
        val s = practiceSummary(core)
        Text(s.text, color = if (s.allSettled) Ok else TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Cells(*core.map<PracticeTask, @Composable (Modifier) -> Unit> { t -> { m -> TaskCard(m, t) } }.toTypedArray())
        if (s.allSettled) {
            Spacer(Modifier.height(14.dp))
            val (text, warn) = afterPracticeNote(tasks)
            Text(text, color = if (warn) Warn else TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            val e = practiceSummary(extras)
            Text("이 차에서 더 익혀 둘 조작 · ${e.text}", color = Muted, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Cells(*extras.map<PracticeTask, @Composable (Modifier) -> Unit> { t -> { m -> TaskCard(m, t) } }.toTypedArray())
        }
    }
}

/**
 * 기본 과제를 마친 뒤의 한 줄. 과제를 마쳤다는 것이 '출발해도 된다'는 뜻으로 읽히지 않게,
 * 열거나 켠 채인 것이 있으면 그것부터 되돌리라고 하고(주황), 없으면 앱이 보지 않는 것을 직접 확인하라고 한다.
 */
internal fun afterPracticeNote(tasks: List<PracticeTask>): Pair<String, Boolean> {
    val leftOn = tasks.filter { it.status == TaskStatus.DONE && it.attention }.map { it.title.substringBefore(' ') }
    val confirmed = tasks.count { it.status == TaskStatus.DONE }
    return if (leftOn.isNotEmpty()) "${leftOn.joinToString("·")}을 되돌린 뒤 출발하세요" to true
    else "기본 3가지 확인을 마쳤어요(차량이 확인한 조작 ${confirmed}개 기록). 문·안전벨트·계기판은 출발 전에 직접 확인하세요" to false
}

internal class PracticeSummary(val text: String, val allSettled: Boolean)

/**
 * 과제 묶음의 진행 문구. 운전자가 조작해서 차량이 확인한 것(완료)과, 조작 없이 이미 맞춰져 있던 것(이미 설정됨)을
 * 따로 센다. 이 차에 없는 기능은 분모에서 뺀다(끝낼 수 없는 과제가 남지 않게).
 */
internal fun practiceSummary(tasks: List<PracticeTask>): PracticeSummary {
    val missing = tasks.count { it.status == TaskStatus.UNSUPPORTED }
    val total = tasks.size - missing
    val done = tasks.count { it.status == TaskStatus.DONE }
    val already = tasks.count { it.status == TaskStatus.ALREADY_SET }
    val text = "${total}개 중 ${done}개 직접 확인" +
        (if (already > 0) " · ${already}개는 이미 설정됨" else "") +
        (if (missing > 0) " · ${missing}개는 앱이 확인 못 함" else "")
    return PracticeSummary(text, total > 0 && done + already == total)
}

@Composable
private fun TaskCard(modifier: Modifier, t: PracticeTask) {
    val (badge, tone) = when (t.status) {
        TaskStatus.CHECKING -> "확인 중" to Muted
        TaskStatus.WAITING -> "해 보세요" to TextMain
        TaskStatus.ALREADY_SET -> "이미 설정됨 · 위치만 확인" to Muted
        TaskStatus.DONE -> "완료" to Ok
        TaskStatus.UNSUPPORTED -> "― 지원 안 함" to Muted
        TaskStatus.UNAVAILABLE -> "― 확인 불가" to Muted
    }
    // 상태에 맞는 안내만 보여 준다(이미 설정됐거나 없는 기능인데 '돌려 보세요'라고 하지 않는다).
    val body = when (t.status) {
        TaskStatus.ALREADY_SET -> t.alreadyText.ifEmpty { t.howTo }
        // 차에 그 기능이 없다는 뜻이 아니라, 앱이 이 차에서 그 상태를 읽지 못한다는 뜻이다.
        TaskStatus.UNSUPPORTED -> "앱이 이 차에서 확인할 수 없는 조작이에요. 위치는 직접 찾아 두세요."
        // 완료한 과제는 '해 보세요' 안내를 지우고 확인 결과만 보여 준다(카드가 짧아져 아래 과제가 가려지지 않는다).
        TaskStatus.DONE -> ""
        else -> t.howTo
    }
    val foot = when (t.status) {
        TaskStatus.ALREADY_SET, TaskStatus.UNSUPPORTED -> ""
        TaskStatus.UNAVAILABLE -> "차량에서 상태를 읽지 못해 완료를 확인할 수 없어요."
        // 추가 과제는 칸이 좁아, 기본 과제에 이미 적힌 '조작하면 자동으로 완료' 안내를 되풀이하지 않는다.
        TaskStatus.WAITING -> if (t.extra && !t.attention) "" else t.detail
        else -> t.detail
    }
    Card(modifier.heightIn(min = if (LocalNarrow.current) 120.dp else if (t.extra) 170.dp else 230.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 체크 표시는 운전자가 조작해서 차량이 확인한 과제에만 붙인다.
                if (t.status == TaskStatus.DONE) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Ok, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(badge, color = tone, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(t.title, color = TextMain, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            if (body.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(body, color = TextMain.copy(alpha = 0.9f), fontSize = 21.sp) }
            val done = t.status == TaskStatus.DONE
            if (foot.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(foot, color = if (t.attention) Warn else if (done) TextMain.copy(alpha = 0.9f) else Muted, fontSize = if (t.attention || done) 20.sp else 18.sp, fontWeight = if (t.attention) FontWeight.Medium else FontWeight.Normal) }
        }
    }
}

// ------------------------------------------------------------------ 경고등

@Composable
private fun WarningsTab(c: AppController) {
    val r by c.report.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("차량 경고 풀이", TelltaleGuide.DISCLAIMER)
        if (r.warningScope == WarningScope.BASIC && r.warningItems.isNotEmpty()) {
            Text("이 차에서 읽을 수 있는 경고: ${r.warningItems.joinToString("·")}. 그 밖의 경고등은 계기판에서 확인하세요.", color = Muted, fontSize = 19.sp)
            Spacer(Modifier.height(16.dp))
        }
        val pending = events.filter { it.whileRestricted && !it.acknowledged }
        val nowOn = (r.warnings as? Read.Ok)?.value
        if (pending.isNotEmpty()) {
            Text("주행 중 켜진 경고", color = Warn, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            pending.forEach { e ->
                // 지금도 켜져 있으면 아래 목록에 한 번 더 보여 주지 않고 이 카드에 함께 표시한다.
                val still = nowOn?.any { it.typeName == e.typeName }
                EventCard(e, when (still) { true -> "지금도 켜져 있음"; false -> "지금은 꺼져 있음"; null -> null })
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(onClick = { c.acknowledgeEvents() }, modifier = Modifier.heightIn(min = 76.dp)) { Text("확인했어요", fontSize = 21.sp) }
            Spacer(Modifier.height(22.dp))
        }
        val pendingTypes = pending.map { it.typeName }.toSet()
        Text("지금 켜져 있는 경고", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        when (val w = r.warnings) {
            is Read.Ok -> {
                val rest = w.value.filter { it.typeName !in pendingTypes }
                when {
                    w.value.isEmpty() -> Text(
                        if (r.warningScope == WarningScope.BASIC) "앱이 읽는 경고 ${r.warningItems.size}종 중 켜진 것이 없어요. 계기판 경고등은 따로 확인하세요."
                        else "읽을 수 있는 경고 중 켜진 것이 없어요.",
                        color = Ok, fontSize = 22.sp,
                    )
                    rest.isEmpty() -> Text(
                        if (r.warningScope == WarningScope.BASIC) "위 경고 말고 켜진 것은 없어요(앱이 읽는 ${r.warningItems.size}종 기준). 계기판 경고등은 따로 확인하세요."
                        else "위에 보여 드린 경고 말고는 없어요.",
                        color = Muted, fontSize = 21.sp,
                    )
                    else -> rest.forEach { WarningCard(it); Spacer(Modifier.height(12.dp)) }
                }
            }
            Read.Unsupported -> Text("이 차량에서는 경고 정보를 제공하지 않아요. 계기판을 직접 확인하세요.", color = Muted, fontSize = 22.sp)
            is Read.Unavailable -> Text("경고 정보를 읽지 못했어요 (${w.reason}). 계기판을 직접 확인하세요.", color = Muted, fontSize = 22.sp)
        }
    }
}

@Composable
private fun WarningCard(w: ActiveWarning) = ExplainCard(
    w.explain.name + if (w.blinking) " (깜박임)" else "", w.explain.meaning, w.explain.action, w.explain.severity, null,
)

@Composable
private fun EventCard(e: WarningEvent, stillOn: String?) {
    val ex = TelltaleGuide.explainByName(e.typeName, e.colorId)
    ExplainCard(ex.name, ex.meaning, ex.action, ex.severity, "주행 중 ${time(e.atMillis)} 켜짐" + (stillOn?.let { " · $it" } ?: ""))
}

@Composable
private fun ExplainCard(name: String, meaning: String, action: String, sev: Severity, meta: String?) {
    val tone = when (sev) { Severity.STOP -> Stop; Severity.CHECK -> Warn; Severity.INFO -> TextMain }
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(18.dp)).background(CardBg)) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(tone))
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (meta != null) { Spacer(Modifier.width(12.dp)); Text(meta, color = Muted, fontSize = 19.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Text("무슨 뜻  $meaning", color = TextMain.copy(alpha = 0.9f), fontSize = 21.sp)
            Spacer(Modifier.height(6.dp))
            Text("먼저 할 일  $action", color = tone, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ------------------------------------------------------------------ 반납

@Composable
private fun ReturnTab(c: AppController) {
    val r by c.report.collectAsStateWithLifecycle()
    val saved by c.checkin.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    var askClear by remember { mutableStateOf(false) }
    if (askClear) ConfirmDialog(
        "기록을 지울까요?", "인수 기록과 경고등 이력을 이 차에서 지웁니다. 다음 이용자에게 보이지 않게 돼요. 되돌릴 수 없어요.",
        "지우기", onConfirm = { c.finishRental() }, onDismiss = { askClear = false },
    )
    LaunchedEffect(Unit) { c.refreshQuietly() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("반납 체크", "인수 때 저장한 값과 지금 차량 값을 비교해요.")
        val s = saved
        if (s == null) {
            Text("저장된 인수 기록이 없어요. 체크인 화면에서 먼저 저장해 주세요.", color = Muted, fontSize = 22.sp)
            return@Column
        }
        Cells(
            { m ->
                CompareItem(m, "주행거리", s.odometerKm?.let { "%,d km".format(it.toInt()) }, r.odometerKm.valueOrNullF()?.let { "%,d km".format(it.toInt()) },
                    diff(s.odometerKm, r.odometerKm.valueOrNullF())?.takeIf { it >= 0 }?.let { "+%,d km 주행".format(it.toInt()) })
            },
            { m ->
                CompareItem(m, s.energyLabel ?: "배터리·연료", s.energyPercent?.let { "$it%" }, (r.energyPercent as? Read.Ok)?.value?.let { "$it%" },
                    diffI(s.energyPercent, (r.energyPercent as? Read.Ok)?.value)?.let { d -> when { d < 0 -> "${-d}% 줄었어요"; d > 0 -> "$d% 늘었어요"; else -> "변화 없음" } })
            },
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("대여 중 새로 켜진 경고 (앱이 실행 중이던 동안)", color = Muted, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                if (events.isEmpty()) Text("앱이 떠 있던 동안 새로 켜진 경고는 기록되지 않았어요. 계기판 경고등은 앱이 읽지 못해요.", color = TextMain.copy(alpha = 0.9f), fontSize = 21.sp)
                events.forEach { e ->
                    Text("· ${time(e.atMillis)}  ${TelltaleGuide.explainByName(e.typeName, e.colorId).name}${if (e.whileRestricted) " (주행 중)" else ""}", color = TextMain, fontSize = 21.sp)
                }
                if (!s.warningsReadable) Text("인수 시 경고 정보는 확인하지 못했어요.", color = Muted, fontSize = 18.sp)
                else if (s.warnings.isNotEmpty()) Text("인수 시 이미 켜져 있던 경고: ${s.warnings.joinToString()}", color = Muted, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        val practiced by c.practiced.collectAsStateWithLifecycle()
        val recordText = remember(s, r.readAtMillis, events, practiced) {
            HandoverRecord.text(
                checkin = s,
                nowOdometerKm = r.odometerKm.valueOrNullF(),
                nowEnergyPercent = (r.energyPercent as? Read.Ok)?.value,
                events = events,
                nowMillis = if (r.readAtMillis > 0) r.readAtMillis else System.currentTimeMillis(),
                eventName = { e -> TelltaleGuide.explainByName(e.typeName, e.colorId).name },
                practiced = practiced,
            )
        }
        Card(Modifier.fillMaxWidth()) {
            val qrText: @Composable () -> Unit = {
                Column {
                    Text("기록을 내 휴대폰으로", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("휴대폰 카메라로 QR을 찍으면 인수·반납 기록이 글자로 열려요. 내 확인용 메모이고, 정산 기준은 운영사 기록이에요.", color = TextMain.copy(alpha = 0.9f), fontSize = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("서버로 보내지 않고, 차대번호·위치는 담지 않아요. 외관은 직접 사진으로 남겨 주세요.", color = Muted, fontSize = 18.sp)
                }
            }
            if (LocalNarrow.current) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { QrView(recordText, Modifier.size(280.dp)); Spacer(Modifier.height(16.dp)); qrText() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { QrView(recordText, Modifier.size(280.dp)); Spacer(Modifier.width(24.dp)); qrText() }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("인수 기록 ${dayTime(s.savedAtMillis)} · 지금 값 ${time(r.readAtMillis)}", color = Muted, fontSize = 19.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { askClear = true }, modifier = Modifier.heightIn(min = 76.dp)) { Text("반납 완료 · 기록 지우기", fontSize = 21.sp) }
        }
    }
}

private fun Read<Float>.valueOrNullF(): Float? = (this as? Read.Ok)?.value
private fun diff(a: Float?, b: Float?) = if (a != null && b != null) b - a else null
private fun diffI(a: Int?, b: Int?) = if (a != null && b != null) b - a else null

@Composable
private fun CompareItem(modifier: Modifier, label: String, before: String?, now: String?, delta: String?) {
    Card(modifier.heightIn(min = if (LocalNarrow.current) 120.dp else 170.dp)) {
        Column {
            Text(label, color = Muted, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            // '인수 12,345 km'와 '지금 12,419 km'가 각각 한 덩어리로 줄바꿈되게 줄바꿈 없는 공백으로 잇는다.
            Text("인수\u00A0${before ?: "확인 불가"}  →  지금\u00A0${now ?: "확인 불가"}", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(delta ?: "비교할 수 없어요", color = if (delta != null) TextMain else Muted, fontSize = 22.sp)
        }
    }
}

/** QR을 흰 바탕 위에 그린다(여백 포함). 생성에 실패하면 안내 문구만 보여 준다. */
@Composable
private fun QrView(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { HandoverRecord.qr(text) }.getOrNull() }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
        if (matrix == null) {
            Text("QR을 만들지 못했어요", color = Color.Black, fontSize = 16.sp)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.size
                val cell = kotlin.math.floor(size.minDimension / n)
                val off = (size.minDimension - cell * n) / 2f
                for (y in 0 until n) for (x in 0 until n) {
                    if (matrix[y][x]) drawRect(
                        Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(off + x * cell, off + y * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}
