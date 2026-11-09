package com.easydrive.app.domain

import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.data.WarningEvent
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.Read
import com.easydrive.app.vehicle.Restriction
import com.easydrive.app.vehicle.VehicleGateway
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

/** 체크인 리포트 화면에 보여 줄 현재 상태 */
data class CheckinReport(
    val loading: Boolean = true,
    val warnings: Read<List<ActiveWarning>> = Read.Unavailable("조회 전"),
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
            refreshReport()
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
        scope.launch {
            _report.update { it.copy(loading = true) }
            val warnings = readActiveWarnings()
            val ev = gateway.evStateOfChargePercent()
            val (label, energy) = if (ev is Read.Ok) "배터리" to ev else "연료" to gateway.fuelPercent()
            val range = when (val r = gateway.rangeRemainingMeters()) {
                is Read.Ok -> Read.Ok(r.value / 1000f, r.readAtMillis)
                is Read.Unavailable -> r
                Read.Unsupported -> Read.Unsupported
            }
            _report.value = CheckinReport(
                loading = false,
                warnings = warnings,
                energyLabel = label,
                energyPercent = energy,
                rangeKm = range,
                odometerKm = gateway.odometerKm(),
                openDoors = gateway.openDoors(),
                openWindows = gateway.openWindows(),
                readAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun saveCheckin() {
        val r = _report.value
        val snap = CheckinSnapshot(
            savedAtMillis = System.currentTimeMillis(),
            odometerKm = r.odometerKm.valueOrNull(),
            energyLabel = r.energyLabel,
            energyPercent = r.energyPercent.valueOrNull(),
            rangeKm = r.rangeKm.valueOrNull(),
            warnings = r.warnings.valueOrNull()?.map { it.explain.name } ?: emptyList(),
            warningsReadable = r.warnings is Read.Ok,
        )
        store.saveCheckin(snap)
        _checkin.value = snap
    }

    fun finishRental() {
        store.clearRental()
        _checkin.value = null
        _events.value = emptyList()
    }

    // ------------------------------------------------------------ 경고등 감시

    private suspend fun readActiveWarnings(): Read<List<ActiveWarning>> =
        when (val t = gateway.telltales()) {
            is Read.Ok -> Read.Ok(
                t.value.telltales
                    .filter { it.state.name != "OFF" && TelltaleGuide.isWarning(it.type, it.color) }
                    .sortedBy { it.priority }
                    .map {
                        ActiveWarning(
                            typeName = it.type?.name ?: "UNKNOWN",
                            colorId = it.color,
                            blinking = it.state.name == "BLINK",
                            explain = TelltaleGuide.explain(it.type, it.color),
                        )
                    },
                t.readAtMillis,
            )
            Read.Unsupported -> Read.Unsupported
            is Read.Unavailable -> t
        }

    /**
     * 앱 프로세스가 살아 있는 동안 2초마다 경고등을 조회한다.
     * 새로 켜진 경고등은 기록만 하고, 주행 규제 중에는 화면에 띄우지 않는다(정차 후 카드로 안내).
     * registerTelltales는 비공개 권한이 필요할 수 있어 공개 권한(CAR_SAFETY)인 조회 API를 주기적으로 쓴다.
     */
    private fun startTelltaleMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            delay(1500)
            var previous: Set<String>? = null
            while (isActive) {
                val now = readActiveWarnings()
                if (now is Read.Ok) {
                    val names = now.value.map { it.typeName }.toSet()
                    val prev = previous
                    if (prev != null) {
                        val newOnes = now.value.filter { it.typeName !in prev }
                        if (newOnes.isNotEmpty()) {
                            val restricted = restriction.state.value != Restriction.PARKED_OK
                            val added = newOnes.map {
                                WarningEvent(it.typeName, it.colorId, System.currentTimeMillis(), restricted)
                            }
                            _events.update { (it + added).takeLast(50) }
                            store.saveEvents(_events.value)
                        }
                    }
                    previous = names
                    _report.update { r -> if (r.loading) r else r.copy(warnings = now) }
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
        PracticeTask("light", "전조등 AUTO", "왼쪽 라이트 레버(또는 조명 버튼)를 AUTO 위치로 돌려 보세요."),
        PracticeTask("wiper", "앞 와이퍼 AUTO", "오른쪽 와이퍼 레버를 AUTO 위치로 바꿔 보세요."),
        PracticeTask("defrost", "앞유리 성에 제거", "공조 조작부의 앞유리 성에 제거 버튼을 눌러 보세요."),
    )

    /** 실습 화면에 들어오면 호출. 지원 여부를 먼저 확인하고, 시작값을 기준으로 변화를 지켜본다. */
    fun startPractice() {
        practiceJob?.cancel()
        _tasks.value = initialTasks()
        practiceJob = scope.launch {
            // 앱 시작 직후에는 차량 서비스 바인딩이 끝나지 않았을 수 있어 '확인 불가'면 잠시 후 다시 확인한다.
            val lightOk = retryUnavailable { gateway.lowBeamSupportsAuto() }
            val wiperOk = retryUnavailable { gateway.frontWiperSupportsAuto() }
            val defrostOk = retryUnavailable { gateway.frontDefrosterSupported() }

            setInitial("light", lightOk) { gateway.lowBeamSwitch().map { it == LightSwitch.AUTOMATIC } }
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
                    gateway.lowBeamSwitch().valueOrNull() == LightSwitch.AUTOMATIC) markDone("light")
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
        val (status, detail) = when (supported) {
            is Read.Unavailable -> TaskStatus.UNAVAILABLE to "지원 여부를 확인하지 못했어요 (${supported.reason})"
            Read.Unsupported -> TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
            is Read.Ok -> if (!supported.value) {
                TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
            } else when (val now = readSatisfied()) {
                is Read.Ok -> if (now.value) TaskStatus.ALREADY_SET to "이미 설정되어 있어요" else TaskStatus.WAITING to "차량이 바뀐 상태를 알려 주면 자동으로 완료돼요"
                Read.Unsupported -> TaskStatus.UNSUPPORTED to "이 차량에서는 지원하지 않는 기능이에요"
                is Read.Unavailable -> TaskStatus.UNAVAILABLE to "현재 상태를 읽지 못했어요 (${now.reason})"
            }
        }
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

    private fun markDone(id: String) {
        _tasks.update { list ->
            list.map { if (it.id == id && it.status == TaskStatus.WAITING) it.copy(status = TaskStatus.DONE, detail = "차량에서 변경을 확인했어요") else it }
        }
    }

    private inline fun <T, R> Read<T>.map(f: (T) -> R): Read<R> = when (this) {
        is Read.Ok -> Read.Ok(f(value), readAtMillis)
        Read.Unsupported -> Read.Unsupported
        is Read.Unavailable -> this
    }
}
