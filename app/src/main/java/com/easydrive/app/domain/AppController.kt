package com.easydrive.app.domain

import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.data.WarningEvent
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.LightGoal
import com.easydrive.app.vehicle.NaviDestination
import com.easydrive.app.vehicle.NaviSource
import com.easydrive.app.vehicle.Read
import com.easydrive.app.vehicle.map
import com.easydrive.app.vehicle.Restriction
import com.easydrive.app.vehicle.VehicleGateway
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.easydrive.app.vehicle.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 경고등 한 개(현재 켜져 있는 것) */
data class ActiveWarning(val typeName: String, val colorId: Int, val blinking: Boolean, val explain: TelltaleExplain)

/**
 * 경고를 어디까지 읽었는지.
 * BASIC: 공개 권한으로 읽는 경고 상태(안전벨트·워셔액 등)만. FULL: 계기판 경고등 전체 목록(비공개 권한이 주어진 환경).
 */
enum class WarningScope { BASIC, FULL }

/** 체크인 리포트 화면에 보여 줄 현재 상태 */
data class CheckinReport(
    val loading: Boolean = true,
    val warnings: Read<List<ActiveWarning>> = Read.Unavailable("조회 전"),
    val warningScope: WarningScope = WarningScope.BASIC,
    /** BASIC일 때 이 차량에서 실제로 읽을 수 있었던 경고 항목 이름 */
    val warningItems: List<String> = emptyList(),
    val energyLabel: String? = null,
    val energyPercent: Read<Int> = Read.Unavailable("조회 전"),
    val rangeKm: Read<Float> = Read.Unavailable("조회 전"),
    val odometerKm: Read<Float> = Read.Unavailable("조회 전"),
    val openDoors: Read<List<String>> = Read.Unavailable("조회 전"),
    val openWindows: Read<List<String>> = Read.Unavailable("조회 전"),
    /** 내비게이션에서 길 안내 중인 목적지(없으면 Unsupported) */
    val destination: Read<NaviDestination> = Read.Unsupported,
    val readAtMillis: Long = 0L,
)

enum class TaskStatus { CHECKING, WAITING, ALREADY_SET, DONE, UNSUPPORTED, UNAVAILABLE }

data class PracticeTask(
    val id: String,
    val title: String,
    val howTo: String,
    /** 이미 설정돼 있을 때 보여 줄 문구(조작을 시키지 않고 위치만 확인하게 한다) */
    val alreadyText: String = "",
    val status: TaskStatus = TaskStatus.CHECKING,
    val detail: String = "",
    /** 기본 3과제가 아닌 '더 익혀 둘 조작'. 차가 지원할 때만 화면에 나온다. */
    val extra: Boolean = false,
    /** detail을 눈에 띄게 보여 줄지(아직 열려·켜져 있음, 레버는 움직였지만 목표 위치가 아님). */
    val attention: Boolean = false,
)

