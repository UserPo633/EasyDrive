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

    fun start() {
        if (manager != null) return
        try {
            val c = Car.createCar(context.applicationContext)
            val m = c?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (m == null) {
                _state.value = Restriction.UNKNOWN
                return
            }
            car = c
            manager = m
            m.registerListener(listener)
            apply(m.currentCarUxRestrictions)
        } catch (t: Throwable) {
            // android.car 라이브러리가 없거나 서비스 연결 실패 → 상태를 알 수 없으므로 잠금 유지
            Log.w("EasyDrive", "UX restrictions unavailable", t)
            _state.value = Restriction.UNKNOWN
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        _state.value = Restriction.UNKNOWN
    }

    private fun apply(r: CarUxRestrictions?) {
        _state.value = when {
            r == null -> Restriction.UNKNOWN
            r.isRequiresDistractionOptimization -> Restriction.LOCKED
            else -> Restriction.PARKED_OK
        }
    }
}
