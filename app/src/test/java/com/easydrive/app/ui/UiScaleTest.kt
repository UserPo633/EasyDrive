package com.easydrive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
