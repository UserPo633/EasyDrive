package com.easydrive.app.ui

import android.os.Bundle
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
    WARNINGS("경고등", Icons.Filled.Warning),
    RETURN("반납", Icons.Filled.ExitToApp),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as EasyDriveApp).controller
        setContent { EasyDriveRoot(controller) }
    }

    override fun onResume() {
        super.onResume()
        // 기본 차량 화면을 다녀오면 콜백이 유지됐다고 가정하지 않고 다시 읽는다.
        (application as EasyDriveApp).controller.refreshReport()
    }
}

/** 테마까지 포함한 앱 최상위 화면(화면 캡처 테스트에서도 같은 코드를 쓴다). */
@Composable
internal fun EasyDriveRoot(c: AppController, startTab: Tab = Tab.CHECKIN) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF06101F), background = Bg, surface = CardBg)) {
        EasyDriveScreen(c, startTab)
    }
}

@Composable
private fun EasyDriveScreen(c: AppController, startTab: Tab) {
    val restriction by c.restriction.state.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(startTab) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (restriction != Restriction.PARKED_OK) {
            LockedScreen(restriction)
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            NavRail(tab, pendingCount = events.count { it.whileRestricted && !it.acknowledged }) { tab = it }
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                val pending = events.filter { it.whileRestricted && !it.acknowledged }
                if (pending.isNotEmpty() && tab != Tab.WARNINGS) {
                    Banner("주행 중 켜진 경고등 ${pending.size}개가 있어요. 눌러서 확인하세요.") { tab = Tab.WARNINGS }
                    Spacer(Modifier.height(16.dp))
                }
                when (tab) {
                    Tab.CHECKIN -> CheckinTab(c)
                    Tab.PRACTICE -> PracticeTab(c)
                    Tab.WARNINGS -> WarningsTab(c)
                    Tab.RETURN -> ReturnTab(c)
                }
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
        Text("새로 켜지는 경고등은 기록해 두었다가 정차 후 알려 드릴게요.", color = Muted, fontSize = 22.sp)
    }
}

@Composable
private fun NavRail(current: Tab, pendingCount: Int, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(232.dp).background(Color(0xFF121A22)).padding(vertical = 24.dp, horizontal = 12.dp),
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
                Text(t.label, color = if (selected) TextMain else Muted, fontSize = 20.sp, maxLines = 1, softWrap = false)
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
        Text(text, color = TextMain, fontSize = 20.sp)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).padding(20.dp)) { content() }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    if (sub != null) Text(sub, color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
}

private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(ms))

// ------------------------------------------------------------------ 체크인

@Composable
private fun CheckinTab(c: AppController) {
    val r by c.report.collectAsStateWithLifecycle()
    val saved by c.checkin.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title("인수 체크인 리포트", if (r.loading) "차량에서 읽는 중…" else "차량에서 읽은 시각 ${time(r.readAtMillis)}")
            }
            OutlinedButton(onClick = { c.refreshReport() }, modifier = Modifier.heightIn(min = 64.dp)) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("다시 읽기", fontSize = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusItem(Modifier.weight(1f), "경고등", warningsSummary(r.warnings))
            StatusItem(Modifier.weight(1f), r.energyLabel ?: "배터리·연료", r.energyPercent.fmt { "$it%" })
            StatusItem(Modifier.weight(1f), "주행가능거리", r.rangeKm.fmt { "${it.toInt()} km" })
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusItem(Modifier.weight(1f), "누적 주행거리", r.odometerKm.fmt { "%,d km".format(it.toInt()) })
            StatusItem(Modifier.weight(1f), "도어·트렁크", openSummary(r.openDoors))
            StatusItem(Modifier.weight(1f), "창문", openSummary(r.openWindows))
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { c.saveCheckin() }, enabled = !r.loading, modifier = Modifier.heightIn(min = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(if (saved == null) "인수 기록 저장" else "인수 기록 다시 저장", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                saved?.let { "저장됨 · ${time(it.savedAtMillis)}" } ?: "저장하면 반납 때 비교할 수 있어요",
                color = Muted, fontSize = 18.sp,
            )
        }
    }
}

private data class Shown(val text: String, val tone: Color, val note: String? = null)

private fun <T> Read<T>.fmt(f: (T) -> String): Shown = when (this) {
    is Read.Ok -> Shown(f(value), TextMain)
    Read.Unsupported -> Shown("지원 안 함", Muted)
    is Read.Unavailable -> Shown("확인 불가", Muted, reason)
}

