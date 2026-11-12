package com.easydrive.app.vehicle

/**
 * 차량 값 조회 결과.
 * EasyDrive는 읽지 못한 값을 0이나 '정상'으로 대신 채우지 않는다. 반드시 세 가지 중 하나로 구분한다.
 */
sealed interface Read<out T> {
    /** 차량에서 실제로 받은 값 */
    data class Ok<T>(val value: T, val readAtMillis: Long = System.currentTimeMillis()) : Read<T>

    /** 이 차량(또는 이 영역)에서 지원하지 않는 기능 */
    data object Unsupported : Read<Nothing>

    /** 권한 오류, 서비스 미연결, 응답 없음 등으로 확인할 수 없음 */
    data class Unavailable(val reason: String) : Read<Nothing>
}

fun <T> Read<T>.valueOrNull(): T? = (this as? Read.Ok<T>)?.value

/** 읽은 값만 바꾸고, '지원 안 함'·'확인 불가'는 그대로 전달한다. */
inline fun <T, R> Read<T>.map(f: (T) -> R): Read<R> = when (this) {
    is Read.Ok -> Read.Ok(f(value), readAtMillis)
    Read.Unsupported -> Read.Unsupported
    is Read.Unavailable -> this
}