class AppController(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    val restriction: DrivingRestriction,
    private val store: CheckinStore,
    private val navi: NaviSource? = null,
) {
    private val _report = MutableStateFlow(CheckinReport())
    val report: StateFlow<CheckinReport> = _report.asStateFlow()

    private val _checkin = MutableStateFlow(store.loadCheckin())
    val checkin: StateFlow<CheckinSnapshot?> = _checkin.asStateFlow()

    private val _events = MutableStateFlow(store.loadEvents())
    val events: StateFlow<List<WarningEvent>> = _events.asStateFlow()

    private val _tasks = MutableStateFlow(initialTasks())
    val tasks: StateFlow<List<PracticeTask>> = _tasks.asStateFlow()

    /** 차량이 확인해 준 조작의 이름. 기기에 저장해 반납 기록(QR)에 함께 남긴다. */
    private val _practiced = MutableStateFlow(store.loadPracticed())
    val practiced: StateFlow<List<String>> = _practiced.asStateFlow()

    /** 기어가 P가 아니어서 실습 과제를 잠시 닫았는지(기어를 읽을 수 있는 차에서만). */
    private val _practiceBlocked = MutableStateFlow(false)
    val practiceBlocked: StateFlow<Boolean> = _practiceBlocked.asStateFlow()

    private var monitorJob: Job? = null
    private var practiceJob: Job? = null

    fun start() {
        gateway.initialize()
        restriction.start()
        scope.launch {
            delay(800) // Vehicle 서비스 바인딩 대기
            // SDK에는 연결 완료 알림이 없어, 첫 조회가 '확인 불가'면 잠시 후 다시 읽는다(최대 4회).
            repeat(4) {
                loadReport(showLoading = true)
                val r = _report.value
                if (r.warnings !is Read.Unavailable && r.odometerKm !is Read.Unavailable) return@launch
                delay(1500)
            }
        }
        startTelltaleMonitor()
    }

    fun stop() {
        monitorJob?.cancel(); practiceJob?.cancel()
        gateway.release()
        restriction.stop()
    }

    // ------------------------------------------------------------ 체크인 리포트

    fun refreshReport() {
        scope.launch { loadReport(showLoading = true) }
    }

    /** 화면을 열 때처럼 '읽는 중' 표시 없이 조용히 다시 읽는다. */
    fun refreshQuietly() {
        scope.launch { loadReport(showLoading = false) }
    }

    private suspend fun loadReport(showLoading: Boolean, known: WarningRead? = null) {
        if (showLoading) _report.update { it.copy(loading = true) }
        val w = known ?: readWarnings()
        val ev = gateway.evStateOfChargePercent()
        // 전기차가 아니라고 확인됐을 때만 연료로 넘어간다(일시적인 조회 실패로 '연료'로 바뀌지 않게).
        val (label, energy) = when (ev) {
            is Read.Ok -> "배터리" to ev
            Read.Unsupported -> "연료" to gateway.fuelPercent()
            is Read.Unavailable -> {
                val fuel = gateway.fuelPercent()
                if (fuel is Read.Ok) "연료" to fuel else null to ev
            }
        }
        val range = when (val r = gateway.rangeRemainingMeters()) {
            is Read.Ok -> Read.Ok(r.value / 1000f, r.readAtMillis)
            is Read.Unavailable -> r
            Read.Unsupported -> Read.Unsupported
        }
        _report.value = CheckinReport(
            loading = false,
            warnings = w.list,
            warningScope = w.scope,
            warningItems = w.items,
            energyLabel = label,
            energyPercent = energy,
            rangeKm = range,
            destination = _report.value.destination,
            odometerKm = gateway.odometerKm(),
            openDoors = gateway.openDoors(),
            openWindows = gateway.openWindows(),
            readAtMillis = System.currentTimeMillis(),
        )
        // 내비게이션 응답이 늦어도 차량 상태 리포트를 붙잡아 두지 않도록 목적지는 따로 읽어 채운다.
        val source = navi ?: return
        if (naviJob?.isActive != true) naviJob = scope.launch {
            val d = source.destination()
            _report.update { it.copy(destination = d) }
        }
    }

    private var naviJob: Job? = null

    fun saveCheckin() {
        val r = _report.value
        val snap = CheckinSnapshot(
            savedAtMillis = System.currentTimeMillis(),
            odometerKm = r.odometerKm.valueOrNull(),
            energyLabel = r.energyLabel,
            energyPercent = r.energyPercent.valueOrNull(),
            rangeKm = r.rangeKm.valueOrNull(),
            warnings = r.warnings.valueOrNull()?.filter { it.explain.severity != Severity.INFO }?.map { it.explain.name } ?: emptyList(),
            warningsReadable = r.warnings is Read.Ok,
        )
        // 첫 저장은 새 대여의 시작이다. 그 전에 쌓인 경고 기록은 이번 대여의 것이 아니므로 비운다.
        if (_checkin.value == null && _events.value.isNotEmpty()) {
            _events.value = emptyList()
            store.saveEvents(emptyList())
        }
        store.saveCheckin(snap)
        _checkin.value = snap
    }

    fun finishRental() {
        store.clearRental()
        _checkin.value = null
        _events.value = emptyList()
        _tasks.value = initialTasks()
        _practiced.value = emptyList()
    }

    // ------------------------------------------------------------ 경고등 감시

    private class WarningRead(val list: Read<List<ActiveWarning>>, val scope: WarningScope, val items: List<String>)

    /**
     * 차량 경고를 읽는다.
     * 1) 계기판 경고등 전체 목록(getTelltalesInfo)을 먼저 시도한다. Pleos Connect Emulator v2.1.0에서는 권한이 없다고 거절된다.
     * 2) 거절되거나 지원하지 않으면 공개 권한으로 읽는 경고 상태 7종으로 넘어간다(게이트웨이가 기억해 다시 부르지 않는다).
     * 일시적인 조회 실패일 때는 범위를 바꾸지 않고 '확인 불가'로 둔다(범위가 오가며 가짜 이력이 생기지 않게).
     */
    private suspend fun readWarnings(): WarningRead {
        val t = gateway.telltales()
        if (t is Read.Ok) {
            val list = t.value.telltales
                .filter { it.state.name != "OFF" && TelltaleGuide.isWarning(it.type, it.color) }
                .sortedBy { it.priority }
                .map {
                    ActiveWarning(
                        typeName = it.type?.name ?: "UNKNOWN",
                        colorId = it.color,
                        blinking = it.state.name == "BLINK",
                        explain = TelltaleGuide.explain(it.type, it.color),
                    )
                }
            return WarningRead(Read.Ok(list, t.readAtMillis), WarningScope.FULL, emptyList())
        }
        if (t is Read.Unavailable && t.reason != VehicleGateway.NO_PERMISSION) return WarningRead(t, WarningScope.FULL, emptyList())
        return when (val b = gateway.basicWarnings()) {
            is Read.Ok -> WarningRead(
                Read.Ok(
                    b.value.active.map { w ->
                        val ex = TelltaleGuide.basic(w)
                        ActiveWarning(TelltaleGuide.BASIC_PREFIX + w.name, if (ex.severity == Severity.STOP) COLOR_RED else COLOR_YELLOW, false, ex)
                    },
                    b.readAtMillis,
                ),
                WarningScope.BASIC,
                b.value.checked.map { TelltaleGuide.basicShortName(it) },
            )
            Read.Unsupported -> WarningRead(Read.Unsupported, WarningScope.BASIC, emptyList())
            is Read.Unavailable -> WarningRead(b, WarningScope.BASIC, emptyList())
        }
    }

    /**
     * 앱 프로세스가 살아 있는 동안 2초마다 경고를 조회한다.
     * 새로 켜진 경고는 기록만 하고, 주행 규제 중에는 화면에 띄우지 않는다(정차 후 카드로 안내).
     * 공개 경고 상태는 항목별 변경 알림(register…)이 따로 있지만, 지금은 7종을 한 번에 다루기 쉬운 주기 조회를 쓴다(구독 전환은 다음 단계). 6초마다 나머지 값(주행거리·잔량·문)도 조용히 다시 읽는다.
     */
    private fun startTelltaleMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            delay(1500)
            var previous: Set<String>? = null
            var failures = 0
            var tick = 0
            while (isActive) {
                try {
                    val w = readWarnings()
                    val now = w.list
                    if (now is Read.Ok) {
                        failures = 0
                        val names = now.value.map { it.typeName }.toSet()
                        val prev = previous
                        if (prev != null) {
                            val newOnes = now.value.filter { it.typeName !in prev && it.explain.severity != Severity.INFO }
                            if (newOnes.isNotEmpty()) {
                                val restricted = restriction.state.value != Restriction.PARKED_OK
                                val added = newOnes.map {
                                    WarningEvent(it.typeName, it.colorId, System.currentTimeMillis(), restricted)
                                }
                                Log.i("EasyDrive", "warning on: ${added.joinToString { it.typeName }} whileRestricted=$restricted")
                                _events.update { (it + added).takeLast(50) }
                                store.saveEvents(_events.value)
                            }
                        }
                        previous = names
                        _report.update { r -> if (r.loading) r else r.copy(warnings = now, warningScope = w.scope, warningItems = w.items) }
                    } else {
                        // 한 번의 실패로는 화면을 바꾸지 않지만, 계속 읽지 못하면 초록색 '경고 없음'을 남겨 두지 않는다.
                        failures++
                        if (failures >= 2) _report.update { r -> if (r.loading) r else r.copy(warnings = now) }
                    }
                    tick++
                    if (tick % 3 == 0 && !_report.value.loading) loadReport(showLoading = false, known = w)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("EasyDrive", "warning monitor iteration failed", t)
                }
                delay(2000)
            }
        }
    }

    fun acknowledgeEvents() {
        _events.update { list -> list.map { it.copy(acknowledged = true) } }
        store.saveEvents(_events.value)
    }

    // ------------------------------------------------------------ 실습 과제

    private fun initialTasks() = listOf(
        PracticeTask("light", "전조등 AUTO", "왼쪽 라이트 레버(또는 조명 버튼)를 AUTO 위치로 돌려 보세요.", "지금 AUTO 위치예요. 레버가 어디 있는지만 눈으로 확인해 두세요."),
        PracticeTask("wiper", "앞 와이퍼 AUTO", "오른쪽 와이퍼 레버를 AUTO 위치로 바꿔 보세요.", "지금 AUTO 위치예요. 레버가 어디 있는지만 눈으로 확인해 두세요."),
        PracticeTask("defrost", "앞유리 성에 제거", "공조 조작부의 앞유리 성에 제거 버튼을 눌러 보세요.", "지금 켜져 있어요. 버튼이 어디 있는지만 눈으로 확인해 두세요."),
        PracticeTask("doorlock", "도어 잠금", "운전석 문 안쪽의 잠금 버튼을 눌러 문을 잠가 보세요.", "지금 잠겨 있어요. 버튼이 어디 있는지만 눈으로 확인해 두세요.", extra = true),
        PracticeTask("charge", "충전구 열기", "충전구 덮개를 눌러 열어 보세요.", "지금 열려 있어요. 출발 전에 닫아 주세요.", extra = true),
        PracticeTask("highbeam", "상향등 켜기", "앞에 차·사람이 없을 때, 라이트 레버를 앞으로 밀어 보세요.", "지금 켜져 있어요. 마주 오는 차가 있으면 꺼 주세요.", extra = true),
        PracticeTask("reardefrost", "뒷유리 열선", "공조 조작부의 뒷유리 열선 버튼을 눌러 보세요.", "지금 켜져 있어요. 버튼이 어디 있는지만 눈으로 확인해 두세요.", extra = true),
    )

    /** 실습 화면에 들어오면 호출. 지원 여부를 먼저 확인하고, 시작값을 기준으로 변화를 지켜본다. */
    fun startPractice() {
        practiceJob?.cancel()
        // 주행 잠금에서 돌아왔을 때 이전의 'P단 아님' 표시가 남아 있지 않게 한다(기어는 아래에서 다시 읽는다).
        _practiceBlocked.value = false
        // 이미 차량이 확인해 준 과제는 화면을 오가도 '완료'로 남긴다(다시 열었을 때 '이미 설정됨'으로 바뀌지 않게).
        // 제목·안내도 완료했을 때의 것을 그대로 둔다('전조등 켜기'로 완료했는데 '전조등 AUTO'로 돌아가지 않게).
        val done = _tasks.value.filter { it.status == TaskStatus.DONE }.associateBy { it.id }
        _tasks.value = initialTasks().map { done[it.id] ?: it }
        practiceJob = scope.launch {
            // 앱 시작 직후에는 차량 서비스 바인딩이 끝나지 않았을 수 있어 '확인 불가'면 잠시 후 다시 확인한다.
            val lightGoal = retryUnavailable { gateway.lightGoal() }
            // AUTO 위치가 없는 차는 '전조등 켜기'로 과제를 바꾼다(차가 확인해 줄 수 있는 조작만 과제로 낸다).
            val goal = (lightGoal as? Read.Ok)?.value
            if (goal != null && goal != LightGoal.AUTO) _tasks.update { list ->
                list.map {
                    if (it.id == "light" && it.status != TaskStatus.DONE) it.copy(
                        title = "전조등 켜기",
                        howTo = "왼쪽 라이트 레버(또는 조명 버튼)로 전조등을 켜 보세요.",
                        alreadyText = "지금 켜져 있어요. 스위치가 어디 있는지만 눈으로 확인해 두세요.",
                    ) else it
                }
            }
            val lightOk = lightGoal.map { true }
            val wiperOk = retryUnavailable { gateway.frontWiperSupportsAuto() }
            val defrostOk = retryUnavailable { gateway.frontDefrosterSupported() }

            setInitial("light", lightOk) { gateway.lightGoalMet(goal ?: LightGoal.AUTO) }
            setInitial("wiper", wiperOk) { gateway.frontWiperSwitch().map { it == WiperSwitch.AUTO } }
            setInitial("defrost", defrostOk) { gateway.frontDefroster() }

            for (e in extras) setInitial(e.id, e.supported()) { e.met() }
            // 와이퍼 레버가 움직였지만 AUTO가 아닐 때 알려 주기 위해 시작 위치를 기억한다.
            val wiperStart = gateway.frontWiperSwitch().valueOrNull()

            // 성에 제거: 변경 알림 구독
            if (task("defrost").status == TaskStatus.WAITING) {
                gateway.watchFrontDefroster(
                    onChange = { on -> if (on) markDone("defrost") },
                    onError = { /* 알림이 안 되면 아래 주기 조회로 확인 */ },
                )
            }
            // 전조등·와이퍼: 스위치 변경 알림 API가 없어 1초마다 조회
            while (isActive) {
                // 기어를 읽을 수 있는 차에서는 P단일 때만 과제를 연다(시스템 주행 규제보다 엄격한 쪽으로만 쓴다).
                val parked = gateway.gearParked()
                val blocked = parked is Read.Ok && !parked.value
                if (_practiceBlocked.value != blocked) {
                    _practiceBlocked.value = blocked
                    Log.i("EasyDrive", "practice blocked by gear -> $blocked")
                }
                if (!blocked) {
                    if (task("light").status == TaskStatus.WAITING &&
                        goal != null && gateway.lightGoalMet(goal).valueOrNull() == true) markDone("light")
                    if (task("wiper").status == TaskStatus.WAITING) {
                        val w = gateway.frontWiperSwitch().valueOrNull()
                        when {
                            w == WiperSwitch.AUTO -> markDone("wiper")
                            w != null && wiperStart != null && w != wiperStart ->
                                note("wiper", "와이퍼 레버가 움직였어요. 아직 AUTO 위치는 아니에요.", attention = true)
                            w != null -> note("wiper", WAITING_DETAIL, attention = false)
                        }
                    }
                    if (task("defrost").status == TaskStatus.WAITING &&
                        gateway.frontDefroster().valueOrNull() == true) markDone("defrost")
                    for (e in extras) if (task(e.id).status == TaskStatus.WAITING && e.met().valueOrNull() == true) markDone(e.id)
                    // 켜거나 연 채로 두면 안 되는 조작은 원래대로 돌려놓았는지까지 지켜본다.
                    for ((id, read) in restorable) if (task(id).status == TaskStatus.DONE) {
                        val on = read().valueOrNull() ?: continue
                        if (on) note(id, doneDetail(id), attention = id in LEAVE_OFF) else note(id, restoredDetail(id), attention = false)
                    }
                }
                delay(1000)
            }
        }
    }

    fun stopPractice() {
        practiceJob?.cancel()
        gateway.unwatchFrontDefroster()
    }

    private suspend fun setInitial(id: String, supported: Read<Boolean>, readSatisfied: suspend () -> Read<Boolean>) {
        if (task(id).status == TaskStatus.DONE) return
        val (status, detail) = when (supported) {
            is Read.Unavailable -> TaskStatus.UNAVAILABLE to "지원 여부를 확인하지 못했어요 (${supported.reason})"
            Read.Unsupported -> TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
            is Read.Ok -> if (!supported.value) {
                TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
            } else when (val now = readSatisfied()) {
                is Read.Ok -> if (now.value) TaskStatus.ALREADY_SET to "이미 설정되어 있어요" else TaskStatus.WAITING to WAITING_DETAIL
                Read.Unsupported -> TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
                is Read.Unavailable -> TaskStatus.UNAVAILABLE to "현재 상태를 읽지 못했어요 (${now.reason})"
            }
        }
        Log.i("EasyDrive", "practice $id -> $status")
        _tasks.update { list -> list.map { if (it.id == id) it.copy(status = status, detail = detail) else it } }
    }

    private suspend fun <T> retryUnavailable(times: Int = 3, read: suspend () -> Read<T>): Read<T> {
        var r = read()
        var n = 1
        while (r is Read.Unavailable && n < times) {
            delay(1000)
            r = read(); n++
        }
        return r
    }

    private fun task(id: String) = _tasks.value.first { it.id == id }

    /** 켜거나 연 채로 두지 않도록 되돌리는 방법까지 알려 준다. */
    private fun doneDetail(id: String) = when (id) {
        "defrost", "reardefrost" -> "차량에서 변경을 확인했어요. 지금 필요 없으면 한 번 더 눌러 끄세요."
        "charge" -> "아직 열려 있어요. 출발 전에 닫아 주세요."
        "highbeam" -> "아직 켜져 있어요. 레버를 다시 당겨 꺼 주세요."
        "doorlock" -> "차량에서 잠김을 확인했어요"
        "light" -> if (task("light").title == "전조등 켜기") "차량에서 켜짐을 확인했어요. 낮에는 다시 꺼 두세요." else "차량에서 변경을 확인했어요"
        else -> "차량에서 변경을 확인했어요"
    }

    private fun restoredDetail(id: String) = when (id) {
        "charge" -> "열고 닫는 것까지 차량에서 확인했어요"
        else -> "켜고 끄는 것까지 차량에서 확인했어요"
    }

    /** 완료 뒤에도 현재 상태를 계속 읽는 과제(되돌렸는지 확인). */
    private val restorable: List<Pair<String, suspend () -> Read<Boolean>>> = listOf(
        "defrost" to { gateway.frontDefroster() },
        "reardefrost" to { gateway.rearDefroster() },
        "charge" to { gateway.chargePortOpen() },
        "highbeam" to { gateway.highBeamOn() },
    )

    private fun note(id: String, detail: String, attention: Boolean) {
        val t = task(id)
        if (t.detail == detail && t.attention == attention) return
        _tasks.update { list -> list.map { if (it.id == id) it.copy(detail = detail, attention = attention) else it } }
    }

    /** 더 익혀 둘 조작: 지원 여부 확인과 '했는지' 확인을 짝지어 둔다. */
    private class Extra(val id: String, val supported: suspend () -> Read<Boolean>, val met: suspend () -> Read<Boolean>)
    private val extras = listOf(
        Extra("doorlock", { gateway.doorLockSupported() }, { gateway.doorLocked() }),
        Extra("charge", { gateway.chargePortSupported() }, { gateway.chargePortOpen() }),
        Extra("highbeam", { gateway.highBeamSupported() }, { gateway.highBeamOn() }),
        Extra("reardefrost", { gateway.rearDefrosterSupported() }, { gateway.rearDefroster() }),
    )

    private fun markDone(id: String) {
        if (task(id).status == TaskStatus.WAITING) Log.i("EasyDrive", "practice $id -> DONE (차량에서 변경 확인)")
        _tasks.update { list ->
            list.map { if (it.id == id && it.status == TaskStatus.WAITING) it.copy(status = TaskStatus.DONE, detail = doneDetail(it.id), attention = id in LEAVE_OFF) else it }
        }
        // 차량이 확인해 준 조작은 기록에도 남긴다.
        val title = task(id).title
        if (task(id).status == TaskStatus.DONE && title !in _practiced.value) {
            _practiced.value = _practiced.value + title
            store.savePracticed(_practiced.value)
        }
    }

    private companion object {
        // TelltaleColor id (WHITE 0, GREEN 1, BLUE 2, YELLOW 3, RED 4, GRAY 5)
        const val COLOR_YELLOW = 3
        const val COLOR_RED = 4
        const val WAITING_DETAIL = "조작하면 차량 상태를 다시 읽어 자동으로 완료돼요"
        /** 켜거나 연 채로 출발하면 안 되는 조작 */
        val LEAVE_OFF = setOf("charge", "highbeam")
    }

}
