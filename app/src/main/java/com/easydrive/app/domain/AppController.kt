package com.easydrive.app.domain

import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.data.WarningEvent
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.LightGoal
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
)

class AppController(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    val restriction: DrivingRestriction,
    private val store: CheckinStore,
) {
    private val _report = MutableStateFlow(CheckinReport())
    val report: StateFlow<CheckinReport> = _report.asStateFlow()

    private val _checkin = MutableStateFlow(store.loadCheckin())
    val checkin: StateFlow<CheckinSnapshot?> = _checkin.asStateFlow()

    private val _events = MutableStateFlow(store.loadEvents())
    val events: StateFlow<List<WarningEvent>> = _events.asStateFlow()

    private val _tasks = MutableStateFlow(initialTasks())
    val tasks: StateFlow<List<PracticeTask>> = _tasks.asStateFlow()

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
            odometerKm = gateway.odometerKm(),
            openDoors = gateway.openDoors(),
            openWindows = gateway.openWindows(),
            readAtMillis = System.currentTimeMillis(),
        )
    }

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
    }

    // ------------------------------------------------------------ 경고등 감시

    private class WarningRead(val list: Read<List<ActiveWarning>>, val scope: WarningScope, val items: List<String>)

    /**
     * 차량 경고를 읽는다.
     * 1) 계기판 경고등 전체 목록(Telltales)은 비공개 권한이 필요하다. 권한이 주어진 환경에서만 쓰인다.
     * 2) 권한이 없거나 지원하지 않으면 공개 권한으로 읽는 경고 상태 7종으로 넘어간다.
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
     * 공개 경고 상태에는 '전체' 변경 알림이 없어 주기 조회를 쓴다. 6초마다 나머지 값(주행거리·잔량·문)도 조용히 다시 읽는다.
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
    )

    /** 실습 화면에 들어오면 호출. 지원 여부를 먼저 확인하고, 시작값을 기준으로 변화를 지켜본다. */
    fun startPractice() {
        practiceJob?.cancel()
        // 이미 차량이 확인해 준 과제는 화면을 오가도 '완료'로 남긴다(다시 열었을 때 '이미 설정됨'으로 바뀌지 않게).
        val doneIds = _tasks.value.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
        _tasks.value = initialTasks().map { if (it.id in doneIds) it.copy(status = TaskStatus.DONE, detail = doneDetail(it.id)) else it }
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

            // 성에 제거: 변경 알림 구독
            if (task("defrost").status == TaskStatus.WAITING) {
                gateway.watchFrontDefroster(
                    onChange = { on -> if (on) markDone("defrost") },
                    onError = { /* 알림이 안 되면 아래 주기 조회로 확인 */ },
                )
            }
            // 전조등·와이퍼: 스위치 변경 알림 API가 없어 1초마다 조회
            while (isActive) {
                if (task("light").status == TaskStatus.WAITING &&
                    goal != null && gateway.lightGoalMet(goal).valueOrNull() == true) markDone("light")
                if (task("wiper").status == TaskStatus.WAITING &&
                    gateway.frontWiperSwitch().valueOrNull() == WiperSwitch.AUTO) markDone("wiper")
                if (task("defrost").status == TaskStatus.WAITING &&
                    gateway.frontDefroster().valueOrNull() == true) markDone("defrost")
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
                is Read.Ok -> if (now.value) TaskStatus.ALREADY_SET to "이미 설정되어 있어요" else TaskStatus.WAITING to "조작하면 차량 상태를 다시 읽어 자동으로 완료돼요"
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

    /** 성에 제거는 켠 채로 두지 않도록 끄는 방법까지 알려 준다. */
    private fun doneDetail(id: String) =
        if (id == "defrost") "차량에서 변경을 확인했어요. 지금 필요 없으면 한 번 더 눌러 끄세요." else "차량에서 변경을 확인했어요"

    private fun markDone(id: String) {
        if (task(id).status == TaskStatus.WAITING) Log.i("EasyDrive", "practice $id -> DONE (차량에서 변경 확인)")
        _tasks.update { list ->
            list.map { if (it.id == id && it.status == TaskStatus.WAITING) it.copy(status = TaskStatus.DONE, detail = doneDetail(it.id)) else it }
        }
    }

    private companion object {
        // TelltaleColor id (WHITE 0, GREEN 1, BLUE 2, YELLOW 3, RED 4, GRAY 5)
        const val COLOR_YELLOW = 3
        const val COLOR_RED = 4
    }

}
