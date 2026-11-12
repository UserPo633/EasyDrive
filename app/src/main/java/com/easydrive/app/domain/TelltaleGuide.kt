package com.easydrive.app.domain

import ai.pleos.playground.vehicle.constant.telltale.TelltaleColor
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import com.easydrive.app.vehicle.BasicWarning

/**
 * 경고등 한 개를 '무슨 뜻 / 먼저 할 일'로 옮긴 안내.
 * 운행해도 되는지는 앱이 판단하지 않는다. 계기판 메시지·설명서·운영사 안내가 우선한다.
 */
data class TelltaleExplain(
    val name: String,
    val meaning: String,
    val action: String,
    val severity: Severity,
)

enum class Severity { STOP, CHECK, INFO }

/**
 * 경고등 통역 사전.
 * 차량의 실제 경고 표시와 설명서를 대신하지 않으며, 운영사 안내와 차량 설명서가 우선한다는 전제를
 * 경고등 화면 제목 바로 아래에 항상 함께 보여 준다. 문구는 일반적인 안전 수칙 수준으로 보수적으로 작성했다.
 */
object TelltaleGuide {

    private val table: Map<TelltaleType, TelltaleExplain> = mapOf(
        // 주차 중에는 정상적으로 켜져 있는 표시라 '확인 필요'로 세지 않는다(INFO).
        TelltaleType.PARKING_BRAKE to e("주차 브레이크", "주차 브레이크가 걸려 있다는 표시입니다.", "출발할 때 주차 브레이크가 풀리는지 확인하세요. 풀어도 계속 켜져 있으면 운행하지 말고 연락하세요.", Severity.INFO),
        TelltaleType.ELECTRONIC_PARKING_BRAKE to e("전자식 주차 브레이크", "전자식 주차 브레이크 시스템에 이상이 있을 수 있다는 표시입니다.", "평지에 안전하게 정차한 상태에서 연락해 점검 여부를 확인하세요.", Severity.CHECK),
        TelltaleType.SEAT_BELT to e("안전벨트", "안전벨트를 매지 않은 좌석이 있습니다.", "출발 전에 모든 탑승자가 안전벨트를 맸는지 확인하세요.", Severity.INFO),
        TelltaleType.DOOR_OPEN to e("도어 열림", "완전히 닫히지 않은 도어가 있습니다.", "정차한 상태에서 모든 도어를 다시 닫아 주세요.", Severity.CHECK),
        TelltaleType.TRUNK_OPEN to e("트렁크 열림", "트렁크(또는 테일게이트)가 완전히 닫히지 않았습니다.", "정차한 상태에서 트렁크를 다시 닫아 주세요.", Severity.CHECK),
        TelltaleType.TPMS to e("타이어 공기압", "타이어 공기압이 낮거나 공기압 감지 장치 점검이 필요하다는 표시입니다.", "안전한 곳에 정차해 타이어 상태를 눈으로 확인하고, 다시 출발하기 전에 운영사 고객센터나 정비소에 연락하세요.", Severity.CHECK),
        TelltaleType.ABS to e("ABS", "잠김 방지 브레이크(ABS)에 이상이 있을 수 있습니다. 일반 제동은 되지만 급제동 보조가 동작하지 않을 수 있습니다.", "안전한 곳에 정차한 뒤 운영사 고객센터나 정비소에 점검을 요청하세요.", Severity.CHECK),
        TelltaleType.ESC to e("차체 자세 제어(ESC)", "미끄러운 길에서 ESC가 작동 중이면 깜박이고, 계속 켜져 있으면 점검이 필요할 수 있습니다.", "깜박인다면 속도를 줄이세요. 계속 켜져 있으면 점검을 요청하세요.", Severity.CHECK),
        TelltaleType.ESC_OFF to e("ESC 꺼짐", "차체 자세 제어(ESC) 기능이 꺼져 있습니다.", "특별한 이유가 없다면 ESC를 다시 켜는 방법을 운영사에 문의하세요.", Severity.CHECK),
        TelltaleType.MDPS to e("전동식 조향(MDPS)", "핸들 조향 보조 장치에 이상이 있을 수 있습니다. 핸들이 무거워질 수 있습니다.", "안전한 곳에 정차한 뒤 운행하지 말고 연락하세요.", Severity.STOP),
        TelltaleType.LOW_VOLT_BATTERY to e("충전 경고(배터리)", "차량 전기 계통 충전에 문제가 있을 수 있다는 표시입니다.", "불필요한 전기 장치를 끄고, 안전한 곳에 정차한 뒤 연락하세요.", Severity.STOP),
        TelltaleType.AIRBAG to e("에어백", "에어백 시스템에 이상이 있을 수 있습니다.", "사고 시 에어백이 작동하지 않을 수 있으니 바로 연락해 점검 여부를 확인하세요.", Severity.STOP),
        TelltaleType.MASTER_WARNING to e("종합 경고", "계기판에 확인할 경고 메시지가 있다는 표시입니다.", "정차한 상태에서 계기판 메시지를 확인하세요.", Severity.CHECK),
        TelltaleType.ENGINE_OIL_PRESSURE to e("엔진 오일 압력", "엔진 오일 압력이 낮다는 표시입니다. 계속 주행하면 엔진이 손상될 수 있습니다.", "즉시 안전한 곳에 정차하고 시동을 끈 뒤 연락하세요.", Severity.STOP),
        TelltaleType.ENGINE_TEMPERATURE to e("엔진 과열", "냉각수 온도가 높다는 표시입니다.", "안전한 곳에 정차하고 연락하세요. 뜨거운 상태에서 냉각수 캡을 열지 마세요.", Severity.STOP),
        TelltaleType.ENGINE to e("엔진 점검", "엔진이나 배기 관련 장치 점검이 필요하다는 표시입니다.", "급가속을 피하고 가까운 시일 안에 점검을 요청하세요. 깜박이면 바로 정차 후 연락하세요.", Severity.CHECK),
        TelltaleType.EV_SYSTEM to e("전기차 시스템", "전기차 구동 시스템 점검이 필요하다는 표시입니다.", "안전한 곳에 정차한 뒤 연락하세요.", Severity.STOP),
        TelltaleType.HEV_SYSTEM to e("하이브리드 시스템", "하이브리드 구동 시스템 점검이 필요하다는 표시입니다.", "안전한 곳에 정차한 뒤 연락하세요.", Severity.STOP),
        TelltaleType.LOW_EV_BATTERY to e("구동 배터리 부족", "구동 배터리 잔량이 낮습니다.", "가까운 충전소를 찾아 충전하세요.", Severity.CHECK),
        TelltaleType.LOW_FUEL to e("연료 부족", "연료 잔량이 낮습니다.", "가까운 주유소에서 주유하세요. 렌터카라면 반납 연료 기준도 함께 확인하세요.", Severity.CHECK),
        TelltaleType.EV_CHARGING_CONNECTOR to e("충전 커넥터 연결", "충전 커넥터가 연결되어 있습니다.", "출발 전 충전 커넥터를 분리하세요.", Severity.CHECK),
        TelltaleType.ICY_ROAD to e("결빙 주의", "바깥 기온이 낮아 노면이 얼어 있을 수 있습니다.", "속도를 줄이고 차간 거리를 넉넉히 두세요.", Severity.INFO),
        TelltaleType.POWER_DOWN to e("출력 제한", "차량 보호를 위해 출력이 제한된 상태입니다.", "무리한 가속을 피하고 정차 후 연락하세요.", Severity.CHECK),
        TelltaleType.IMMOBILIZER to e("이모빌라이저", "스마트키 인식 또는 도난 방지 장치 관련 표시입니다.", "스마트키가 차 안에 있는지 확인하세요. 계속되면 연락하세요.", Severity.CHECK),
    )

