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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 시연 영상에 쓰는 장면별 화면을 실제 Compose 화면 코드로 그린다(가짜 차량 응답).
 * 결과물: app/build/demo 폴더의 PNG 파일
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1600dp-h640dp-mdpi", sdk = [34], application = android.app.Application::class)
open class DemoFramesTest {

    /** 결과 PNG를 저장할 폴더(세로 배치용 하위 클래스가 바꾼다) */
    protected open val outDir = "build/demo"

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fake {
        val g = mockk<VehicleGateway>()
        val r = mockk<DrivingRestriction>()
        val s = mockk<CheckinStore>(relaxed = true)
        val restriction = MutableStateFlow(Restriction.PARKED_OK)
        var active: List<BasicWarning> = emptyList()
        var windows: Read<List<String>> = Read.Ok(emptyList())
        var odometer: Read<Float> = Read.Ok(12_345f)
        var soc: Read<Int> = Read.Ok(78)
        var lightSupport: Read<Boolean> = Read.Ok(true)
        var light: Read<LightSwitch> = Read.Ok(LightSwitch.OFF)
        var wiper: Read<WiperSwitch> = Read.Ok(WiperSwitch.OFF)
        var defrostSupport: Read<Boolean> = Read.Ok(true)
        var defrost: Read<Boolean> = Read.Ok(false)
        var checkin: CheckinSnapshot? = null
        var events: List<WarningEvent> = emptyList()

        init {
            every { g.initialize() } just runs
            every { g.release() } just runs
            every { g.watchFrontDefroster(any(), any()) } just runs
            every { g.unwatchFrontDefroster() } just runs
            coEvery { g.telltales() } returns Read.Unavailable(VehicleGateway.NO_PERMISSION)
            coEvery { g.basicWarnings() } answers { Read.Ok(BasicWarnings(active, listOf(BasicWarning.POWER_DOWN))) }
            coEvery { g.evStateOfChargePercent() } answers { soc }
            coEvery { g.fuelPercent() } returns Read.Unsupported
            coEvery { g.rangeRemainingMeters() } answers { Read.Ok(if ((soc as? Read.Ok)?.value == 64) 340_000f else 410_000f) }
            coEvery { g.odometerKm() } answers { odometer }
            coEvery { g.openDoors() } returns Read.Ok(emptyList())
            coEvery { g.openWindows() } answers { windows }
            coEvery { g.lightGoal() } answers { when (val s = lightSupport) { is Read.Ok -> if (s.value) Read.Ok(LightGoal.AUTO) else Read.Unsupported; Read.Unsupported -> Read.Unsupported; is Read.Unavailable -> s } }
            coEvery { g.lightGoalMet(any()) } answers { light.map { it == LightSwitch.AUTOMATIC } }
            coEvery { g.frontWiperSupportsAuto() } returns Read.Ok(true)
            coEvery { g.frontWiperSwitch() } answers { wiper }
            coEvery { g.frontDefrosterSupported() } answers { defrostSupport }
            coEvery { g.frontDefroster() } answers { defrost }
            every { r.state } returns restriction
            every { r.start() } just runs
            every { r.stop() } just runs
            every { s.loadCheckin() } answers { checkin }
            every { s.loadEvents() } answers { events }
        }
        fun controller() = AppController(CoroutineScope(Dispatchers.Main.immediate), g, r, s)
    }

