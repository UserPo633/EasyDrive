package com.easydrive.app.domain

import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.CheckinStore
import com.easydrive.app.data.WarningEvent
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
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 차량 대신 가짜 응답을 주는 게이트웨이로 앱 로직(AppController)을 검증한다.
 * 에뮬레이터 없이 JVM에서 실행되며, 'Pleos SDK가 실제로 이렇게 응답한다'는 보증은 아니다.
 * 이 테스트가 보는 것은 앱이 어떤 응답이 오든 약속한 대로 반응하는지(0으로 채우지 않기, 규제 중 기록만 하기 등)다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {

    // 색상 id: SDK 선언 순서(WHITE 0, GREEN 1, BLUE 2, YELLOW 3, RED 4, GRAY 5)
    private val YELLOW = 3
    private val GREEN = 1

    private fun tell(type: TelltaleType, on: Boolean = true, color: Int = YELLOW, priority: Int = 1) =
        TelltaleData(type, if (on) TelltaleState.ON else TelltaleState.OFF, color, priority, 0)

    /** 모든 호출에 기본 응답을 깔아 둔 가짜 게이트웨이 */
    private class Fake {
        val gateway = mockk<VehicleGateway>()
        val restriction = mockk<DrivingRestriction>()
        val store = mockk<CheckinStore>()
        val restrictionState = MutableStateFlow(Restriction.PARKED_OK)

        var telltales: Read<Telltales> = Read.Ok(Telltales(emptyList()))
        var basic: Read<BasicWarnings> = Read.Unsupported
        var ev: Read<Int> = Read.Unsupported
        var fuel: Read<Int> = Read.Ok(55)
        var range: Read<Float> = Read.Ok(320_000f)
        var odometer: Read<Float> = Read.Ok(12_345.6f)
        var doors: Read<List<String>> = Read.Ok(emptyList())
        var windows: Read<List<String>> = Read.Ok(emptyList())

        var lightSupport: Read<Boolean> = Read.Ok(true)
        var lightNow: Read<LightSwitch> = Read.Ok(LightSwitch.OFF)
        var wiperSupport: Read<Boolean> = Read.Ok(true)
        var wiperNow: Read<WiperSwitch> = Read.Ok(WiperSwitch.OFF)
        var defrostSupport: Read<Boolean> = Read.Ok(true)
        var defrostNow: Read<Boolean> = Read.Ok(false)

        val savedEvents = mutableListOf<List<WarningEvent>>()
        var savedCheckin: CheckinSnapshot? = null

        init {
            every { gateway.initialize() } just runs
            every { gateway.release() } just runs
            every { gateway.watchFrontDefroster(any(), any()) } just runs
            every { gateway.unwatchFrontDefroster() } just runs
            coEvery { gateway.telltales() } answers { telltales }
            coEvery { gateway.basicWarnings() } answers { basic }
            coEvery { gateway.evStateOfChargePercent() } answers { ev }
            coEvery { gateway.fuelPercent() } answers { fuel }
            coEvery { gateway.rangeRemainingMeters() } answers { range }
            coEvery { gateway.odometerKm() } answers { odometer }
            coEvery { gateway.openDoors() } answers { doors }
            coEvery { gateway.openWindows() } answers { windows }
            coEvery { gateway.lightGoal() } answers { when (val s = lightSupport) { is Read.Ok -> if (s.value) Read.Ok(LightGoal.AUTO) else Read.Unsupported; Read.Unsupported -> Read.Unsupported; is Read.Unavailable -> s } }
            coEvery { gateway.lightGoalMet(any()) } answers { lightNow.map { it == LightSwitch.AUTOMATIC } }
            coEvery { gateway.frontWiperSupportsAuto() } answers { wiperSupport }
            coEvery { gateway.frontWiperSwitch() } answers { wiperNow }
            coEvery { gateway.frontDefrosterSupported() } answers { defrostSupport }
            coEvery { gateway.frontDefroster() } answers { defrostNow }

            every { restriction.state } returns restrictionState
            every { restriction.start() } just runs
            every { restriction.stop() } just runs

            every { store.loadCheckin() } returns null
            every { store.loadEvents() } returns emptyList()
            every { store.saveEvents(any()) } answers { savedEvents += firstArg<List<WarningEvent>>() }
            val snap = slot<CheckinSnapshot>()
            every { store.saveCheckin(capture(snap)) } answers { savedCheckin = snap.captured }
            every { store.clearRental() } just runs
        }

        fun controller(scope: TestScope) = AppController(scope.backgroundScope, gateway, restriction, store)
    }

    // ------------------------------------------------------------------ 체크인 리포트

    @Test
    fun `리포트는 켜진 경고등만 보여 주고 방향지시등 같은 표시등은 뺀다`() = runTest {
        val f = Fake().apply {
            telltales = Read.Ok(Telltales(listOf(
                tell(TelltaleType.TPMS),
                tell(TelltaleType.TURN_SIGNAL_LEFT, color = GREEN),
                tell(TelltaleType.ABS, on = false),
            )))
        }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)

        val w = c.report.value.warnings
        assertTrue(w is Read.Ok)
        assertEquals(listOf("TPMS"), (w as Read.Ok).value.map { it.typeName })
    }

    @Test
    fun `읽지 못한 값은 0이나 정상으로 채우지 않고 미지원과 확인불가를 구분한다`() = runTest {
        val f = Fake().apply {
            telltales = Read.Unavailable("응답 없음")
            odometer = Read.Unsupported
            doors = Read.Unavailable("권한 없음")
        }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)

        val r = c.report.value
        assertTrue("경고등 조회 실패는 '없음'이 아니라 확인 불가", r.warnings is Read.Unavailable)
        assertEquals(Read.Unsupported, r.odometerKm)
        assertTrue(r.openDoors is Read.Unavailable)
    }

    @Test
    fun `전기차 SoC를 못 읽으면 연료로 대체하고 주행가능거리는 미터를 km로 바꾼다`() = runTest {
        val f = Fake().apply { ev = Read.Unsupported; fuel = Read.Ok(55); range = Read.Ok(320_000f) }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)

        val r = c.report.value
        assertEquals("연료", r.energyLabel)
        assertEquals(55, (r.energyPercent as Read.Ok).value)
        assertEquals(320f, (r.rangeKm as Read.Ok).value, 0.001f)

        f.ev = Read.Ok(78)
        c.refreshReport(); advanceTimeBy(100)
        assertEquals("배터리", c.report.value.energyLabel)
        assertEquals(78, (c.report.value.energyPercent as Read.Ok).value)
    }

    @Test
    fun `앱 시작 때 차량 서비스 연결이 늦어도 자동 재시도로 리포트가 채워진다`() = runTest {
        val f = Fake().apply {
            telltales = Read.Unavailable("차량 서비스에 연결되지 않음")
            odometer = Read.Unavailable("차량 서비스에 연결되지 않음")
        }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(1_500)
        assertTrue(c.report.value.warnings is Read.Unavailable)

        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS))))
        f.odometer = Read.Ok(9_999f)
        advanceTimeBy(3_000)

        assertTrue(c.report.value.warnings is Read.Ok)
        assertEquals(9_999f, (c.report.value.odometerKm as Read.Ok).value, 0.001f)
    }

    // ------------------------------------------------------------------ 공개 경고 상태(비공개 권한이 없을 때)

    private val allBasic = BasicWarning.entries.toList()
    private val noPermission = Read.Unavailable(VehicleGateway.NO_PERMISSION)

    @Test
    fun `경고등 목록 권한이 없으면 공개 경고 상태로 넘어가고 읽은 범위를 함께 알려 준다`() = runTest {
        val f = Fake().apply {
            telltales = noPermission
            basic = Read.Ok(BasicWarnings(active = listOf(BasicWarning.WASHER_LOW), checked = allBasic))
        }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)

        val r = c.report.value
        assertEquals(WarningScope.BASIC, r.warningScope)
        assertEquals(listOf("워셔액 부족"), (r.warnings as Read.Ok).value.map { it.explain.name })
        assertEquals(7, r.warningItems.size)
    }

    @Test
    fun `공개 경고도 하나도 읽지 못하면 경고 없음이 아니라 지원 안 함 또는 확인 불가다`() = runTest {
        val f = Fake().apply { telltales = noPermission; basic = Read.Unsupported }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)
        assertEquals(Read.Unsupported, c.report.value.warnings)

        f.basic = Read.Unavailable("응답 없음")
        c.refreshReport(); advanceTimeBy(100)
        assertTrue(c.report.value.warnings is Read.Unavailable)
    }

    @Test
    fun `주행 중 새로 켜진 공개 경고도 기록하고 저장된 이름으로 풀이를 다시 찾는다`() = runTest {
        val f = Fake().apply { telltales = noPermission; basic = Read.Ok(BasicWarnings(emptyList(), allBasic)) }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(3_000)
        f.restrictionState.value = Restriction.LOCKED
        f.basic = Read.Ok(BasicWarnings(listOf(BasicWarning.POWER_DOWN), allBasic))
        advanceTimeBy(3_000)

        val e = c.events.value.single()
        assertTrue(e.whileRestricted)
        assertEquals("출력 제한", TelltaleGuide.explainByName(e.typeName, e.colorId).name)
    }

    @Test
    fun `일시적인 조회 실패로는 읽는 범위를 바꾸지 않아 가짜 이력이 생기지 않는다`() = runTest {
        val f = Fake().apply {
            telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS))))
            basic = Read.Ok(BasicWarnings(emptyList(), allBasic))
        }
        val c = f.controller(this)
        c.start(); advanceTimeBy(3_000)
        f.telltales = Read.Unavailable("응답 없음"); advanceTimeBy(6_000)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS)))); advanceTimeBy(6_000)

        assertTrue(c.events.value.isEmpty())
    }

    @Test
    fun `계속 읽지 못하면 초록색 경고 없음을 남겨 두지 않는다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.start(); advanceTimeBy(3_000)
        assertTrue(c.report.value.warnings is Read.Ok)

        f.telltales = Read.Unavailable("응답 없음")
        advanceTimeBy(7_000)
        assertTrue(c.report.value.warnings is Read.Unavailable)
    }

    @Test
    fun `차량 값이 바뀌면 다시 읽기를 누르지 않아도 화면 값이 따라온다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.start(); advanceTimeBy(3_000)
        f.odometer = Read.Ok(12_400f)
        advanceTimeBy(8_000)

        assertEquals(12_400f, (c.report.value.odometerKm as Read.Ok).value, 0.001f)
    }

    @Test
    fun `주차 브레이크 표시는 확인 필요로 세지 않고 새 이력으로도 남기지 않는다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.start(); advanceTimeBy(3_000)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.PARKING_BRAKE, color = 4))))
        advanceTimeBy(3_000)

        assertTrue(c.events.value.isEmpty())
        c.saveCheckin()
        assertTrue(f.savedCheckin!!.warnings.isEmpty())
    }

    @Test
    fun `배터리 조회가 잠깐 실패해도 연료 차량으로 바뀌어 보이지 않는다`() = runTest {
        val f = Fake().apply { ev = Read.Unavailable("응답 없음"); fuel = Read.Unsupported }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)

        assertNull(c.report.value.energyLabel)
        assertTrue(c.report.value.energyPercent is Read.Unavailable)
    }

    @Test
    fun `첫 인수 기록을 저장하면 그 전에 쌓인 경고 기록은 비운다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.start(); advanceTimeBy(3_000)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.ENGINE))))
        advanceTimeBy(3_000)
        assertEquals(1, c.events.value.size)

        c.saveCheckin()
        assertTrue(c.events.value.isEmpty())
    }

    @Test
    fun `완료한 실습 과제는 화면을 나갔다 와도 완료로 남는다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.startPractice(); advanceTimeBy(500)
        f.lightNow = Read.Ok(LightSwitch.AUTOMATIC); advanceTimeBy(1_500)
        c.stopPractice()
        c.startPractice(); advanceTimeBy(500)

        assertEquals(TaskStatus.DONE, c.tasks.value.first { it.id == "light" }.status)
        c.stopPractice()
    }

    // ------------------------------------------------------------------ 인수 기록·반납

    @Test
    fun `인수 기록은 읽지 못한 항목을 null로 남기고 경고등 판독 여부를 기록한다`() = runTest {
        val f = Fake().apply { telltales = Read.Unavailable("x"); odometer = Read.Unavailable("y") }
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)
        c.saveCheckin()

        val s = f.savedCheckin!!
        assertNull(s.odometerKm)
        assertFalse(s.warningsReadable)
        assertTrue(s.warnings.isEmpty())
        assertEquals(c.checkin.value, s)

        // 같은 컨트롤러에서 값을 읽을 수 있게 되면 실제 값이 저장된다(위 결과가 '초기 상태' 때문이 아님을 확인)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS))))
        f.odometer = Read.Ok(12_000f)
        c.refreshReport(); advanceTimeBy(100)
        c.saveCheckin()
        val s2 = f.savedCheckin!!
        assertEquals(12_000f, s2.odometerKm!!, 0.001f)
        assertTrue(s2.warningsReadable)
        assertEquals(listOf("타이어 공기압"), s2.warnings)
    }

    @Test
    fun `반납을 마치면 인수 기록과 경고등 이력이 지워진다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.refreshReport(); advanceTimeBy(100)
        c.saveCheckin()
        c.finishRental()

        assertNull(c.checkin.value)
        assertTrue(c.events.value.isEmpty())
        verify { f.store.clearRental() }
    }

    // ------------------------------------------------------------------ 경고등 감시

    @Test
    fun `앱 시작 때 이미 켜져 있던 경고등은 새 이력으로 세지 않는다`() = runTest {
        val f = Fake().apply { telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS)))) }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(10_000)

        assertTrue(c.events.value.isEmpty())
    }

    @Test
    fun `주행 규제 중 새로 켜진 경고등은 화면 방해 없이 기록만 하고 규제 중이었음을 표시한다`() = runTest {
        val f = Fake().apply { restrictionState.value = Restriction.PARKED_OK }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(3_000)              // 기준선(경고등 없음) 확보

        f.restrictionState.value = Restriction.LOCKED
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.ENGINE))))
        advanceTimeBy(3_000)

        val ev = c.events.value
        assertEquals(1, ev.size)
        assertEquals("ENGINE", ev[0].typeName)
        assertTrue("주행 중에 켜졌다고 기록", ev[0].whileRestricted)
        assertFalse(ev[0].acknowledged)
        assertTrue(f.savedEvents.isNotEmpty())

        // 같은 경고등이 계속 켜져 있어도 중복 기록하지 않는다
        advanceTimeBy(10_000)
        assertEquals(1, c.events.value.size)

        // 정차 후 확인하면 확인 처리
        f.restrictionState.value = Restriction.PARKED_OK
        c.acknowledgeEvents()
        assertTrue(c.events.value.all { it.acknowledged })
    }

    @Test
    fun `규제 상태를 모를 때 켜진 경고등도 주행 중으로 간주한다`() = runTest {
        val f = Fake().apply { restrictionState.value = Restriction.UNKNOWN }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(3_000)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS))))
        advanceTimeBy(3_000)

        assertTrue(c.events.value.single().whileRestricted)
    }

    @Test
    fun `경고등 조회가 잠깐 실패해도 이력을 지우거나 새로 만들지 않는다`() = runTest {
        val f = Fake().apply { telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS)))) }
        val c = f.controller(this)
        c.start()
        advanceTimeBy(3_000)

        f.telltales = Read.Unavailable("응답 없음")
        advanceTimeBy(6_000)
        f.telltales = Read.Ok(Telltales(listOf(tell(TelltaleType.TPMS))))
        advanceTimeBy(6_000)

        assertTrue("기준선이 유지되어 같은 경고등을 새 이력으로 세지 않음", c.events.value.isEmpty())
    }

    // ------------------------------------------------------------------ 실습 과제

    private fun PracticeTaskList.byId(id: String) = first { it.id == id }

    @Test
    fun `지원되는 과제는 대기 중이다가 차량 값이 바뀌면 완료로 바뀐다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.startPractice()
        advanceTimeBy(500)

        assertEquals(TaskStatus.WAITING, c.tasks.value.byId("light").status)
        assertEquals(TaskStatus.WAITING, c.tasks.value.byId("wiper").status)
        assertEquals(TaskStatus.WAITING, c.tasks.value.byId("defrost").status)

        f.lightNow = Read.Ok(LightSwitch.AUTOMATIC)
        advanceTimeBy(1_500)
        assertEquals(TaskStatus.DONE, c.tasks.value.byId("light").status)
        assertEquals("다른 과제는 그대로", TaskStatus.WAITING, c.tasks.value.byId("wiper").status)

        f.wiperNow = Read.Ok(WiperSwitch.AUTO)
        f.defrostNow = Read.Ok(true)
        advanceTimeBy(1_500)
        assertTrue(c.tasks.value.all { it.status == TaskStatus.DONE })
        c.stopPractice()
    }

    @Test
    fun `전조등에 AUTO 위치가 없는 차에서는 전조등 켜기 과제로 바꾸고 켜지면 완료한다`() = runTest {
        // Pleos Connect Emulator v2.1.0은 전조등 스위치 선택지로 [OFF, ON]만 알려 준다.
        val f = Fake()
        var on = false
        coEvery { f.gateway.lightGoal() } returns Read.Ok(LightGoal.ON_SWITCH)
        coEvery { f.gateway.lightGoalMet(LightGoal.ON_SWITCH) } answers { Read.Ok(on) }
        val c = f.controller(this)
        c.startPractice()
        advanceTimeBy(1_500)
        assertEquals("전조등 켜기", c.tasks.value.byId("light").title)
        assertEquals(TaskStatus.WAITING, c.tasks.value.byId("light").status)

        on = true
        advanceTimeBy(1_500)
        assertEquals(TaskStatus.DONE, c.tasks.value.byId("light").status)
        c.stopPractice()
    }

    @Test
    fun `이미 설정된 상태로 시작하면 완료가 아니라 이미 설정됨으로 표시한다`() = runTest {
        val f = Fake().apply { lightNow = Read.Ok(LightSwitch.AUTOMATIC) }
        val c = f.controller(this)
        c.startPractice()
        advanceTimeBy(500)

        assertEquals(TaskStatus.ALREADY_SET, c.tasks.value.byId("light").status)
    }

    @Test
    fun `미지원은 지원 안 함으로, 지원 여부를 끝내 못 읽으면 확인 불가로 표시하고 완료 처리하지 않는다`() = runTest {
        val f = Fake().apply {
            lightSupport = Read.Ok(false)
            wiperSupport = Read.Unsupported
            defrostSupport = Read.Unavailable("권한 없음")
        }
        val c = f.controller(this)
        c.startPractice()
        advanceTimeBy(5_000)           // 확인 불가는 1초 간격으로 3번 재시도

        assertEquals(TaskStatus.UNSUPPORTED, c.tasks.value.byId("light").status)
        assertEquals(TaskStatus.UNSUPPORTED, c.tasks.value.byId("wiper").status)
        assertEquals(TaskStatus.UNAVAILABLE, c.tasks.value.byId("defrost").status)
        assertFalse(c.tasks.value.any { it.status == TaskStatus.DONE })
    }

    @Test
    fun `서비스 연결이 늦어 처음에 확인 불가여도 재시도로 회복한다`() = runTest {
        val f = Fake().apply { lightSupport = Read.Unavailable("차량 서비스에 연결되지 않음") }
        val c = f.controller(this)
        c.startPractice()
        advanceTimeBy(1_200)
        f.lightSupport = Read.Ok(true)
        advanceTimeBy(2_000)

        assertEquals(TaskStatus.WAITING, c.tasks.value.byId("light").status)
    }

    @Test
    fun `반납을 마치면 실습 과제도 처음 상태로 돌아간다`() = runTest {
        val f = Fake()
        val c = f.controller(this)
        c.startPractice(); advanceTimeBy(500)
        f.lightNow = Read.Ok(LightSwitch.AUTOMATIC); advanceTimeBy(1_500)
        assertEquals(TaskStatus.DONE, c.tasks.value.first { it.id == "light" }.status)
        c.stopPractice()

        c.finishRental()
        assertTrue(c.tasks.value.none { it.status == TaskStatus.DONE })
    }

    // ------------------------------------------------------------------ 안전: 제어 API 미사용

    @Test
    fun `게이트웨이는 읽기 전용이라 set 또는 move 계열 공개 메서드가 없다`() {
        val bad = VehicleGateway::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .filter { it.startsWith("set") || it.startsWith("move") || it.startsWith("control") }
        assertTrue("제어 계열 메서드 발견: $bad", bad.isEmpty())
    }
}

private typealias PracticeTaskList = List<PracticeTask>
