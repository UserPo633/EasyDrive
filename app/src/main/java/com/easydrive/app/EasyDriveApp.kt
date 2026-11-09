package com.easydrive.app

import android.app.Application
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.domain.AppController
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EasyDriveApp : Application() {

    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controller = AppController(
            scope = scope,
            gateway = VehicleGateway(this),
            restriction = DrivingRestriction(this),
            store = CheckinStore(this),
        )
        controller.start()
    }
}