    private fun e(name: String, meaning: String, action: String, s: Severity) = TelltaleExplain(name, meaning, action, s)

    /** 공개 권한으로 읽는 경고 상태 7종의 풀이 */
    private val basicTable: Map<BasicWarning, TelltaleExplain> = mapOf(
        BasicWarning.SEAT_BELT to e("안전벨트 미착용", "안전벨트를 매지 않은 좌석이 있다는 경고입니다.", "출발 전에 모든 탑승자가 안전벨트를 맸는지 확인하세요.", Severity.INFO),
        BasicWarning.WASHER_LOW to e("워셔액 부족", "앞유리를 닦는 워셔액이 부족하다는 경고입니다.", "인수 직후라면 직원에게 보충을 요청하세요. 주행 중이라면 다음 정차 때 보충하거나 운영사에 문의하세요.", Severity.CHECK),
        BasicWarning.POWER_DOWN to e("출력 제한", "차량 보호를 위해 출력이 제한된 상태라는 경고입니다.", "안전한 곳에 정차하고, 다시 출발하기 전에 운영사 고객센터나 정비소에 연락하세요.", Severity.CHECK),
        BasicWarning.ENGINE_OIL to e("엔진 오일 이상", "엔진 오일 양이 정상 범위를 벗어났다는 경고입니다.", "안전한 곳에 정차해 시동을 끄고 운영사 고객센터나 정비소에 연락하세요.", Severity.STOP),
        BasicWarning.BRAKE_FLUID to e("브레이크액 이상", "브레이크액 양이 정상 범위를 벗어났다는 경고입니다. 제동 성능에 영향을 줄 수 있습니다.", "운행하지 말고 운영사 고객센터나 정비소에 연락하세요.", Severity.STOP),
        BasicWarning.TRANSMISSION_OIL to e("변속기 오일 이상", "변속기 오일 양이 정상 범위를 벗어났다는 경고입니다.", "안전한 곳에 정차한 뒤 운영사 고객센터나 정비소에 연락하세요.", Severity.CHECK),
        BasicWarning.AIRBAG_DEPLOYED to e("에어백 전개 이력", "에어백이 전개된 상태로 기록되어 있다는 표시입니다.", "운행하지 말고 운영사 고객센터에 연락하세요.", Severity.STOP),
    )

