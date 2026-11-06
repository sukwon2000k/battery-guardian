package com.sukwon.batteryguardian

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 접근성 연결 중 배터리 이벤트를 받아 사용자가 정한 조건으로만 홈 이동·잠금을 실행한다. */
class ProtectionService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private lateinit var repository: SettingsRepository
    private var settings = GuardianSettings()
    private var battery = -1
    private var plugged = false
    private var foregroundPackage = ""
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent)
            if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                GuardianScheduler.schedule(this@ProtectionService, settings)
            }
            requestEvaluation()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (receiverRegistered) return
        repository = SettingsRepository(this)
        GuardianRuntime.service = this
        GuardianRuntime.connected.value = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 시스템 배터리 이벤트만 수신한다. 일반 외부 앱의 임의 이벤트는 받지 않는다.
        val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter)
        receiverRegistered = true
        sticky?.let(::updateBattery)
        scope.launch {
            repository.settings.collectLatest {
                settings = it
                GuardianScheduler.schedule(this@ProtectionService, it)
                requestEvaluation()
            }
        }
    }

    /** 배터리 백분율은 기기별 원래 눈금을 100으로 환산한다. 케이블 연결 시 보호를 건너뛴다. */
    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** 알람 앱의 창과 통화 상태를 보수적으로 건너뛴다. 모든 타사 알람 판별을 보장하지 않는다. */
    private fun interruptionReason(): String? {
        val mode = requireNotNull(getSystemService(AudioManager::class.java)).mode
        if (mode != AudioManager.MODE_NORMAL) return "통화·통신 오디오 사용 중이라 보호를 미뤘어요"
        if (foregroundPackage in clockPackages) return "시계·알람 화면 사용 중이라 보호를 미뤘어요"
        return null
    }

    /** 여러 이벤트가 겹쳐도 보호 동작은 직렬 실행하며, 같은 밤에 한 번만 요청한다. */
    fun requestEvaluation() {
        if (!::repository.isInitialized) return
        scope.launch {
            mutex.withLock {
                val decision = ProtectionEvaluator.evaluate(settings, LocalDateTime.now(), battery, plugged)
                if (!decision.shouldProtect || !hasMediaAccess(this@ProtectionService) ||
                    !GuardianRuntime.mediaConnected.value || interruptionReason() != null) return@withLock
                // 실행 전에 저장해 서비스가 도중에 재시작해도 같은 밤 반복 잠금을 막는다.
                repository.record("보호 동작을 요청하고 있어요", decision.window)
                settings = settings.copy(lastWindow = decision.window)
                val result = performProtection()
                repository.record(result)
            }
        }
    }

    /** UI에서 명시적으로 시작한 테스트만 수행한다. 자동 보호 이력은 소모하지 않는다. */
    suspend fun runManualTest(): String = mutex.withLock {
        val reason = interruptionReason()
        val result = when {
            reason != null -> reason
            !hasMediaAccess(this) || !GuardianRuntime.mediaConnected.value -> "미디어 제어 권한 연결을 확인해주세요"
            else -> performProtection()
        }
        repository.record("[수동 테스트] $result")
        result
    }

    /** 미디어에는 일시정지 요청만 보낸다. 재생/정지 토글은 멈춘 알람을 켤 수 있어 사용하지 않는다. */
    private suspend fun performProtection(): String {
        var media = "미디어 제어 대상 없음"
        try {
            val controllers = requireNotNull(getSystemService(MediaSessionManager::class.java))
                .getActiveSessions(ComponentName(this, MediaAccessService::class.java))
            val targets = controllers.filter {
                it.packageName in mediaPackages && it.playbackState?.state in listOf(
                    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
            }
            var requested = 0
            targets.forEach { controller ->
                val actions = controller.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_PAUSE != 0L) {
                    runCatching { controller.transportControls.pause() }.onSuccess { requested++ }
                }
            }
            media = if (requested > 0) "미디어 일시정지 $requested 건 요청" else "일시정지 가능한 지원 미디어 없음"
        } catch (_: SecurityException) {
            media = "미디어 접근 권한 확인 필요"
        } catch (_: RuntimeException) {
            media = "미디어 요청 실패"
        }
        val locked = requireNotNull(getSystemService(KeyguardManager::class.java)).isKeyguardLocked
        val home = if (locked) "이미 잠겨 홈 이동 생략"
            else if (performGlobalAction(GLOBAL_ACTION_HOME)) "홈 이동 성공" else "홈 이동 실패"
        // 홈 화면 전환을 기다리는 짧은 간격이다. 상시 타이머나 폴링은 아니다.
        delay(350)
        val lock = if (locked) "이미 잠김"
            else if (performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) "잠금 동작 성공" else "잠금 동작 실패"
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        return "$time · $media / $home / $lock\n재생 중단 여부는 해당 앱에서 확인해주세요."
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 화면 내용은 조회하지 않는다. 알람 화면 회피에 필요한 패키지 이름만 일시 사용한다.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = event.packageName?.toString().orEmpty()
            requestEvaluation()
        }
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        GuardianRuntime.connected.value = false
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver)
        GuardianRuntime.service = null
        GuardianRuntime.connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // 알람·통신 앱을 건드리지 않도록 검증 대상 미디어 앱만 제한적으로 요청한다.
        val mediaPackages = setOf("com.google.android.youtube", "com.google.android.apps.youtube.music",
            "com.spotify.music", "com.sec.android.app.music", "org.videolan.vlc")
        val clockPackages = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
    }
}
