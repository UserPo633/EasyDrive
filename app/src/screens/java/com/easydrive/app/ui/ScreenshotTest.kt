package com.easydrive.app.ui

import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.data.WarningEvent
import com.easydrive.app.domain.AppController
import com.easydrive.app.vehicle.DrivingRestriction
import com.easydrive.app.vehicle.Read
import com.easydrive.app.vehicle.Restriction
import com.easydrive.app.vehicle.VehicleGateway
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 앱의 실제 Compose 화면 코드를 가짜 차량 데이터로 그려 PNG로 저장한다(에뮬레이터 대체 확인용).
 * 결과물: app/build/screens 폴더의 PNG 파일
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h560dp-mdpi", sdk = [34], application = android.app.Application::class)
class ScreenshotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun tell(t: TelltaleType, color: Int, prio: Int) = TelltaleData(t, TelltaleState.ON, color, prio, 0)

    private fun controller(
        restriction: Restriction = Restriction.PARKED_OK,
        withCheckin: Boolean = false,
        withEvents: Boolean = false,
        lightAuto: Boolean = false,
    ): AppController {
        val g = mockk<VehicleGateway>()
        every { g.initialize() } just runs
        every { g.release() } just runs
        every { g.watchFrontDefroster(any(), any()) } just runs
        every { g.unwatchFrontDefroster() } just runs
        coEvery { g.telltales() } returns Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS, 3, 2), tell(TelltaleType.LOW_EV_BATTERY, 3, 5))))
        coEvery { g.evStateOfChargePercent() } returns Read.Ok(78)
        coEvery { g.fuelPercent() } returns Read.Unsupported
        coEvery { g.rangeRemainingMeters() } returns Read.Ok(312_000f)
        coEvery { g.odometerKm() } returns Read.Ok(12_458f)
        coEvery { g.openDoors() } returns Read.Ok(emptyList())
        coEvery { g.openWindows() } returns Read.Ok(listOf("뒷좌석 왼쪽 창문"))
        coEvery { g.lowBeamSupportsAuto() } returns Read.Ok(true)
        coEvery { g.lowBeamSwitch() } returns Read.Ok(if (lightAuto) LightSwitch.AUTOMATIC else LightSwitch.OFF)
        coEvery { g.frontWiperSupportsAuto() } returns Read.Ok(true)
        coEvery { g.frontWiperSwitch() } returns Read.Ok(WiperSwitch.OFF)
        coEvery { g.frontDefrosterSupported() } returns Read.Unsupported
        coEvery { g.frontDefroster() } returns Read.Unsupported

        val r = mockk<DrivingRestriction>()
        every { r.state } returns MutableStateFlow(restriction)
        every { r.start() } just runs
        every { r.stop() } just runs

        val s = mockk<CheckinStore>(relaxed = true)
        every { s.loadCheckin() } returns if (withCheckin) CheckinSnapshot(
            System.currentTimeMillis() - 3_600_000, 12_410f, "배터리", 92, 340f, emptyList(), true
        ) else null
        every { s.loadEvents() } returns if (withEvents) listOf(
            WarningEvent("TPMS", 3, System.currentTimeMillis() - 600_000, true)
        ) else emptyList()

        return AppController(CoroutineScope(Dispatchers.Main.immediate), g, r, s)
    }

    private fun shoot(name: String, tab: Tab, c: AppController, waitMs: Long = 1500) {
        rule.setContent { EasyDriveRoot(c, tab) }
        c.refreshReport()
        if (tab == Tab.PRACTICE) c.startPractice()
        Thread.sleep(waitMs)
        rule.waitForIdle()
        val bmp = rule.runOnUiThread<Bitmap> {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        val out = File("build/screens").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun checkin() = shoot("1_checkin", Tab.CHECKIN, controller())
    @Test fun practice() = shoot("2_practice", Tab.PRACTICE, controller(lightAuto = true), 2500)
    @Test fun warnings() = shoot("3_warnings", Tab.WARNINGS, controller(withEvents = true))
    @Test fun returnTab() = shoot("4_return", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** 반납 화면 전체(QR 카드 포함)를 보기 위한 세로로 긴 캡처 */
    @Test @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun returnTabFull() = shoot("4_return_full", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** 작은 차량 화면에서도 깨지지 않는지 확인 */
    @Test @Config(qualifiers = "w1024dp-h420dp-mdpi")
    fun checkinSmall() = shoot("6_checkin_small", Tab.CHECKIN, controller())
    @Test fun locked() = shoot("5_locked", Tab.CHECKIN, controller(restriction = Restriction.LOCKED))
}
