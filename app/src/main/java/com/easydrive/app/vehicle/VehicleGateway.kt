package com.easydrive.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 공개 권한(CAR_SAFETY)으로 읽을 수 있는 차량 경고 상태.
 * 계기판 경고등 전체 목록(Telltales)과 타이어 공기압은 비공개 권한(PRIVATE_CAR_SAFETY)이 필요해 일반 앱에서는 쓰지 않는다.
 */
/** 전조등 과제의 확인 기준. */
enum class LightGoal { AUTO, ON_SWITCH, ON_STATE }

enum class BasicWarning { SEAT_BELT, WASHER_LOW, POWER_DOWN, ENGINE_OIL, BRAKE_FLUID, TRANSMISSION_OIL, AIRBAG_DEPLOYED }

/** 공개 경고 상태 조회 결과: 켜진 경고와, 이 차량에서 실제로 읽을 수 있었던 항목 */
data class BasicWarnings(val active: List<BasicWarning>, val checked: List<BasicWarning>)

/**
 * Pleos Vehicle SDK(Vehicle State) 호출을 한곳에 모은 읽기 전용 게이트웨이.
 *
 * 공식 Sample App의 흐름(지원 범위 확인 → 현재값 조회 → 변경 알림 구독·해제)을 따른다.
 * 모든 콜백 API를 코루틴으로 감싸고, 응답이 없으면 [Read.Unavailable]로 돌려준다.
 * set·move 계열 제어 API는 사용하지 않는다.
 */
class VehicleGateway(context: Context) {

    // SDK 객체를 만들지 못해도(Pleos 서비스가 없는 환경 등) 앱이 종료되지 않도록 모든 항목을 '확인 불가'로 표시한다.
    private val vehicleOrNull: Vehicle? = runCatching { Vehicle(context.applicationContext) }
        .onFailure { Log.w(TAG, "Vehicle() creation failed", it) }
        .getOrNull()
    private val vehicle: Vehicle get() = vehicleOrNull ?: error("Vehicle SDK를 사용할 수 없음")
    private var initialized = false
    private var lastInitAt = 0L

    fun initialize() {
        if (initialized) return
        val v = vehicleOrNull ?: return
        lastInitAt = System.currentTimeMillis()
        runCatching { v.initialize() }
            .onSuccess { initialized = true }
            .onFailure { Log.w(TAG, "Vehicle.initialize failed", it) }
    }

    /**
     * SDK는 차량 서비스 연결을 일정 횟수만 재시도한 뒤 포기한다(이후 모든 호출이 바로 실패).
     * 그 상태가 되면 10초에 한 번까지만 다시 연결을 요청한다.
     */
    private fun reconnectIfDue() {
        val v = vehicleOrNull ?: return
        val now = System.currentTimeMillis()
        if (now - lastInitAt < RECONNECT_INTERVAL_MS) return
        lastInitAt = now
        runCatching { v.initialize() }.onFailure { Log.w(TAG, "Vehicle re-initialize failed", it) }
    }

    /** SDK가 준 영어 오류를 화면에 보여 줄 짧은 한국어 이유로 바꾼다(원문은 로그에 남긴다). */
    private fun reason(raw: String?): String = when {
        raw == null -> "알 수 없는 오류"
        raw.contains("Permission", ignoreCase = true) -> NO_PERMISSION
        raw.contains("connection", ignoreCase = true) || raw.contains("not started", ignoreCase = true) -> { reconnectIfDue(); NOT_CONNECTED }
        raw.contains("not supported", ignoreCase = true) -> "이 영역은 지원하지 않음"
        else -> raw.take(60)
    }

    fun release() {
        if (!initialized) return
        defrosterListener?.let { l ->
            runCatching { vehicle.getHVAC().unregisterHvacDefrosterState(FRONT, l) }
        }
        defrosterListener = null
        runCatching { vehicle.release() }
        initialized = false
    }

    // ---------------------------------------------------------------- 공통 래퍼

