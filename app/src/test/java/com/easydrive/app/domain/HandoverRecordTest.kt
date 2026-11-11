package com.easydrive.app.domain

import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.WarningEvent
import com.google.zxing.DecodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 인수·반납 기록 글자와 QR을 검증한다(QR은 다시 읽어서 같은 글자가 나오는지 확인). */
class HandoverRecordTest {
    private val t0 = 1_780_000_000_000L

    private fun checkin(odo: Float? = 12_410f, pct: Int? = 92, warnings: List<String> = emptyList(), readable: Boolean = true) =
        CheckinSnapshot(t0, odo, "배터리", pct, 340f, warnings, readable)

    private fun decode(matrix: Array<BooleanArray>): String {
        val n = matrix.size
        val bits = BitMatrix(n)
        for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) bits.set(x, y)
        return Decoder().decode(bits, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }

    @Test
    fun `기록에는 인수와 현재 값 그리고 변화량이 들어간다`() {
        val text = HandoverRecord.text(checkin(), 12_458f, 78, emptyList(), t0 + 3_600_000) { it.typeName }
        assertTrue(text.contains("/ 12,410km /"))
        assertTrue(text.contains("/ 12,458km(+48) /"))
        assertTrue(text.contains("배터리 92%"))
        assertTrue(text.contains("배터리 78%"))
        assertTrue(text.contains("경고등 없음"))
        assertTrue(text.contains("대여 중 경고등 없음"))
    }

    @Test
    fun `읽지 못한 값은 0이 아니라 확인 불가로 적고 변화량을 만들지 않는다`() {
        val text = HandoverRecord.text(checkin(odo = null, pct = null, readable = false), null, null, emptyList(), t0) { it.typeName }
        assertEquals("주행거리 2곳 모두", 2, Regex("/ 확인 불가 /").findAll(text).count())
        assertTrue(text.contains("배터리 확인 불가"))
        assertTrue(text.contains("경고등 확인 불가"))
        assertFalse(text.contains("(+"))
        assertFalse(text.contains("0km"))
    }

    @Test
    fun `대여 중 경고등은 주행 중 여부와 함께 최대 3건까지 적고 나머지는 건수로 줄인다`() {
        val events = (1..7).map { WarningEvent("TPMS", 3, t0 + it * 60_000, whileRestricted = it == 1) }
        val text = HandoverRecord.text(checkin(), 12_500f, 70, events, t0 + 3_600_000) { "타이어 공기압" }
        assertTrue(text.contains("대여 중 경고등 7건"))
        assertEquals(3, Regex("타이어 공기압").findAll(text).count())
        assertEquals(1, Regex("\\(주행 중\\)").findAll(text).count())
        assertTrue(text.contains("외 4건"))
    }

    @Test
    fun `기록에는 차대번호나 위치 같은 식별정보 항목이 없다`() {
        val text = HandoverRecord.text(checkin(), 12_458f, 78, emptyList(), t0) { it.typeName }
        listOf("VIN", "차대번호", "위치", "위도", "경도", "번호판").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test
    fun `QR을 다시 읽으면 한글 기록이 그대로 나온다`() {
        val events = (1..7).map { WarningEvent("TPMS", 3, t0 + it * 60_000, true) }
        val text = HandoverRecord.text(checkin(warnings = listOf("타이어 공기압", "구동 배터리 부족")), 12_458f, 78, events, t0 + 3_600_000) { "타이어 공기압" }
        val qr = HandoverRecord.qr(text)
        assertEquals(text, decode(qr))
        // 280dp 카드 안(여백 제외 252dp)에서 한 칸이 3.5dp 이상이 되도록, 가장 긴 기록도 69칸 이하여야 한다
        assertTrue("QR 칸 수 ${qr.size}", qr.size <= 69)

        // 보통의 기록(경고등 1건)은 57칸 이하 → 한 칸 4dp 이상
        val usual = HandoverRecord.text(checkin(), 12_458f, 78, events.take(1), t0 + 3_600_000) { "타이어 공기압" }
        assertTrue("보통 기록 QR 칸 수 ${HandoverRecord.qr(usual).size}", HandoverRecord.qr(usual).size <= 57)
    }
}
