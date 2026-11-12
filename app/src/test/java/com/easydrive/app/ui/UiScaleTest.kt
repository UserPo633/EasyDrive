package com.easydrive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.easydrive.app.domain.PracticeTask
import com.easydrive.app.domain.TaskStatus
import com.easydrive.app.vehicle.NaviDestination
import com.easydrive.app.vehicle.Read
import org.junit.Test

/** 화면 크기별 배율과 배치: 넓은 차량 화면에서는 키우고, 작은 화면에서는 줄이지 않으며, 좁은 창에서는 세로로 쌓는다. */
class UiScaleTest {
    @Test
    fun `Pleos 에뮬레이터 기본 화면 2560x1440dp에서는 2배로 키우고 가로 배치를 쓴다`() {
        assertEquals(2.0f, uiScale(2560, 1440), 0.001f)
        assertFalse(isNarrow(2560, 1440))
    }

    @Test
    fun `그 화면을 좌우로 나눈 창 1280x1440dp에서도 2배를 유지하고 세로 배치로 바꾼다`() {
        assertEquals(2.0f, uiScale(1280, 1440), 0.001f)
        assertTrue(isNarrow(1280, 1440))
    }

    @Test
    fun `설계 기준 화면에서는 그대로 둔다`() {
        assertEquals(1.0f, uiScale(1280, 720), 0.001f)
        assertFalse(isNarrow(1280, 720))
    }

    @Test
    fun `가로로만 넓은 화면은 키우지 않는다`() = assertEquals(1.0f, uiScale(1920, 720), 0.001f)

    @Test
    fun `설계 기준보다 작은 화면에서도 글자를 줄이지 않는다`() = assertEquals(1.0f, uiScale(1024, 420), 0.001f)

    @Test
    fun `세로 화면에서는 폭이 남는 만큼만 키우고 세로 배치를 쓴다`() {
        assertEquals(2.25f, uiScale(1440, 2560), 0.001f)
        assertTrue(isNarrow(1440, 2560))
    }

    @Test
    fun `지나치게 큰 화면은 2_5배까지만 키운다`() = assertEquals(2.5f, uiScale(5120, 2880), 0.001f)

    // --- 시스템 바 영역: Pleos Connect Emulator v2.1.0에서 실제로 받은 구성 정보로 확인한다.
    private val pleosConfig = "{1.0 310mcc260mnc [ko_KR] ldltr sw720dp w1280dp h504dp 160dpi lrg long land car finger " +
        "winConfig={ mBounds=Rect(0, 0 - 1280, 720) mAppBounds=Rect(0, 0 - 1280, 580) mMaxBounds=Rect(0, 0 - 1280, 720) " +
        "mDisplayRotation=ROTATION_0 mWindowingMode=multi-window} s.6}"

    @Test fun `인셋이 전달되지 않아도 구성 정보에서 위 76 아래 140을 찾는다`() {
        assertEquals(BarInsets(top = 76, bottom = 140), barInsetsFromConfiguration(720, 504, pleosConfig, statusBarPx = 0))
    }

    @Test fun `앱 영역 정보가 없으면 상태 바 높이를 위쪽으로 본다`() {
        assertEquals(BarInsets(top = 76, bottom = 140), barInsetsFromConfiguration(720, 504, "{no bounds}", statusBarPx = 76))
    }

    @Test fun `창 높이와 쓸 수 있는 높이가 같으면 여백을 두지 않는다`() {
        assertEquals(BarInsets(), barInsetsFromConfiguration(720, 720, pleosConfig, statusBarPx = 76))
        assertEquals(BarInsets(), barInsetsFromConfiguration(0, 504, pleosConfig, statusBarPx = 76))
    }

    @Test fun `오른쪽 패널로 뜬 창은 상단 바 아래에서 시작하므로 아래쪽만 비운다`() {
        val panel = "{1.0 sw1440dp w1682dp h1210dp 160dpi winConfig={ mBounds=Rect(853, 90 - 2535, 1440) " +
            "mAppBounds=Rect(853, 90 - 2535, 1440) mMaxBounds=Rect(0, 0 - 2560, 1440) mWindowingMode=multi-window}}"
        assertEquals(BarInsets(top = 0, bottom = 140), barInsetsFromConfiguration(1350, 1210, panel, statusBarPx = 76))
    }