    /** 콜백 API 하나를 감싸 [Read]로 변환한다. */
    private suspend fun <T> call(
        name: String,
        block: (onOk: (T?) -> Unit, onFail: (Exception) -> Unit) -> Unit,
    ): Read<T> {
        if (!initialized) return Read.Unavailable(NOT_CONNECTED)
        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    try {
                        block(
                            { v ->
                                if (cont.isActive) cont.resume(
                                    if (v == null) Read.Unavailable("값 없음(null)") else Read.Ok(v)
                                )
                            },
                            { e ->
                                Log.w(TAG, "$name failed: ${e.message}")
                                // 차량이 그 항목을 갖고 있지 않다는 응답(ERROR_NOT_SUPPORTED_PROPERTY)은 '지원 안 함'이다.
                                // Pleos Connect Emulator v2.1.0에서 워셔액·오일류 경고 등이 이렇게 응답한다.
                                val raw = e.message ?: e.javaClass.simpleName
                                if (cont.isActive) cont.resume(
                                    if (raw.contains("NOT_SUPPORTED", ignoreCase = true)) Read.Unsupported else Read.Unavailable(reason(raw))
                                )
                            },
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "$name threw", t)
                        if (cont.isActive) cont.resume(Read.Unavailable(reason(t.message ?: t.javaClass.simpleName)))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Read.Unavailable("응답 없음")
        }
    }

    private fun CapabilityConfig.canRead(): Boolean =
        accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    /** capability 확인 결과가 '읽기 불가'이면 Unsupported, 확인 자체가 실패하면 Unavailable */
    private suspend fun <T> guarded(capability: Read<Boolean>, read: suspend () -> Read<T>): Read<T> =
        when (capability) {
            is Read.Ok -> if (capability.value) read() else Read.Unsupported
            Read.Unsupported -> Read.Unsupported
            is Read.Unavailable -> capability
        }

    // ---------------------------------------------------------------- 경고등

    private var telltalesDenied = false
    private var telltalesWorked = false
    private var telltalesFails = 0

    /**
     * 계기판 경고등 전체 목록. SDK 2.3.2에서 조회(getTelltalesInfo)는 CAR_SAFETY 표시이지만
     * 지원 확인(checkTelltalesCapabilities)은 비공개 권한(PRIVATE_CAR_SAFETY) 표시라서, 지원 확인은 부르지 않고
     * 조회만 시도한다. 한 번도 읽히지 않은 채 거절·미지원·연속 실패가 나오면 다시 부르지 않는다
     * (그 뒤로는 공개 경고 상태 7종만 읽는다). 한 번이라도 읽힌 뒤의 실패는 일시적인 것으로 보고 그대로 돌려준다.
     */
    suspend fun telltales(): Read<Telltales> {
        if (telltalesDenied) return Read.Unavailable(NO_PERMISSION)
        if (!initialized) return Read.Unavailable(NOT_CONNECTED)
        val r = call<Telltales>("getTelltalesInfo") { ok, fail -> vehicle.getSafety().getTelltalesInfo({ ok(it) }, fail) }
        if (r is Read.Ok) {
            if (!telltalesWorked) Log.i(TAG, "telltales readable: ${r.value.telltales.size} items")
            telltalesWorked = true; telltalesFails = 0
            return r
        }
        if (telltalesWorked) return r
        val denied = r is Read.Unsupported || (r is Read.Unavailable && r.reason == NO_PERMISSION) || ++telltalesFails >= 2
        if (denied) {
            telltalesDenied = true
            Log.i(TAG, "telltales not available to this app ($r) -> public warning states only")
            return Read.Unavailable(NO_PERMISSION)
        }
        return r
    }

    // ---------------------------------------------------------------- 공개 경고 상태

    private val capCache = mutableMapOf<String, Boolean>()
    private var seatBeltAreas: List<SeatArea>? = null
    private var airbagAreas: List<SeatArea>? = null