    const val BASIC_PREFIX = "BASIC_"

    fun basic(w: BasicWarning): TelltaleExplain = basicTable.getValue(w)

    /** 화면에 보여 줄 짧은 항목 이름(예: "읽는 경고 5종: 안전벨트·워셔액·…") */
    fun basicShortName(w: BasicWarning): String = when (w) {
        BasicWarning.SEAT_BELT -> "안전벨트"
        BasicWarning.WASHER_LOW -> "워셔액"
        BasicWarning.POWER_DOWN -> "출력 제한"
        BasicWarning.ENGINE_OIL -> "엔진 오일"
        BasicWarning.BRAKE_FLUID -> "브레이크액"
        BasicWarning.TRANSMISSION_OIL -> "변속기 오일"
        BasicWarning.AIRBAG_DEPLOYED -> "에어백 전개 이력"
    }

    /** 저장된 기록(이름 문자열)에서 풀이를 다시 찾는다. 공개 경고와 경고등 이름을 모두 처리한다. */
    fun explainByName(typeName: String, colorId: Int): TelltaleExplain {
        if (typeName.startsWith(BASIC_PREFIX)) {
            val w = runCatching { BasicWarning.valueOf(typeName.removePrefix(BASIC_PREFIX)) }.getOrNull()
            if (w != null) return basic(w)
        }
        val type = runCatching { TelltaleType.valueOf(typeName) }.getOrNull()
        return explain(type, colorId)
    }

    /**
     * 경고 대상 여부: 사전에 있는 경고등이거나, 차량이 빨강·노랑으로 표시한 경고등.
     * 방향지시등·전조등 같은 단순 표시등은 리포트에서 제외한다.
     */
    fun isWarning(type: TelltaleType?, colorId: Int): Boolean {
        if (type != null && table.containsKey(type)) return true
        val c = colorOf(colorId)
        return c == TelltaleColor.RED || c == TelltaleColor.YELLOW
    }

    fun explain(type: TelltaleType?, colorId: Int): TelltaleExplain {
        table[type]?.let { return it }
        val c = colorOf(colorId)
        val name = type?.name ?: "알 수 없는 경고등"
        return if (c == TelltaleColor.RED) {
            TelltaleExplain(name, "차량이 빨간색으로 표시한 경고등입니다.", "안전한 곳에 정차한 뒤 계기판 메시지를 확인하고 연락하세요.", Severity.STOP)
        } else {
            TelltaleExplain(name, "차량이 점검을 권하는 경고등입니다.", "정차한 상태에서 계기판 메시지를 확인하고 필요하면 연락하세요.", Severity.CHECK)
        }
    }

    /**
     * TelltaleData.color(int) → TelltaleColor.
     * SDK 2.3.2의 TelltaleColor.findById는 internal이라 직접 호출할 수 없다.
     * 같은 SDK에서 id 값은 선언 순서와 같다(WHITE 0, GREEN 1, BLUE 2, YELLOW 3, RED 4, GRAY 5).
     */
    fun colorOf(id: Int): TelltaleColor? = TelltaleColor.entries.getOrNull(id)

    const val DISCLAIMER = "이 안내는 이해를 돕기 위한 요약입니다. 차량 계기판 메시지와 설명서, 운영사 안내가 우선합니다."
}
