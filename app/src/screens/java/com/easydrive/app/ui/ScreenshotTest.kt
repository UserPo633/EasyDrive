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
import com.easydrive.app.vehicle.LightGoal
import com.easydrive.app.vehicle.Read
import com.easydrive.app.vehicle.map
import com.easydrive.app.vehicle.Restriction
import com.easydrive.app.vehicle.BasicWarning
import com.easydrive.app.vehicle.BasicWarnings
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

    /** 화면마다 시각이 달라 보이지 않도록 고정한 예시 시각(인수 시점) */
    private val T0 = (System.currentTimeMillis() / 60_000L) * 60_000L - 74 * 60_000L

    private fun tell(t: TelltaleType, color: Int, prio: Int) = TelltaleData(t, TelltaleState.ON, color, prio, 0)

    private fun controller(
        restriction: Restriction = Restriction.PARKED_OK,
        withCheckin: Boolean = false,
        withEvents: Boolean = false,
        lightAuto: Boolean = false,
        tpmsOn: Boolean = withEvents,
    ): AppController {
        val g = mockk<VehicleGateway>()
        every { g.initialize() } just runs
        every { g.release() } just runs
        every { g.watchFrontDefroster(any(), any()) } just runs
        every { g.unwatchFrontDefroster() } just runs
        // 일반 앱 환경: 경고등 목록 권한 없음 → 공개 경고 상태 7종
        coEvery { g.telltales() } returns Read.Unavailable(VehicleGateway.NO_PERMISSION)
        coEvery { g.basicWarnings() } returns Read.Ok(BasicWarnings(if (tpmsOn) listOf(BasicWarning.POWER_DOWN) else emptyList(), listOf(BasicWarning.POWER_DOWN)))
        coEvery { g.evStateOfChargePercent() } returns Read.Ok(if (withCheckin) 64 else 78)
        coEvery { g.fuelPercent() } returns Read.Unsupported
        coEvery { g.rangeRemainingMeters() } returns Read.Ok(if (withCheckin) 340_000f else 410_000f)
        coEvery { g.odometerKm() } returns Read.Ok(if (withCheckin) 12_419f else 12_345f)
        coEvery { g.openDoors() } returns Read.Ok(emptyList())
        coEvery { g.openWindows() } returns Read.Ok(emptyList())
        coEvery { g.lightGoal() } returns Read.Ok(LightGoal.AUTO)
        coEvery { g.lightGoalMet(any()) } returns Read.Ok(lightAuto)
        coEvery { g.frontWiperSupportsAuto() } returns Read.Ok(true)
        coEvery { g.frontWiperSwitch() } returns Read.Ok(WiperSwitch.OFF)
        coEvery { g.frontDefrosterSupported() } returns Read.Ok(true)
        coEvery { g.frontDefroster() } returns Read.Ok(false)

        val r = mockk<DrivingRestriction>()
        every { r.state } returns MutableStateFlow(restriction)
        every { r.start() } just runs
        every { r.stop() } just runs

        val s = mockk<CheckinStore>(relaxed = true)
        every { s.loadCheckin() } returns if (withCheckin) CheckinSnapshot(T0, 12_345f, "배터리", 78, 410f, emptyList(), true) else null
        every { s.loadEvents() } returns if (withEvents) listOf(
            WarningEvent("BASIC_POWER_DOWN", 3, T0 + 45 * 60_000L, true)
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
    @Test fun practice() {
        // 대표 화면: 사용자가 전조등 레버를 AUTO로 돌린 직후(완료 1개, 나머지 대기)
        val g = mockk<VehicleGateway>(relaxed = true)
        var light: Read<LightSwitch> = Read.Ok(LightSwitch.OFF)
        coEvery { g.lightGoal() } returns Read.Ok(LightGoal.AUTO)
        coEvery { g.lightGoalMet(any()) } answers { light.map { it == LightSwitch.AUTOMATIC } }
        coEvery { g.frontWiperSupportsAuto() } returns Read.Ok(true)
        coEvery { g.frontWiperSwitch() } returns Read.Ok(WiperSwitch.OFF)
        coEvery { g.frontDefrosterSupported() } returns Read.Ok(true)
        coEvery { g.frontDefroster() } returns Read.Ok(false)
        coEvery { g.telltales() } returns Read.Unavailable(VehicleGateway.NO_PERMISSION)
        coEvery { g.basicWarnings() } returns Read.Ok(BasicWarnings(emptyList(), listOf(BasicWarning.POWER_DOWN)))
        coEvery { g.evStateOfChargePercent() } returns Read.Ok(78)
        coEvery { g.fuelPercent() } returns Read.Unsupported
        coEvery { g.rangeRemainingMeters() } returns Read.Ok(410_000f)
        coEvery { g.odometerKm() } returns Read.Ok(12_345f)
        coEvery { g.openDoors() } returns Read.Ok(emptyList())
        coEvery { g.openWindows() } returns Read.Ok(emptyList())
        val r = mockk<DrivingRestriction>(relaxed = true)
        every { r.state } returns MutableStateFlow(Restriction.PARKED_OK)
        val st = mockk<CheckinStore>(relaxed = true)
        every { st.loadCheckin() } returns null
        every { st.loadEvents() } returns emptyList()
        val c = AppController(CoroutineScope(Dispatchers.Main.immediate), g, r, st)
        rule.setContent { EasyDriveRoot(c, Tab.PRACTICE) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS); rule.waitForIdle()
        light = Read.Ok(LightSwitch.AUTOMATIC)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS); rule.waitForIdle()
        val bmp = rule.runOnUiThread<Bitmap> {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        File("build/screens").apply { mkdirs() }.resolve("2_practice.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun warnings() = shoot("3_warnings", Tab.WARNINGS, controller(withEvents = true))
    @Test fun returnTab() = shoot("4_return", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** 반납 화면 전체(QR 카드 포함)를 보기 위한 세로로 긴 캡처 */
    @Test @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun returnTabFull() = shoot("4_return_full", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** Pleos Connect Emulator v2.1.0 기기 정의(2560x1440px, 160dpi)와 같은 화면에서 2배로 커지는지 확인 */
    @Test @Config(qualifiers = "w2560dp-h1440dp-mdpi")
    fun checkinPleosEmulator() = shoot("7_checkin_2560x1440", Tab.CHECKIN, controller())

    @Test @Config(qualifiers = "w2560dp-h1440dp-mdpi")
    fun returnPleosEmulator() = shoot("8_return_2560x1440", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** 화면을 좌우로 나눠 쓰는 창(1280x1440dp)에서 세로로 쌓는 배치가 되는지 확인 */
    @Test @Config(qualifiers = "w1280dp-h1440dp-mdpi")
    fun checkinSplitWindow() = shoot("9_checkin_split_1280x1440", Tab.CHECKIN, controller())

    @Test @Config(qualifiers = "w1280dp-h1440dp-mdpi")
    fun returnSplitWindow() = shoot("10_return_split_1280x1440", Tab.RETURN, controller(withCheckin = true, withEvents = true))

    /** 작은 차량 화면에서도 깨지지 않는지 확인 */
    @Test @Config(qualifiers = "w1024dp-h420dp-mdpi")
    fun checkinSmall() = shoot("6_checkin_small", Tab.CHECKIN, controller())
    @Test fun locked() = shoot("5_locked", Tab.CHECKIN, controller(restriction = Restriction.LOCKED))

    /**
     * Pleos Connect Emulator v2.1.0에서 실제로 앱에 주어진 영역(홈 화면 오른쪽 패널): 창 1682x1350px 중
     * 하단 시스템 바 140px를 뺀 1682x1210dp. 에뮬레이터에서 읽은 구성 정보와 같은 크기로 그린다.
     */
    @Test @Config(qualifiers = "w1682dp-h1210dp-mdpi")
    fun checkinPleosPanel() = shoot("11_checkin_pleos_panel_1682x1210", Tab.CHECKIN, controller())

    @Test @Config(qualifiers = "w1682dp-h1210dp-mdpi")
    fun practicePleosPanel() = shoot("12_practice_pleos_panel_1682x1210", Tab.PRACTICE, controller())

    @Test @Config(qualifiers = "w1682dp-h1210dp-mdpi")
    fun warningsPleosPanel() = shoot("13_warnings_pleos_panel_1682x1210", Tab.WARNINGS, controller(tpmsOn = true, withEvents = true))

    @Test @Config(qualifiers = "w1682dp-h1210dp-mdpi")
    fun returnPleosPanel() = shoot("14_return_pleos_panel_1682x1210", Tab.RETURN, controller(withCheckin = true, withEvents = true))
}
