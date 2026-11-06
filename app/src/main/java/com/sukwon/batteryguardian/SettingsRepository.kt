package com.sukwon.batteryguardian

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Context 확장으로 저장소를 한 번만 생성한다. UI와 서비스가 같은 파일을 사용한다.
private val Context.guardianDataStore by preferencesDataStore("guardian")

/** 설정을 기기 안에 저장하며 Flow로 변경을 전달한다. 알림 본문은 저장하지 않는다. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.guardianDataStore
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val start = intPreferencesKey("start")
        val end = intPreferencesKey("end")
        val threshold = intPreferencesKey("threshold")
        val window = stringPreferencesKey("window")
        val result = stringPreferencesKey("result")
    }
    val settings = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { p ->
        GuardianSettings(
            p[Keys.enabled] ?: false, (p[Keys.start] ?: 0).coerceIn(0, 1439),
            (p[Keys.end] ?: 420).coerceIn(0, 1439), (p[Keys.threshold] ?: 20).coerceIn(1, 100),
            p[Keys.window] ?: "", p[Keys.result] ?: "아직 실행 기록이 없어요"
        )
    }
    suspend fun setEnabled(value: Boolean) { store.edit { it[Keys.enabled] = value } }
    suspend fun setThreshold(value: Int) { store.edit { it[Keys.threshold] = value.coerceIn(1, 100) } }
    suspend fun setTime(start: Int, end: Int) {
        require(start in 0..1439 && end in 0..1439 && start != end)
        store.edit { it[Keys.start] = start; it[Keys.end] = end }
    }
    /** window 비어 있으면 수동 테스트 기록만 남기고 자동 실행 이력은 바꾸지 않는다. */
    suspend fun record(result: String, window: String = "") {
        store.edit { p ->
            p[Keys.result] = result
            if (window.isNotBlank()) p[Keys.window] = window
        }
    }
}
