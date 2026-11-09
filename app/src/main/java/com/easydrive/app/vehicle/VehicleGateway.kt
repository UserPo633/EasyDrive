package com.easydrive.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK(Vehicle State) 호출을 한곳에 모은 읽기 전용 게이트웨이.
 *
 * 공식 Sample App의 흐름(지원 범위 확인 → 현재값 조회 → 변경 알림 구독·해제)을 따른다.
 * 모든 콜백 API를 코루틴으로 감싸고, 응답이 없으면 [Read.Unavailable]로 돌려준다.
 * set·move 계열 제어 API는 사용하지 않는다.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    fun initialize() {
        if (initialized) return
        runCatching { vehicle.initialize() }
            .onSuccess { initialized = true }
            .onFailure { Log.w(TAG, "Vehicle.initialize failed", it) }
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
        if (!initialized) return Read.Unavailable("차량 서비스에 연결되지 않음")
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
                                if (cont.isActive) cont.resume(Read.Unavailable(e.message ?: e.javaClass.simpleName))
                            },
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "$name threw", t)
                        if (cont.isActive) cont.resume(Read.Unavailable(t.message ?: t.javaClass.simpleName))
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

    suspend fun telltales(): Read<Telltales> {
        val cap = call<Boolean>("checkTelltalesCapabilities") { ok, fail ->
            vehicle.getSafety().checkTelltalesCapabilities({ c -> ok(c.canRead()) }, fail)
        }
        return guarded(cap) {
            call("getTelltalesInfo") { ok, fail -> vehicle.getSafety().getTelltalesInfo({ ok(it) }, fail) }
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

    /** 지원되는 도어 영역별 열림 여부(위치값 > 최솟값이면 열림). */
    suspend fun openDoors(): Read<List<String>> {
        val areas = call<List<DoorArea>>("checkDoorPositionCapabilities") { ok, fail ->
            vehicle.getDoor().checkDoorPositionCapabilities({ map -> ok(map.keys.toList()) }, fail)
        }
        if (areas !is Read.Ok) return areas as Read<List<String>>
        if (areas.value.isEmpty()) return Read.Unsupported
        val open = mutableListOf<String>()
        for (area in areas.value) {
            when (val pos = call<Int>("getCurrentDoorPosition") { ok, fail ->
                vehicle.getDoor().getCurrentDoorPosition(area, { ok(it) }, fail)
            }) {
                is Read.Ok -> if (pos.value > 0) open += doorLabel(area)
                else -> return Read.Unavailable("일부 도어 상태를 읽지 못함")
            }
        }
        return Read.Ok(open)
    }

    suspend fun openWindows(): Read<List<String>> {
        val areas = call<List<WindowArea>>("checkWindowPositionCapabilities") { ok, fail ->
            vehicle.getWindow().checkWindowPositionCapabilities({ map -> ok(map.keys.toList()) }, fail)
        }
        if (areas !is Read.Ok) return areas as Read<List<String>>
        if (areas.value.isEmpty()) return Read.Unsupported
        val open = mutableListOf<String>()
        for (area in areas.value) {
            when (val pos = call<Int>("getCurrentWindowPosition") { ok, fail ->
                vehicle.getWindow().getCurrentWindowPosition(area, { ok(it) }, fail)
            }) {
                is Read.Ok -> if (pos.value > 0) open += windowLabel(area)
                else -> return Read.Unavailable("일부 창문 상태를 읽지 못함")
            }
        }
        return Read.Ok(open)
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

    private var defrosterListener: HvacDefrosterStateListener? = null

    /** 성에 제거 상태 변경 알림 구독. 이미 구독 중이면 교체한다. */
    fun watchFrontDefroster(onChange: (Boolean) -> Unit, onError: (String) -> Unit) {
        if (!initialized) { onError("차량 서비스에 연결되지 않음"); return }
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
        private val FRONT = WindowArea(WindowValue.FRONT_WINDSHIELD)

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
