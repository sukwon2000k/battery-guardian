package com.sukwon.batteryguardian

import java.time.LocalDateTime

/** 사용자가 정한 값과 마지막 실행 결과. 시간은 자정부터의 분 단위다. */
data class GuardianSettings(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 420,
    val threshold: Int = 20,
    val lastWindow: String = "",
    val lastResult: String = "아직 실행 기록이 없어요",
)

/** 순수 조건 판단 결과. 실제 휴대폰 동작 없이 테스트할 수 있다. */
data class Decision(val shouldProtect: Boolean, val message: String, val window: String = "")

/** 시간·잔량·충전·중복 실행을 판단한다. 시작은 포함하고 종료 시각은 제외한다. */
object ProtectionEvaluator {
    fun evaluate(s: GuardianSettings, now: LocalDateTime, battery: Int, plugged: Boolean): Decision {
        if (!s.enabled) return Decision(false, "자동 보호가 꺼져 있어요")
        if (s.startMinute == s.endMinute) return Decision(false, "시작과 종료 시간을 다르게 정해주세요")
        val minute = now.hour * 60 + now.minute
        val overnight = s.startMinute > s.endMinute
        val inside = if (overnight) minute >= s.startMinute || minute < s.endMinute
            else minute >= s.startMinute && minute < s.endMinute
        if (!inside) return Decision(false, "보호 시간까지 기다리고 있어요")
        // 자정을 지난 구간도 전날 시작한 하나의 밤으로 묶어 반복 잠금을 막는다.
        val date = if (overnight && minute < s.endMinute) now.toLocalDate().minusDays(1)
            else now.toLocalDate()
        val window = date.toString()
        if (plugged) return Decision(false, "충전 연결 중이라 쉬고 있어요", window)
        if (battery !in 0..100) return Decision(false, "배터리 상태를 확인하고 있어요", window)
        if (s.lastWindow == window) return Decision(false, "오늘 보호를 한 번 실행했어요", window)
        if (battery > s.threshold) return Decision(false, "배터리 ${s.threshold}% 이하에서 보호해요", window)
        return Decision(true, "보호 조건을 만족했어요", window)
    }
}
