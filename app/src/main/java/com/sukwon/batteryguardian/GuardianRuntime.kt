package com.sukwon.batteryguardian

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId

/** 연결 상태는 프로세스에만 보관해 저장된 ON 설정과 실제 서비스 연결을 구분한다. */
object GuardianRuntime {
    val connected = MutableStateFlow(false)
    val mediaConnected = MutableStateFlow(false)
    var service: ProtectionService? = null
}

/** 미디어 세션 접근용 연결점. 알림 본문을 조회·수집하는 콜백은 구현하지 않는다. */
class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardianRuntime.mediaConnected.value = true
        GuardianRuntime.service?.requestEvaluation()
    }
    override fun onListenerDisconnected() {
        GuardianRuntime.mediaConnected.value = false
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        GuardianRuntime.mediaConnected.value = false
        super.onDestroy()
    }
}

/** 권한 상태를 운영체제에 조회한다. 표시 허용 권한과 알림 접근 권한은 서로 다르다. */
fun hasMediaAccess(context: Context): Boolean =
    requireNotNull(context.getSystemService(android.app.NotificationManager::class.java))
        .isNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java))

/** 초 단위 폴링 없이 다음 시작/종료 경계만 예약한다. OS가 전달을 늦출 수 있다. */
object GuardianScheduler {
    fun schedule(context: Context, s: GuardianSettings) {
        val manager = requireNotNull(context.getSystemService(AlarmManager::class.java))
        val pending = PendingIntent.getBroadcast(context, 1,
            Intent(context, ScheduleReceiver::class.java).setAction("com.sukwon.batteryguardian.BOUNDARY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(pending)
        if (!s.enabled || s.startMinute == s.endMinute) return
        val now = LocalDateTime.now()
        val next = listOf(s.startMinute, s.endMinute).map { minute ->
            val today = now.toLocalDate().atTime(minute / 60, minute % 60)
            if (today.isAfter(now)) today else today.plusDays(1)
        }.minOrNull() ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending)
    }
}

/** 재부팅·시간 변경·시간 경계 때 예약을 복구한다. 접근성 서비스를 강제로 켜지는 않는다. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeout(7000) {
                    val s = SettingsRepository(context).settings.first()
                    GuardianScheduler.schedule(context, s)
                    GuardianRuntime.service?.requestEvaluation()
                }
            } finally { pending.finish() }
        }
    }
}