    @Test fun `Pleos 오른쪽 패널 크기에서는 배율을 조금 낮춰 가로 배치를 유지한다`() {
        assertEquals(1682 / 1100f, uiScale(1682, 1210), 0.001f)
        assertTrue(!isNarrow(1682, 1210))
    }

    // --- 내비 목적지 표시
    @Test fun `목적지가 주행가능거리 안쪽이면 남은 거리만 적는다`() {
        assertEquals("강릉역까지 132km", destinationNote(Read.Ok(NaviDestination("강릉역", 132_000)), Read.Ok(410f)))
    }

    @Test fun `목적지가 주행가능거리의 80퍼센트를 넘으면 충전 계획을 확인하라고 알린다`() {
        assertEquals("강릉역까지 350km · 주행가능거리와 비슷해요. 충전 계획을 확인하세요", destinationNote(Read.Ok(NaviDestination("강릉역", 350_000)), Read.Ok(410f)))
        assertEquals("목적지까지 500km · 주행가능거리보다 멀어요. 충전 계획을 확인하세요", destinationNote(Read.Ok(NaviDestination(null, 500_000)), Read.Ok(410f)))
    }

    @Test fun `길 안내 중이 아니거나 읽지 못하면 아무것도 적지 않는다`() {
        assertEquals(null, destinationNote(Read.Unsupported, Read.Ok(410f)))
        assertEquals(null, destinationNote(Read.Unavailable("응답 없음"), Read.Ok(410f)))
        assertEquals("강릉역까지 132km", destinationNote(Read.Ok(NaviDestination("강릉역", 132_000)), Read.Unavailable("확인 불가")))
    }

    @Test
    fun `이미 설정돼 있던 과제는 직접 확인한 수에 넣지 않고 따로 센다`() {
        fun t(id: String, st: TaskStatus) = PracticeTask(id, id, "", status = st)
        val s = practiceSummary(listOf(t("a", TaskStatus.DONE), t("b", TaskStatus.ALREADY_SET), t("c", TaskStatus.UNSUPPORTED)))
        assertEquals("2개 중 1개 직접 확인 · 1개는 이미 설정됨 · 1개는 앱이 확인 못 함", s.text)
        assertTrue("조작할 것이 남지 않았으면 끝난 것으로 본다", s.allSettled)

        val waiting = practiceSummary(listOf(t("a", TaskStatus.DONE), t("b", TaskStatus.WAITING), t("c", TaskStatus.WAITING)))
        assertEquals("3개 중 1개 직접 확인", waiting.text)
        assertFalse(waiting.allSettled)
    }

    @Test
    fun `글자는 낱말 안에서 줄이 바뀌지 않게 하고 숫자와 단위는 붙여 둔다`() {
        val wj = '\u2060'
        assertEquals("앞${wj}유${wj}리 성${wj}에 제${wj}거", keepWords("앞유리 성에 제거"))
        assertEquals("띄어쓰기는 그대로 줄바꿈 자리로 남는다", 2, keepWords("앞유리 성에 제거").count { it == ' ' })
        assertEquals("숫자와 단위는 줄바꿈 없는 공백으로 잇는다", "지금 12,419\u00A0km", keepWords("지금 12,419 km").replace(wj.toString(), ""))
        val long = "가".repeat(MAX_KEEP_WORD + 1)
        assertEquals("아주 긴 낱말은 넘치지 않게 그대로 둔다", long, keepWords(long))
    }

    @Test
    fun `과제를 마친 뒤에는 출발해도 된다고 하지 않고 열려 있는 것부터 되돌리게 한다`() {
        fun t(id: String, title: String, st: TaskStatus, attention: Boolean = false) = PracticeTask(id, title, "", status = st, attention = attention)
        val open = afterPracticeNote(listOf(t("light", "전조등 켜기", TaskStatus.DONE), t("charge", "충전구 열기", TaskStatus.DONE, true), t("highbeam", "상향등 켜기", TaskStatus.DONE, true)))
        assertEquals("충전구·상향등을 되돌린 뒤 출발하세요", open.first)
        assertTrue(open.second)

        val closed = afterPracticeNote(listOf(t("light", "전조등 켜기", TaskStatus.DONE), t("charge", "충전구 열기", TaskStatus.DONE)))
        assertFalse(closed.second)
        assertTrue(closed.first.contains("문·안전벨트·계기판은 출발 전에 직접 확인하세요"))
        assertFalse(closed.first.contains("출발 준비 완료"))
    }
}