private fun warningsSummary(r: Read<List<ActiveWarning>>): Shown = when (r) {
    is Read.Ok -> when {
        r.value.isEmpty() -> Shown("켜진 경고등 없음", Ok)
        r.value.any { it.explain.severity == Severity.STOP } -> Shown("확인 필요 ${r.value.size}개", Stop, r.value.joinToString { it.explain.name })
        else -> Shown("확인 필요 ${r.value.size}개", Warn, r.value.joinToString { it.explain.name })
    }
    Read.Unsupported -> Shown("지원 안 함", Muted)
    is Read.Unavailable -> Shown("확인 불가", Muted, r.reason)
}

private fun openSummary(r: Read<List<String>>): Shown = when (r) {
    is Read.Ok -> if (r.value.isEmpty()) Shown("모두 닫힘", Ok) else Shown("열림 ${r.value.size}곳", Warn, r.value.joinToString())
    Read.Unsupported -> Shown("지원 안 함", Muted)
    is Read.Unavailable -> Shown("확인 불가", Muted, r.reason)
}

@Composable
private fun StatusItem(modifier: Modifier, label: String, s: Shown) {
    Card(modifier.height(150.dp)) {
        Column {
            Text(label, color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(s.text, color = s.tone, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (s.note != null) Text(s.note, color = Muted, fontSize = 16.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
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
    Column {
        Title("출발 전 3가지만 손으로 익혀요", "실제 레버·버튼을 조작하면 차량이 바뀐 상태를 알려 주고, 그때 완료됩니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            tasks.forEach { TaskCard(Modifier.weight(1f), it) }
        }
        Spacer(Modifier.height(18.dp))
        Text("차량이 확인해 주지 않은 과제는 완료로 표시하지 않아요.", color = Muted, fontSize = 17.sp)
    }
}

@Composable
private fun TaskCard(modifier: Modifier, t: PracticeTask) {
    val (badge, tone) = when (t.status) {
        TaskStatus.CHECKING -> "확인 중" to Muted
        TaskStatus.WAITING -> "해 보세요" to Accent
        TaskStatus.ALREADY_SET -> "이미 설정됨" to Ok
        TaskStatus.DONE -> "완료" to Ok
        TaskStatus.UNSUPPORTED -> "지원 안 함" to Muted
        TaskStatus.UNAVAILABLE -> "확인 불가" to Muted
    }
    Card(modifier.height(300.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.status == TaskStatus.DONE || t.status == TaskStatus.ALREADY_SET) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Ok, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(badge, color = tone, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(t.title, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(t.howTo, color = TextMain.copy(alpha = 0.85f), fontSize = 19.sp)
            Spacer(Modifier.height(12.dp))
            Text(t.detail, color = Muted, fontSize = 16.sp)
        }
    }
}

// ------------------------------------------------------------------ 경고등

@Composable
private fun WarningsTab(c: AppController) {
    val r by c.report.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("경고등 통역", "지금 켜진 경고등을 쉬운 말로 풀어 드려요.")
        val pending = events.filter { it.whileRestricted && !it.acknowledged }
        if (pending.isNotEmpty()) {
            Text("주행 중 켜진 경고등", color = Warn, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            pending.forEach { EventCard(it); Spacer(Modifier.height(12.dp)) }
            OutlinedButton(onClick = { c.acknowledgeEvents() }, modifier = Modifier.heightIn(min = 64.dp)) { Text("확인했어요", fontSize = 18.sp) }
            Spacer(Modifier.height(22.dp))
        }
        Text("지금 켜져 있는 경고등", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        when (val w = r.warnings) {
            is Read.Ok -> if (w.value.isEmpty()) {
                Text("켜진 경고등이 없어요.", color = Ok, fontSize = 20.sp)
            } else w.value.forEach { WarningCard(it); Spacer(Modifier.height(12.dp)) }
            Read.Unsupported -> Text("이 차량에서는 경고등 정보를 제공하지 않아요.", color = Muted, fontSize = 20.sp)
            is Read.Unavailable -> Text("경고등 정보를 읽지 못했어요 (${w.reason}). 계기판을 직접 확인하세요.", color = Muted, fontSize = 20.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(TelltaleGuide.DISCLAIMER, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun WarningCard(w: ActiveWarning) = ExplainCard(
    w.explain.name + if (w.blinking) " (깜박임)" else "", w.explain.meaning, w.explain.action, w.explain.severity, null,
)

@Composable
private fun EventCard(e: WarningEvent) {
    val type = runCatching { ai.pleos.playground.vehicle.constant.telltale.TelltaleType.valueOf(e.typeName) }.getOrNull()
    val ex = TelltaleGuide.explain(type, e.colorId)
    ExplainCard(ex.name, ex.meaning, ex.action, ex.severity, "켜진 시각 ${time(e.atMillis)}")
}

@Composable
private fun ExplainCard(name: String, meaning: String, action: String, sev: Severity, meta: String?) {
    val tone = when (sev) { Severity.STOP -> Stop; Severity.CHECK -> Warn; Severity.INFO -> Accent }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)) {
        Box(Modifier.width(8.dp).height(170.dp).background(tone))
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (meta != null) { Spacer(Modifier.width(12.dp)); Text(meta, color = Muted, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Text("무슨 뜻  $meaning", color = TextMain.copy(alpha = 0.9f), fontSize = 19.sp)
            Spacer(Modifier.height(6.dp))
            Text("지금 할 일  $action", color = tone, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ------------------------------------------------------------------ 반납

@Composable
private fun ReturnTab(c: AppController) {
    val r by c.report.collectAsStateWithLifecycle()
    val saved by c.checkin.collectAsStateWithLifecycle()
    val events by c.events.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("반납 체크", "인수 때 저장한 값과 지금 차량 값을 비교해요.")
        val s = saved
        if (s == null) {
            Text("저장된 인수 기록이 없어요. 체크인 화면에서 먼저 저장해 주세요.", color = Muted, fontSize = 20.sp)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CompareItem(Modifier.weight(1f), "주행거리", s.odometerKm?.let { "%,d km".format(it.toInt()) }, r.odometerKm.valueOrNullF()?.let { "%,d km".format(it.toInt()) },
                diff(s.odometerKm, r.odometerKm.valueOrNullF())?.let { "+%,d km 주행".format(it.toInt()) })
            CompareItem(Modifier.weight(1f), s.energyLabel ?: "배터리·연료", s.energyPercent?.let { "$it%" }, (r.energyPercent as? Read.Ok)?.value?.let { "$it%" },
                diffI(s.energyPercent, (r.energyPercent as? Read.Ok)?.value)?.let { "${if (it >= 0) "+" else ""}$it%p" })
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("대여 중 경고등 기록", color = Muted, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                if (events.isEmpty()) Text("기록된 경고등이 없어요.", color = Ok, fontSize = 20.sp)
                events.forEach { e ->
                    val type = runCatching { ai.pleos.playground.vehicle.constant.telltale.TelltaleType.valueOf(e.typeName) }.getOrNull()
                    Text("· ${time(e.atMillis)}  ${TelltaleGuide.explain(type, e.colorId).name}${if (e.whileRestricted) " (주행 중)" else ""}", color = TextMain, fontSize = 19.sp)
                }
                if (!s.warningsReadable) Text("인수 시 경고등 정보는 확인하지 못했어요.", color = Muted, fontSize = 16.sp)
                else if (s.warnings.isNotEmpty()) Text("인수 시 이미 켜져 있던 경고등: ${s.warnings.joinToString()}", color = Muted, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        val recordText = remember(s, r.readAtMillis, events) {
            HandoverRecord.text(
                checkin = s,
                nowOdometerKm = r.odometerKm.valueOrNullF(),
                nowEnergyPercent = (r.energyPercent as? Read.Ok)?.value,
                events = events,
                nowMillis = if (r.readAtMillis > 0) r.readAtMillis else System.currentTimeMillis(),
                eventName = { e ->
                    val type = runCatching { ai.pleos.playground.vehicle.constant.telltale.TelltaleType.valueOf(e.typeName) }.getOrNull()
                    TelltaleGuide.explain(type, e.colorId).name
                },
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrView(recordText, Modifier.size(280.dp))
                Spacer(Modifier.width(24.dp))
                Column {
                    Text("기록을 내 휴대폰으로", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("휴대폰 카메라로 QR을 찍으면 인수·반납 기록이 글자로 열려요. 메모나 메시지로 보관하세요.", color = TextMain.copy(alpha = 0.9f), fontSize = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("서버로 보내지 않고, 차대번호·위치는 담지 않아요. 외관 상태는 직접 사진으로 남겨 주세요.", color = Muted, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("인수 기록 ${time(s.savedAtMillis)} · 비교 기준 ${time(r.readAtMillis)}", color = Muted, fontSize = 17.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { c.finishRental() }, modifier = Modifier.heightIn(min = 64.dp)) { Text("반납 완료 · 기록 지우기", fontSize = 18.sp) }
        }
    }
}

private fun Read<Float>.valueOrNullF(): Float? = (this as? Read.Ok)?.value
private fun diff(a: Float?, b: Float?) = if (a != null && b != null) b - a else null
private fun diffI(a: Int?, b: Int?) = if (a != null && b != null) b - a else null

@Composable
private fun CompareItem(modifier: Modifier, label: String, before: String?, now: String?, delta: String?) {
    Card(modifier.height(170.dp)) {
        Column {
            Text(label, color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text("인수 ${before ?: "확인 불가"}  →  지금 ${now ?: "확인 불가"}", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(delta ?: "비교할 수 없어요", color = if (delta != null) Accent else Muted, fontSize = 20.sp)
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
