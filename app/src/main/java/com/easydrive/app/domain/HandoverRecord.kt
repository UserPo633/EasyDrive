package com.easydrive.app.domain

import com.easydrive.app.data.CheckinSnapshot
import com.easydrive.app.data.WarningEvent
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 인수·반납 기록을 사람이 읽을 수 있는 글자로 만들고 QR로 바꾼다.
 * 서버로 보내지 않는다. 운전자가 휴대폰 카메라로 찍어 자기 기록으로 가져가는 용도다.
 * VIN·위치 등 식별정보는 넣지 않고, 읽지 못한 값은 '확인 불가'로 적는다.
 */
object HandoverRecord {
    const val MAX_EVENTS = 3
    private const val NA = "확인 불가"

    /**
     * QR 칸이 너무 촘촘해지지 않도록 짧게 쓴다(보통 200바이트 안팎).
     * 예) EasyDrive 기록
     *     인수 11-11 21:29 / 12,410km / 배터리 92% / 앱이 읽은 경고 없음
     *     지금 11-11 22:29 / 12,458km(+48) / 배터리 78%
     *     대여 중 앱이 기록한 경고 1건: 22:19 출력 제한(주행 중)
     */
    fun text(
        checkin: CheckinSnapshot,
        nowOdometerKm: Float?,
        nowEnergyPercent: Int?,
        events: List<WarningEvent>,
        nowMillis: Long,
        practiced: List<String> = emptyList(),
        eventName: (WarningEvent) -> String,
    ): String {
        val day = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        val energy = checkin.energyLabel ?: "배터리·연료"
        fun km(v: Float?) = v?.let { "%,dkm".format(it.toInt()) } ?: NA
        fun pct(v: Int?) = v?.let { "$it%" } ?: NA

        val sb = StringBuilder("EasyDrive 기록\n")
        sb.append("인수 ").append(day.format(Date(checkin.savedAtMillis)))
            .append(" / ").append(km(checkin.odometerKm))
            .append(" / ").append(energy).append(' ').append(pct(checkin.energyPercent))
            .append(" / 앱이 읽은 경고 ").append(
                when {
                    !checkin.warningsReadable -> NA
                    checkin.warnings.isEmpty() -> "없음"
                    else -> checkin.warnings.joinToString(", ")
                }
            ).append('\n')
        sb.append("지금 ").append(day.format(Date(nowMillis))).append(" / ").append(km(nowOdometerKm))
        if (checkin.odometerKm != null && nowOdometerKm != null && nowOdometerKm >= checkin.odometerKm) {
            sb.append("(+%,d)".format((nowOdometerKm - checkin.odometerKm).toInt()))
        }
        sb.append(" / ").append(energy).append(' ').append(pct(nowEnergyPercent)).append('\n')
        sb.append("대여 중 앱이 기록한 경고 ")
        if (events.isEmpty()) sb.append("없음")
        else {
            sb.append(events.size).append("건: ")
            // 최근 것부터 남긴다
            sb.append(events.takeLast(MAX_EVENTS).joinToString(", ") { e ->
                hm.format(Date(e.atMillis)) + " " + eventName(e) + if (e.whileRestricted) "(주행 중)" else ""
            })
            if (events.size > MAX_EVENTS) sb.append(" 외 ").append(events.size - MAX_EVENTS).append("건")
        }
        // 운전자가 직접 조작했고 차량이 확인해 준 것만 적는다('이미 설정됨'은 넣지 않는다).
        if (practiced.isNotEmpty()) sb.append('\n').append("차량이 확인한 조작 ").append(practiced.size).append("개: ").append(practiced.joinToString(", "))
        return sb.toString()
    }

    /** QR 모듈 행렬(true = 검은 칸). 여백 없이 반환하므로 그릴 때 흰 여백을 둔다. */
    fun qr(text: String): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }
}
