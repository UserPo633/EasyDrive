package com.easydrive.app.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 플랫폼 주행 규제 상태. UNKNOWN은 LOCKED와 똑같이 다룬다(잠금 유지). */
enum class Restriction { UNKNOWN, PARKED_OK, LOCKED }

/**
 * Pleos 주행 규제 개발 가이드에 따라 CarUxRestrictionsManager를 유일한 판단 근거로 쓴다.
 * 속도나 기어로 정차 여부를 추정하지 않는다.
 */
class DrivingRestriction(private val context: Context) {

    private val _state = MutableStateFlow(Restriction.UNKNOWN)
    val state: StateFlow<Restriction> = _state.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> apply(r) }

    /**
     * 차량 서비스(car_service)에 연결한다. 기다리지 않고 바로 돌아오며, 서비스가 준비되면 [attach]가 불린다.
     * (예전 방식 Car.createCar(context)는 서비스가 아직 준비되지 않았으면 화면을 최대 5초 멈춘 뒤 포기해,
     *  Pleos Connect 에뮬레이터 부팅 직후 실행했을 때 잠금이 풀리지 않는 문제가 있었다.)
     * 서비스가 끊기면 '알 수 없음'(잠금)으로 돌아가고, 다시 준비되면 자동으로 다시 연결된다.
     */
    fun start() {
        if (car != null) return
        try {
            car = Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) attach(c) else detach()
            }
        } catch (t: Throwable) {
            // android.car 라이브러리가 없거나 연결 실패 → 상태를 알 수 없으므로 잠금 유지
            Log.w("EasyDrive", "UX restrictions unavailable", t)
            _state.value = Restriction.UNKNOWN
        }
    }

    private fun attach(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager = m
            if (m == null) { _state.value = Restriction.UNKNOWN; return }
            m.registerListener(listener)
            apply(m.currentCarUxRestrictions)
        } catch (t: Throwable) {
            Log.w("EasyDrive", "UX restrictions unavailable", t)
            _state.value = Restriction.UNKNOWN
        }
    }

    private fun detach() {
        manager = null
        if (_state.value != Restriction.UNKNOWN) Log.i("EasyDrive", "driving restriction -> UNKNOWN (car service disconnected)")
        _state.value = Restriction.UNKNOWN
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        _state.value = Restriction.UNKNOWN
    }

    private fun apply(r: CarUxRestrictions?) {
        val next = when {
            r == null -> Restriction.UNKNOWN
            r.isRequiresDistractionOptimization -> Restriction.LOCKED
            else -> Restriction.PARKED_OK
        }
        if (next != _state.value) Log.i("EasyDrive", "driving restriction -> $next (active=${r?.activeRestrictions})")
        _state.value = next
    }
}
