package com.easydrive.app

import android.app.Application
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.domain.AppController
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.VehicleGateway
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EasyDriveApp : Application() {

    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        // 예상하지 못한 예외 하나로 앱 전체가 종료되지 않도록 기록만 하고 넘어간다.
        val handler = CoroutineExceptionHandler { _, t -> Log.e("EasyDrive", "unexpected error in app scope", t) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        controller = AppController(
            scope = scope,
            gateway = VehicleGateway(this),
            restriction = DrivingRestriction(this),
            store = CheckinStore(this),
        )
        controller.start()
    }
}
