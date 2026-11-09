package com.easydrive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 인수 시점에 차량에서 읽은 값. 읽지 못한 항목은 null로 남긴다(0으로 채우지 않음). */
data class CheckinSnapshot(
    val savedAtMillis: Long,
    val odometerKm: Float?,
    val energyLabel: String?,      // "배터리" 또는 "연료"
    val energyPercent: Int?,
    val rangeKm: Float?,
    val warnings: List<String>,    // 인수 시 켜져 있던 경고등 이름
    val warningsReadable: Boolean,
)

/** 대여 중 새로 켜진 경고등 기록 */
data class WarningEvent(
    val typeName: String,
    val colorId: Int,
    val atMillis: Long,
    val whileRestricted: Boolean,  // 주행 규제 중(주행 중) 켜졌는지
    var acknowledged: Boolean = false,
)

/**
 * 로컬 저장소(SharedPreferences + JSON). 서버·계정 없음.
 * VIN·위치 등 차량·개인 식별정보는 저장하지 않는다.
 */
class CheckinStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("easydrive", Context.MODE_PRIVATE)

    fun saveCheckin(s: CheckinSnapshot) {
        val o = JSONObject()
            .put("savedAt", s.savedAtMillis)
            .put("odometerKm", s.odometerKm?.toDouble() ?: JSONObject.NULL)
            .put("energyLabel", s.energyLabel ?: JSONObject.NULL)
            .put("energyPercent", s.energyPercent ?: JSONObject.NULL)
            .put("rangeKm", s.rangeKm?.toDouble() ?: JSONObject.NULL)
            .put("warnings", JSONArray(s.warnings))
            .put("warningsReadable", s.warningsReadable)
        prefs.edit().putString(KEY_CHECKIN, o.toString()).apply()
    }

    fun loadCheckin(): CheckinSnapshot? {
        val raw = prefs.getString(KEY_CHECKIN, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CheckinSnapshot(
                savedAtMillis = o.getLong("savedAt"),
                odometerKm = if (o.isNull("odometerKm")) null else o.getDouble("odometerKm").toFloat(),
                energyLabel = if (o.isNull("energyLabel")) null else o.getString("energyLabel"),
                energyPercent = if (o.isNull("energyPercent")) null else o.getInt("energyPercent"),
                rangeKm = if (o.isNull("rangeKm")) null else o.getDouble("rangeKm").toFloat(),
                warnings = o.getJSONArray("warnings").let { a -> List(a.length()) { a.getString(it) } },
                warningsReadable = o.optBoolean("warningsReadable", false),
            )
        }.getOrNull()
    }

    fun saveEvents(events: List<WarningEvent>) {
        val a = JSONArray()
        events.forEach {
            a.put(
                JSONObject().put("type", it.typeName).put("color", it.colorId).put("at", it.atMillis)
                    .put("restricted", it.whileRestricted).put("ack", it.acknowledged)
            )
        }
        prefs.edit().putString(KEY_EVENTS, a.toString()).apply()
    }

    fun loadEvents(): List<WarningEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                WarningEvent(o.getString("type"), o.getInt("color"), o.getLong("at"), o.getBoolean("restricted"), o.getBoolean("ack"))
            }
        }.getOrDefault(emptyList())
    }

    /** 반납 완료 후 다음 대여를 위해 기록을 지운다. */
    fun clearRental() {
        prefs.edit().remove(KEY_CHECKIN).remove(KEY_EVENTS).apply()
    }

    private companion object {
        const val KEY_CHECKIN = "checkin"
        const val KEY_EVENTS = "events"
    }
}