    /** 장면마다 시각이 어긋나 보이지 않도록 분 단위로 맞춘 기준 시각 */
    private val base = (System.currentTimeMillis() / 600_000L) * 600_000L   // 10분 단위로 맞춰 장면 간 시각이 어긋나지 않게 한다
    private fun now() = base
    private fun idle(ms: Long = 300) { ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS); rule.waitForIdle() }

    private fun capture(name: String) {
        idle()
        val bmp = rule.runOnUiThread<Bitmap> {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        File(outDir).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun c_load() { val f = Fake(); val c = f.controller(); rule.setContent { EasyDriveRoot(c, Tab.CHECKIN) }; capture("c_load") }

    @Test fun c_ok_and_saved() {
        val f = Fake(); val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.CHECKIN) }
        c.refreshReport(); capture("c_ok")
        c.saveCheckin(); capture("c_saved")
    }

    @Test fun unsup() {
        val f = Fake().apply { windows = Read.Unavailable("응답 없음") }
        val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.CHECKIN) }
        c.refreshReport(); idle(); c.saveCheckin(); capture("unsup")
    }

    @Test fun practice() {
        val f = Fake(); val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.PRACTICE) }
        idle(1500); capture("p0")
        f.light = Read.Ok(LightSwitch.AUTOMATIC); idle(1500); capture("p1")
        f.wiper = Read.Ok(WiperSwitch.AUTO); idle(1500); capture("p2")
        f.defrost = Read.Ok(true); idle(1500); capture("p3")
    }

    @Test fun unsup2() {
        val f = Fake().apply { light = Read.Ok(LightSwitch.AUTOMATIC); defrostSupport = Read.Unsupported }
        val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.PRACTICE) }
        idle(1500)
        f.wiper = Read.Ok(WiperSwitch.AUTO); idle(1500); capture("unsup2")
    }

    @Test fun drive() {
        val f = Fake().apply { restriction.value = Restriction.LOCKED }
        val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.CHECKIN) }
        capture("drive")
    }

    private fun afterDrive() = Fake().apply {
        active = listOf(BasicWarning.POWER_DOWN)
        odometer = Read.Ok(12_419f); soc = Read.Ok(64)
        checkin = CheckinSnapshot(now() - 178_200_000, 12_345f, "배터리", 78, 410f, emptyList(), true)
        events = listOf(WarningEvent("BASIC_POWER_DOWN", 3, now() - 900_000, true))
    }

    @Test fun banner() {
        val f = afterDrive(); val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.CHECKIN) }
        c.refreshReport(); capture("banner")
    }

    @Test fun w() {
        val f = afterDrive(); val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.WARNINGS) }
        c.refreshReport(); capture("w")
    }

    @Test fun r() {
        val f = afterDrive().apply { events = listOf(WarningEvent("BASIC_POWER_DOWN", 3, now() - 900_000, true, acknowledged = true)) }
        val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.RETURN) }
        c.refreshReport(); capture("r")
    }

    /** 반납 화면 아래쪽(QR 카드)까지 보이도록 세로로 길게 그린다. 영상에서는 아래쪽을 잘라 쓴다. */
    // 높이 719dp까지는 배율이 1.0으로 다른 장면과 같다(720dp를 넘으면 화면 전체가 커진다).
    @Test @Config(qualifiers = "w1600dp-h719dp-mdpi")
    fun r_tall() = r_tallBody("r_tall")

    protected fun r_tallBody(name: String) {
        val f = afterDrive().apply { events = listOf(WarningEvent("BASIC_POWER_DOWN", 3, now() - 900_000, true, acknowledged = true)) }
        val c = f.controller()
        rule.setContent { EasyDriveRoot(c, Tab.RETURN) }
        c.refreshReport(); idle()
        // 화면 맨 아래(QR 카드와 기록 지우기 버튼)까지 스크롤한 상태를 찍는다.
        rule.onNodeWithText("반납 완료 · 기록 지우기").performScrollTo()
        capture(name)
    }
}

/** 숏폼(세로 영상)용: 화면을 좌우로 나눈 창(1280x1440dp)의 세로 배치로 같은 장면을 그린다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h1440dp-mdpi", sdk = [34], application = android.app.Application::class)
class DemoFramesNarrowTest : DemoFramesTest() {
    override val outDir = "build/demo_v"

    /** 반납 화면 아래쪽(QR)까지 보이도록 세로로 길게 그린다. */
    @Test @Config(qualifiers = "w1280dp-h2600dp-mdpi")
    fun r_long() = r_tallBody("r_long")
}
