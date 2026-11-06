package com.sukwon.batteryguardian

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** 경계·자정·충전·실행 이력을 실제 Kotlin 판단기에 전달하는 단위 테스트다. */
class ProtectionEvaluatorTest {
    private val on = GuardianSettings(enabled = true)
    private fun at(value: String) = LocalDateTime.parse(value)

    @Test fun thresholdIncludesEquality() {
        assertTrue(ProtectionEvaluator.evaluate(on, at("2026-11-06T02:00"), 20, false).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on, at("2026-11-06T02:00"), 21, false).shouldProtect)
    }
    @Test fun timeIncludesStartExcludesEnd() {
        assertTrue(ProtectionEvaluator.evaluate(on, at("2026-11-06T00:00"), 10, false).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on, at("2026-11-06T07:00"), 10, false).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on, at("2026-11-06T23:59"), 10, false).shouldProtect)
    }
    @Test fun midnightSharesSameNight() {
        val s = on.copy(startMinute = 1380)
        val first = ProtectionEvaluator.evaluate(s, at("2026-11-06T23:00"), 10, false)
        val after = ProtectionEvaluator.evaluate(s, at("2026-11-07T02:00"), 10, false)
        assertEquals(first.window, after.window)
        assertFalse(ProtectionEvaluator.evaluate(s.copy(lastWindow = first.window), at("2026-11-07T02:00"), 10, false).shouldProtect)
        assertTrue(ProtectionEvaluator.evaluate(s.copy(lastWindow = first.window), at("2026-11-07T23:00"), 10, false).shouldProtect)
    }
    @Test fun offChargingUnknownBatteryAndEqualTimesDoNotExecute() {
        val now = at("2026-11-06T02:00")
        assertFalse(ProtectionEvaluator.evaluate(on.copy(enabled = false), now, 10, false).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on, now, 10, true).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on, now, -1, false).shouldProtect)
        assertFalse(ProtectionEvaluator.evaluate(on.copy(startMinute = 420), now, 10, false).shouldProtect)
    }
    @Test fun newDayAllowsNewProtectionAndOldRecordSurvivesToggle() {
        val s = on.copy(lastWindow = "2026-11-06")
        assertFalse(ProtectionEvaluator.evaluate(s, at("2026-11-06T02:00"), 10, false).shouldProtect)
        assertTrue(ProtectionEvaluator.evaluate(s, at("2026-11-07T02:00"), 10, false).shouldProtect)
    }
}
