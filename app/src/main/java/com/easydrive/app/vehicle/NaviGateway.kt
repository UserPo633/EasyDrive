package com.easydrive.app.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 내비게이션에 설정된 목적지(길 안내 중일 때만). */
data class NaviDestination(val name: String?, val remainMeters: Int)

/** 목적지 정보를 주는 쪽. 테스트에서는 가짜로 바꾼다. */
fun interface NaviSource {
    suspend fun destination(): Read<NaviDestination>
}

/**
 * Pleos Connect NaviHelper SDK로 '지금 길 안내 중인 목적지까지 남은 거리'만 읽는다.
 * 경로를 요청하거나 바꾸지 않는다(읽기 전용). 내비게이션 서비스가 없거나 길 안내 중이 아니면 '지원 안 함'으로 돌려준다.
 */
class NaviGateway(context: Context) : NaviSource {
    private val helper: NaviHelper? = runCatching { NaviHelper(context.applicationContext) }
        .onFailure { Log.w(TAG, "NaviHelper unavailable", it) }.getOrNull()
    private var ready: CompletableDeferred<Boolean>? = null
    @Volatile private var routeWaiter: CompletableDeferred<Read<Boolean>>? = null
    @Volatile private var destWaiter: CompletableDeferred<Read<NaviDestination>>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(info: RouteStateInfo) {
            Log.i(TAG, "navi route state: routing=${info.isRouting}")
            routeWaiter?.complete(Read.Ok(info.isRouting))
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            Log.i(TAG, "navi destination: remainDistance=${info.remainDistance} remainTime=${info.remainTime}")
            destWaiter?.complete(Read.Ok(NaviDestination(info.name.takeIf { it.isNotBlank() }, info.remainDistance)))
        }
        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error: $code")
            routeWaiter?.complete(Read.Unavailable("내비게이션 응답 오류"))
            destWaiter?.complete(Read.Unavailable("내비게이션 응답 오류"))
        }
    }

    private suspend fun ensureReady(): Boolean {
        val h = helper ?: return false
        val d = ready ?: CompletableDeferred<Boolean>().also { def ->
            ready = def
            runCatching {
                h.initialize(
                    { Log.i(TAG, "NaviHelper initialized"); runCatching { h.addListener(listener) }; def.complete(true) },
                    { t -> Log.w(TAG, "NaviHelper initialize failed: ${t.message}"); def.complete(false) },
                )
            }.onFailure { Log.w(TAG, "NaviHelper initialize threw", it); def.complete(false) }
        }
        val ok = withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: false
        if (!ok && d.isCompleted) ready = null   // 다음 조회 때 다시 시도
        return ok
    }

    private var quietUntil = 0L
    private var misses = 0

    /** 응답이 없으면 한동안 다시 묻지 않는다(내비게이션이 준비되지 않은 차에서 3초씩 기다리는 일을 반복하지 않게). */
    override suspend fun destination(): Read<NaviDestination> {
        val now = System.currentTimeMillis()
        if (now < quietUntil) return Read.Unavailable("내비게이션 응답 없음")
        val r = query()
        if (r is Read.Unavailable) {
            misses++
            quietUntil = now + if (misses >= 3) 120_000L else 20_000L
        } else misses = 0
        return r
    }

    private suspend fun query(): Read<NaviDestination> {
        val h = helper ?: return Read.Unsupported
        if (!ensureReady()) return Read.Unsupported
        val rw = CompletableDeferred<Read<Boolean>>().also { routeWaiter = it }
        runCatching { h.getRouteStateInfo() }.onFailure { return Read.Unavailable("내비게이션 조회 실패") }
        val routing = withTimeoutOrNull(TIMEOUT_MS) { rw.await() } ?: return Read.Unavailable("내비게이션 응답 없음")
        if (routing !is Read.Ok) return Read.Unavailable("내비게이션 응답 오류")
        if (!routing.value) return Read.Unsupported   // 길 안내 중이 아님 → 보여 줄 것이 없음
        val dw = CompletableDeferred<Read<NaviDestination>>().also { destWaiter = it }
        runCatching { h.getDestinationInfo() }.onFailure { return Read.Unavailable("내비게이션 조회 실패") }
        return withTimeoutOrNull(TIMEOUT_MS) { dw.await() } ?: Read.Unavailable("내비게이션 응답 없음")
    }

    private companion object {
        const val TAG = "EasyDrive"
        const val TIMEOUT_MS = 3000L
    }
}