    /** 지원 여부는 주행 중 바뀌지 않으므로 한 번 확인되면 기억해 둔다(주기 조회 부담을 줄임). */
    private suspend fun capability(key: String, check: (ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit): Read<Boolean> {
        capCache[key]?.let { return Read.Ok(it) }
        val r = call<Boolean>("check$key", check)
        if (r is Read.Ok) capCache[key] = r.value
        if (r == Read.Unsupported) capCache[key] = false
        return r
    }

    private fun OilLevelState.isWarning() =
        this == OilLevelState.LOW || this == OilLevelState.CRITICALLY_LOW || this == OilLevelState.ERROR

    /** 좌석별 경고(안전벨트·에어백): 지원 좌석을 한 번 확인해 두고, 좌석 하나라도 참이면 경고로 본다. */
    private suspend fun anySeat(
        name: String,
        cached: List<SeatArea>?,
        remember: (List<SeatArea>) -> Unit,
        check: (ok: (List<SeatArea>?) -> Unit, fail: (Exception) -> Unit) -> Unit,
        read: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
    ): Read<Boolean> {
        val areas = cached ?: when (val a = call<List<SeatArea>>("check$name", check)) {
            is Read.Ok -> a.value.also(remember)
            Read.Unsupported -> { remember(emptyList()); return Read.Unsupported }
            is Read.Unavailable -> return a
        }
        if (areas.isEmpty()) return Read.Unsupported
        var any = false
        var readOne = false
        var failure: Read.Unavailable? = null
        for (area in areas) {
            when (val r = call<Boolean>(name) { ok, fail -> read(area, ok, fail) }) {
                is Read.Ok -> { readOne = true; if (r.value) any = true }
                is Read.Unavailable -> failure = r
                Read.Unsupported -> {}
            }
        }
        return if (readOne) Read.Ok(any) else failure ?: Read.Unsupported
    }

    /**
     * 공개 권한으로 읽을 수 있는 경고 상태 7종을 조회한다.
     * 항목마다 지원 여부를 먼저 확인하고, 읽을 수 있었던 항목([BasicWarnings.checked])을 함께 돌려준다.
     * 하나도 읽지 못했으면 '경고 없음'이 아니라 Unsupported 또는 Unavailable이다.
     */
    suspend fun basicWarnings(): Read<BasicWarnings> {
        val safety = vehicle.getSafety()
        val active = mutableListOf<BasicWarning>()
        val checked = mutableListOf<BasicWarning>()
        var failure: Read.Unavailable? = null
        fun take(w: BasicWarning, r: Read<Boolean>) {
            when (r) {
                is Read.Ok -> { checked += w; if (r.value) active += w }
                is Read.Unavailable -> failure = r
                Read.Unsupported -> {}
            }
        }
        take(BasicWarning.SEAT_BELT, anySeat("SeatBeltNotBuckledWarning", seatBeltAreas, { seatBeltAreas = it },
            { ok, fail -> safety.checkSeatNotBuckledWarningCapabilities({ c, areas -> ok(if (c.canRead()) areas else emptyList()) }, fail) },
            { area, ok, fail -> safety.hasSeatBeltNotBuckledWarning(area, { ok(it) }, fail) }))
        take(BasicWarning.WASHER_LOW, guarded(capability("LowWasherFluidWarning") { ok, fail ->
            safety.checkLowWasherFluidWarningStateCapabilities({ c -> ok(c.canRead()) }, fail)
        }) { call("hasLowWasherFluidWarning") { ok, fail -> safety.hasLowWasherFluidWarning({ ok(it) }, fail) } })
        take(BasicWarning.POWER_DOWN, guarded(capability("PowerDownWarningIndicator") { ok, fail ->
            safety.checkPowerDownWarningIndicatorCapabilities({ c -> ok(c.canRead()) }, fail)
        }) { call("hasPowerDownWarningIndicator") { ok, fail -> safety.hasPowerDownWarningIndicator({ ok(it) }, fail) } })
        take(BasicWarning.ENGINE_OIL, guarded(capability("EngineOilLevelState") { ok, fail ->
            safety.checkEngineOilLevelStateCapabilities({ c, _ -> ok(c.canRead()) }, fail)
        }) { call("getEngineOilLevelState") { ok, fail -> safety.getEngineOilLevelState({ ok(it?.isWarning()) }, fail) } })
        take(BasicWarning.BRAKE_FLUID, guarded(capability("BrakeFluidLevelState") { ok, fail ->
            safety.checkBrakeFluidLevelStateCapabilities({ c, _ -> ok(c.canRead()) }, fail)
        }) { call("getBrakeFluidLevelState") { ok, fail -> safety.getBrakeFluidLevelState({ ok(it?.isWarning()) }, fail) } })
        take(BasicWarning.TRANSMISSION_OIL, guarded(capability("TransmissionOilLevelState") { ok, fail ->
            safety.checkTransmissionOilLevelStateCapabilities({ c, _ -> ok(c.canRead()) }, fail)
        }) { call("getTransmissionOilLevelState") { ok, fail -> safety.getTransmissionOilLevelState({ ok(it?.isWarning()) }, fail) } })
        take(BasicWarning.AIRBAG_DEPLOYED, anySeat("SeatAirbagDeployed", airbagAreas, { airbagAreas = it },
            { ok, fail -> safety.checkAirbagDeployedCapabilities({ c, areas -> ok(if (c.canRead()) areas else emptyList()) }, fail) },
            { area, ok, fail -> safety.hasSeatAirbagBeenDeployed(area, { ok(it) }, fail) }))
        return when {
            checked.isNotEmpty() -> Read.Ok(BasicWarnings(active, checked))
            failure != null -> failure!!
            else -> Read.Unsupported
        }
    }

    // ---------------------------------------------------------------- 주행거리·에너지

    suspend fun odometerKm(): Read<Float> {
        val cap = call<Boolean>("checkCurrentDistanceDrivenCapabilities") { ok, fail ->
            vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities({ c -> ok(c.canRead()) }, fail)
        }
        return guarded(cap) {
            call("getCurrentDistanceDriven") { ok, fail -> vehicle.getOdometer().getCurrentDistanceDriven({ ok(it) }, fail) }
        }
    }

    /** 주행가능거리. VHAL 규약과 Virtual Console 표기에 맞춰 미터 단위로 받는다. */
    suspend fun rangeRemainingMeters(): Read<Float> =
        call("getRangeRemainingDistance") { ok, fail -> vehicle.getOdometer().getRangeRemainingDistance({ ok(it) }, fail) }

    suspend fun evStateOfChargePercent(): Read<Int> {
        val cap = call<Boolean>("checkEvBatteryStateOfChargeCapabilities") { ok, fail ->
            vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ c -> ok(c.canRead()) }, fail)
        }
        return guarded(cap) {
            call("getEvBatteryStateOfCharge") { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge({ ok(it) }, fail) }
        }
    }

    suspend fun fuelPercent(): Read<Int> {
        val cap = call<Boolean>("checkFuelPercentCapabilities") { ok, fail ->
            vehicle.getFuel().checkFuelPercentCapabilities({ c -> ok(c.canRead()) }, fail)
        }
        return guarded(cap) {
            call("getFuelPercent") { ok, fail -> vehicle.getFuel().getFuelPercent({ ok(it) }, fail) }
        }
    }

    // ---------------------------------------------------------------- 도어·트렁크·창문

    /** 지원되는 도어 영역별 열림 여부(위치값이 0보다 크면 열림). */
    suspend fun openDoors(): Read<List<String>> {
        val areas = call<List<DoorArea>>("checkDoorPositionCapabilities") { ok, fail ->
            vehicle.getDoor().checkDoorPositionCapabilities({ map -> ok(map.keys.toList()) }, fail)
        }
        if (areas !is Read.Ok) return areas as Read<List<String>>
        if (areas.value.isEmpty()) return Read.Unsupported
        val open = mutableListOf<String>()
        var readOne = false
        var missed = 0
        for (area in areas.value) {
            val pos = call<Int>("getCurrentDoorPosition") { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(area, { ok(it) }, fail) }
            if (pos is Read.Ok) { readOne = true; if (pos.value > 0) open += doorLabel(area) } else missed++
        }
        // 열린 곳을 찾았으면 그대로 알린다. 일부가 읽히지 않았는데 열린 곳도 없으면 '모두 닫힘'이라고 하지 않는다.
        return when {
            !readOne -> Read.Unavailable("도어 상태를 읽지 못함")
            missed > 0 && open.isEmpty() -> Read.Unavailable("일부 도어를 읽지 못함")
            else -> Read.Ok(open)
        }
    }

    suspend fun openWindows(): Read<List<String>> {
        val areas = call<List<WindowArea>>("checkWindowPositionCapabilities") { ok, fail ->
            vehicle.getWindow().checkWindowPositionCapabilities({ map -> ok(map.keys.toList()) }, fail)
        }
        if (areas !is Read.Ok) return areas as Read<List<String>>
        if (areas.value.isEmpty()) return Read.Unsupported
        val open = mutableListOf<String>()
        var readOne = false
        var missed = 0
        for (area in areas.value) {
            val pos = call<Int>("getCurrentWindowPosition") { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(area, { ok(it) }, fail) }
            if (pos is Read.Ok) { readOne = true; if (pos.value > 0) open += windowLabel(area) } else missed++
        }
        return when {
            !readOne -> Read.Unavailable("창문 상태를 읽지 못함")
            missed > 0 && open.isEmpty() -> Read.Unavailable("일부 창문을 읽지 못함")
            else -> Read.Ok(open)
        }
    }

    // ---------------------------------------------------------------- 실습 과제

    /** 전조등 스위치. AUTO 지원 여부를 먼저 확인한다. */
    suspend fun lowBeamSupportsAuto(): Read<Boolean> =
        call("checkLowBeamLightSwitchCapabilities") { ok, fail ->
            vehicle.getLight().checkLowBeamLightSwitchCapabilities(
                { list: List<LightSwitch> -> ok(list.contains(LightSwitch.AUTOMATIC)) }, fail
            )
        }

    suspend fun lowBeamSwitch(): Read<LightSwitch> =
        call("getLowBeamLightSwitch") { ok, fail -> vehicle.getLight().getLowBeamLightSwitch({ ok(it) }, fail) }

    /**
     * 이 차에서 전조등 과제를 무엇으로 확인할 수 있는지 정한다.
     * 스위치에 AUTO가 있으면 AUTO, 없고 '켜짐'이 있으면 스위치 켜짐, 스위치를 읽을 수 없으면 전조등 점등 상태.
     */
    suspend fun lightGoal(): Read<LightGoal> {
        val sw = call<List<LightSwitch>>("checkLowBeamLightSwitchCapabilities") { ok, fail ->
            vehicle.getLight().checkLowBeamLightSwitchCapabilities({ list: List<LightSwitch> -> ok(list) }, fail)
        }
        Log.i(TAG, "low beam switch options: $sw")
        if (sw is Read.Ok) {
            if (LightSwitch.AUTOMATIC in sw.value) return Read.Ok(LightGoal.AUTO)
            if (LightSwitch.ON in sw.value) return Read.Ok(LightGoal.ON_SWITCH)
        }
        val st = call<List<LightState>>("checkLowBeamLightStateCapabilities") { ok, fail ->
            vehicle.getLight().checkLowBeamLightStateCapabilities({ c, list -> ok(if (c.canRead()) list else emptyList()) }, fail)
        }
        Log.i(TAG, "low beam state options: $st")
        if (st is Read.Ok && LightState.ON in st.value) return Read.Ok(LightGoal.ON_STATE)
        return when {
            sw is Read.Unavailable -> sw
            st is Read.Unavailable -> st
            else -> Read.Unsupported
        }
    }

    suspend fun lightGoalMet(goal: LightGoal): Read<Boolean> = when (goal) {
        LightGoal.AUTO -> lowBeamSwitch().map { it == LightSwitch.AUTOMATIC }
        LightGoal.ON_SWITCH -> lowBeamSwitch().map { it == LightSwitch.ON || it == LightSwitch.AUTOMATIC }
        LightGoal.ON_STATE -> call<LightState>("getCurrentLowBeamLightState") { ok, fail ->
            vehicle.getLight().getCurrentLowBeamLightState({ ok(it) }, fail)
        }.map { it == LightState.ON }
    }

    suspend fun frontWiperSupportsAuto(): Read<Boolean> =
        call("checkWiperSwitchCapabilities") { ok, fail ->
            vehicle.getWiper().checkWiperSwitchCapabilities(
                { map: Map<WindowArea, List<WiperSwitch>> ->
                    val front = map.entries.firstOrNull { it.key.values.contains(WindowValue.FRONT_WINDSHIELD) }
                    ok(front?.value?.contains(WiperSwitch.AUTO) == true)
                }, fail
            )
        }

    suspend fun frontWiperSwitch(): Read<WiperSwitch> =
        call("getCurrentWiperSwitch") { ok, fail -> vehicle.getWiper().getCurrentWiperSwitch(FRONT, { ok(it) }, fail) }

    suspend fun frontDefrosterSupported(): Read<Boolean> =
        call("checkHvacDefrosterCapabilities") { ok, fail ->
            vehicle.getHVAC().checkHvacDefrosterCapabilities(
                { list: List<WindowArea> -> ok(list.any { it.values.contains(WindowValue.FRONT_WINDSHIELD) }) }, fail
            )
        }

    suspend fun frontDefroster(): Read<Boolean> =
        call("getHvacDefrosterState") { ok, fail -> vehicle.getHVAC().getHvacDefrosterState(FRONT, { ok(it) }, fail) }

    // ---- 더 익혀 둘 조작(차가 지원할 때만 과제로 낸다)

    suspend fun rearDefrosterSupported(): Read<Boolean> =
        call("checkHvacDefrosterCapabilities") { ok, fail ->
            vehicle.getHVAC().checkHvacDefrosterCapabilities(
                { list: List<WindowArea> -> ok(list.any { it.values.contains(WindowValue.REAR_WINDSHIELD) }) }, fail
            )
        }

    suspend fun rearDefroster(): Read<Boolean> =
        call("getHvacDefrosterState(rear)") { ok, fail -> vehicle.getHVAC().getHvacDefrosterState(REAR_GLASS, { ok(it) }, fail) }

    suspend fun chargePortSupported(): Read<Boolean> =
        call("checkEvChargePortOpenCapabilities") { ok, fail ->
            vehicle.getEvBattery().checkEvChargePortOpenCapabilities({ c -> ok(c.canRead()) }, fail)
        }

    suspend fun chargePortOpen(): Read<Boolean> =
        call("isEvChargePortOpen") { ok, fail -> vehicle.getEvBattery().isEvChargePortOpen({ ok(it) }, fail) }

    /** 상향등: 스위치에 '켜짐'이 있거나 점등 상태를 읽을 수 있으면 과제로 낸다. */
    suspend fun highBeamSupported(): Read<Boolean> {
        val sw = call<List<LightSwitch>>("checkHighBeamLightSwitchCapabilities") { ok, fail ->
            vehicle.getLight().checkHighBeamLightSwitchCapabilities({ list: List<LightSwitch> -> ok(list) }, fail)
        }
        Log.i(TAG, "high beam switch options: $sw")
        if (sw is Read.Ok && LightSwitch.ON in sw.value) { highBeamBySwitch = true; return Read.Ok(true) }
        val st = call<List<LightState>>("checkHighBeamLightStateCapabilities") { ok, fail ->
            vehicle.getLight().checkHighBeamLightStateCapabilities({ c, list -> ok(if (c.canRead()) list else emptyList()) }, fail)
        }
        Log.i(TAG, "high beam state options: $st")
        if (st is Read.Ok && LightState.ON in st.value) { highBeamBySwitch = false; return Read.Ok(true) }
        return when {
            sw is Read.Unavailable -> sw
            st is Read.Unavailable -> st
            else -> Read.Unsupported
        }
    }

    private var highBeamBySwitch = true

    suspend fun highBeamOn(): Read<Boolean> =
        if (highBeamBySwitch) call<LightSwitch>("getHighBeamLightSwitch") { ok, fail ->
            vehicle.getLight().getHighBeamLightSwitch({ ok(it) }, fail)
        }.map { it == LightSwitch.ON }
        else call<LightState>("getCurrentHighBeamLightState") { ok, fail ->
            vehicle.getLight().getCurrentHighBeamLightState({ ok(it) }, fail)
        }.map { it == LightState.ON }

    // 도어 잠금: 운전석 도어(없으면 목록의 첫 도어)의 잠금 상태를 읽는다. 잠그거나 풀지 않는다.
    private var lockArea: DoorArea? = null

    suspend fun doorLockSupported(): Read<Boolean> =
        call<List<DoorArea>>("checkDoorLockCapabilities") { ok, fail ->
            vehicle.getDoor().checkDoorLockCapabilities({ list: List<DoorArea> -> ok(list) }, fail)
        }.map { list ->
            lockArea = list.firstOrNull { a -> a.values.any { it.name == "ROW_1_LEFT" } } ?: list.firstOrNull()
            lockArea != null
        }

    suspend fun doorLocked(): Read<Boolean> {
        val area = lockArea ?: return Read.Unsupported
        return call("getDoorLockState") { ok, fail -> vehicle.getDoor().getDoorLockState(area, { ok(it) }, fail) }
    }

    /** 기어가 P인지. 실습 과제를 P단에서만 열기 위해 읽는다(주행 규제 잠금은 시스템 신호를 따른다). */
    suspend fun gearParked(): Read<Boolean> =
        call<VehicleGear>("getCurrentGearSelectionState") { ok, fail ->
            vehicle.getGear().getCurrentGearSelectionState({ ok(it) }, fail)
        }.map { it == VehicleGear.GEAR_PARK }

    private var defrosterListener: HvacDefrosterStateListener? = null

    /** 성에 제거 상태 변경 알림 구독. 이미 구독 중이면 교체한다. */
    fun watchFrontDefroster(onChange: (Boolean) -> Unit, onError: (String) -> Unit) {
        if (!initialized) { onError(NOT_CONNECTED); return }
        unwatchFrontDefroster()
        val l = object : HvacDefrosterStateListener {
            override fun onHvacDefrosterStateUpdated(p0: Boolean?) { if (p0 != null) onChange(p0) }
            override fun onFailed(p0: Exception) { onError(p0.message ?: "알림 수신 실패") }
        }
        runCatching { vehicle.getHVAC().registerHvacDefrosterState(FRONT, l) }
            .onSuccess { defrosterListener = l }
            .onFailure { onError(it.message ?: "구독 실패") }
    }

    fun unwatchFrontDefroster() {
        val l = defrosterListener ?: return
        runCatching { vehicle.getHVAC().unregisterHvacDefrosterState(FRONT, l) }
        defrosterListener = null
    }

    companion object {
        private const val TAG = "EasyDrive"
        private const val TIMEOUT_MS = 3_000L
        private const val RECONNECT_INTERVAL_MS = 10_000L
        const val NO_PERMISSION = "앱에 권한이 없음"
        const val NOT_CONNECTED = "차량 서비스에 연결되지 않음"
        private val FRONT = WindowArea(WindowValue.FRONT_WINDSHIELD)
        private val REAR_GLASS = WindowArea(WindowValue.REAR_WINDSHIELD)

        private fun doorLabel(a: DoorArea): String = a.values.joinToString { v ->
            when (v.name) {
                "ROW_1_LEFT" -> "운전석 도어"
                "ROW_1_RIGHT" -> "조수석 도어"
                "ROW_2_LEFT" -> "뒷좌석 왼쪽 도어"
                "ROW_2_RIGHT" -> "뒷좌석 오른쪽 도어"
                "HOOD" -> "후드(보닛)"
                "REAR" -> "트렁크"
                else -> v.name
            }
        }

        private fun windowLabel(a: WindowArea): String = a.values.joinToString { v ->
            when (v.name) {
                "ROW_1_LEFT" -> "운전석 창문"
                "ROW_1_RIGHT" -> "조수석 창문"
                "ROW_2_LEFT" -> "뒷좌석 왼쪽 창문"
                "ROW_2_RIGHT" -> "뒷좌석 오른쪽 창문"
                "ROOF_TOP_1", "ROOF_TOP_2" -> "선루프"
                else -> v.name
            }
        }
    }
}
