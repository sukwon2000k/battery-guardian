package com.sukwon.batteryguardian

import android.app.TimePickerDialog
import android.content.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/** 단일 화면의 시작점. 실제 자동 감시는 접근성 서비스에 맡긴다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(this)
        setContent { GuardianHome(this, repository) }
    }
}

/** 자정부터의 분 단위 시각을 읽기 쉬운 24시간제로 표시한다. */
private fun timeLabel(minute: Int) = String.format(Locale.KOREA, "%02d:%02d", minute / 60, minute % 60)

/** 설정·권한·시험 도구를 제공한다. 조작한 설정만 저장해 서비스 상태를 덮어쓰지 않는다. */
@Composable
private fun GuardianHome(activity: ComponentActivity, repository: SettingsRepository) {
    val saved by produceState<GuardianSettings?>(initialValue = null, repository) {
        repository.settings.collect { value = it }
    }
    val s = saved ?: return
    val scope = rememberCoroutineScope()
    val connected by GuardianRuntime.connected.collectAsState()
    val mediaConnected by GuardianRuntime.mediaConnected.collectAsState()
    var mediaGranted by remember { mutableStateOf(hasMediaAccess(activity)) }
    var battery by remember { mutableIntStateOf(-1) }
    var plugged by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var showTime by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var permissionDetail by remember { mutableStateOf("") }
    var showTests by remember { mutableStateOf(false) }
    var confirmTest by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var transientMessage by remember { mutableStateOf("") }

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                }
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaGranted = hasMediaAccess(activity)
                now = LocalDateTime.now()
            }
            // 다른 화면으로 이동하면 수동 테스트의 예약을 취소한다.
            if (event == Lifecycle.Event.ON_STOP) countdown = 0
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.unregisterReceiver(receiver); activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            if (countdown == 1) {
                countdown = 0
                // 카운트가 0으로 바뀌어도 보호 동작 코루틴이 취소되지 않도록 별도로 실행한다.
                scope.launch {
                    transientMessage = GuardianRuntime.service?.runManualTest() ?: "접근성 서비스가 연결되지 않았어요"
                }
            } else countdown--
        }
    }
    val ready = connected && mediaGranted && mediaConnected
    val decision = ProtectionEvaluator.evaluate(s, now, battery, plugged)
    val status = if (s.enabled && !ready) "권한 연결 필요 · 자동 보호 준비 안 됨" else decision.message
    val background = if (s.enabled) Color(0xFF232B35) else Color(0xFFF7F3EA)
    val foreground = if (s.enabled) Color(0xFFF5F0E5) else Color(0xFF343A34)
    val accent = if (s.enabled) Color(0xFFA8BFA6) else Color(0xFF70866F)
    val panel = if (s.enabled) Color(0xFF303A46) else Color(0xFFEDE8DD)
    val colors = if (s.enabled) darkColorScheme(primary = accent, background = background,
        surface = background, onSurface = foreground, onBackground = foreground,
        onPrimary = Color(0xFF232B35), surfaceVariant = panel)
        else lightColorScheme(primary = accent, background = background, surface = background,
            onSurface = foreground, onBackground = foreground, onPrimary = Color.White, surfaceVariant = panel)
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("배터리 지킴이", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Image(painterResource(if (s.enabled) R.drawable.sheep_sleeping else R.drawable.sheep_resting),
                    if (s.enabled) "편안히 잠든 양" else "앉아서 쉬는 양", Modifier.size(218.dp))
                Text(if (s.enabled) "오늘 밤도 내가 지켜줄게" else "지금은 쉬고 있어요",
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("미디어 일시정지 · 홈 이동 · 화면 잠금", fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
                Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("자동 보호", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text(if (s.enabled) "켜짐" else "꺼짐", fontSize = 14.sp)
                    }
                    Switch(s.enabled, onCheckedChange = { enabled ->
                        scope.launch { repository.setEnabled(enabled) }
                        if (enabled && !ready) showPermissions = true
                    })
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("몇 시부터 지켜줄까?", fontSize = 16.sp)
                        Text("매일 ${timeLabel(s.startMinute)} — ${timeLabel(s.endMinute)}",
                            fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { showTime = true }) { Text("변경") }
                }
                Spacer(Modifier.height(24.dp))
                Text("배터리가 얼마나 남으면 쉴까?", Modifier.fillMaxWidth(), fontSize = 16.sp)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 20, 25).forEach { value ->
                        FilterChip(s.threshold == value, { scope.launch { repository.setThreshold(value) } },
                            label = { Text("$value%", fontSize = 16.sp) }, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(status, fontSize = 15.sp, color = accent)
                Text("현재 ${if (battery < 0) "확인 중" else "$battery%"}${if (plugged) " · 충전 연결" else ""}",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (s.enabled && !ready) TextButton(onClick = { showPermissions = true }) { Text("보호 권한 연결하기") }
                Spacer(Modifier.height(24.dp))
                Text(s.lastResult, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                if (transientMessage.isNotBlank()) Text(transientMessage, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { showPermissions = true }) { Text("권한 안내") }
                    TextButton(onClick = { showTests = true }) { Text("테스트") }
                }
                Text("한 보호 시간대에 한 번 실행해요. 게임에 따라 백그라운드 동작이 남을 수 있어요.", fontSize = 12.sp)
            }
            if (showTime) TimeSettingsDialog(activity, s, { showTime = false }, { start, end ->
                scope.launch { repository.setTime(start, end) }; showTime = false
            })
            if (showPermissions) AlertDialog(onDismissRequest = { showPermissions = false },
                title = { Text("잠든 사이 지키려면") }, text = {
                    Column {
                        Text("설정한 조건에 따라 지원 미디어를 일시정지하고 홈 화면으로 이동한 뒤 잠급니다. 다른 앱을 강제 종료하지 않습니다.")
                        Spacer(Modifier.height(16.dp))
                        Text("접근성: ${if (connected) "연결됨" else "연결 필요"}")
                        TextButton(onClick = { permissionDetail = "accessibility" }) { Text("접근성 사용 안내") }
                        Text("미디어 접근: ${if (mediaGranted && mediaConnected) "연결됨" else "연결 필요"}")
                        TextButton(onClick = { permissionDetail = "media" }) { Text("알림 접근 사용 안내") }
                        Text("알람 음량·설정은 바꾸지 않습니다. 자동 절전 모드 전환은 포함되지 않습니다.", fontSize = 13.sp)
                    }
                }, confirmButton = { TextButton(onClick = { showPermissions = false }) { Text("닫기") } })
            if (permissionDetail.isNotBlank()) AlertDialog(onDismissRequest = { permissionDetail = "" },
                title = { Text(if (permissionDetail == "accessibility") "접근성 권한 안내" else "알림 접근 권한 안내") },
                text = { Text(if (permissionDetail == "accessibility")
                    "홈 이동과 화면 잠금에 접근성을 사용합니다. 시계·알람 화면을 피하기 위해 현재 앱의 패키지 이름만 일시 확인합니다. 화면 내용·입력 내용은 읽거나 저장하지 않습니다. 설정에서 배터리 지킴이를 직접 켜주세요."
                    else "지원 앱의 활성 미디어 세션에 일시정지를 요청하기 위해 알림 접근을 사용합니다. 알림 본문은 조회·저장·전송하지 않습니다. 운영체제는 광범위한 알림 접근 경고를 표시할 수 있습니다.") },
                confirmButton = { TextButton(onClick = {
                    val action = if (permissionDetail == "accessibility") Settings.ACTION_ACCESSIBILITY_SETTINGS
                        else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    permissionDetail = ""
                    runCatching { activity.startActivity(Intent(action)) }
                        .onFailure { transientMessage = "설정 화면을 열지 못했어요. 휴대폰 설정에서 권한을 열어주세요." }
                }) { Text("동의하고 설정 열기") } },
                dismissButton = { TextButton(onClick = { permissionDetail = "" }) { Text("취소") } })
            if (showTests) TestDialog(s, { showTests = false }, { showTests = false; confirmTest = true })
            if (confirmTest) AlertDialog(onDismissRequest = { confirmTest = false },
                title = { Text("실제 보호 동작 테스트") },
                text = { Text("현재 시간·잔량·충전·자동 보호 ON/OFF 조건을 무시하고, 5초 후 미디어 일시정지·홈 이동·잠금을 요청합니다. 권한 연결과 통화·알람 회피 조건은 유지합니다. 화면을 떠나면 취소됩니다.${if (!ready) "\n먼저 권한을 연결해주세요." else ""}") },
                confirmButton = { TextButton(enabled = ready, onClick = { confirmTest = false; countdown = 5 }) { Text("5초 후 실행") } },
                dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("취소") } })
            if (countdown > 0) AlertDialog(onDismissRequest = { countdown = 0 },
                title = { Text("${countdown}초 후 실제 보호 실행") }, text = { Text("이 테스트는 휴대폰 화면을 잠급니다.") },
                confirmButton = { TextButton(onClick = { countdown = 0 }) { Text("테스트 취소") } })
        }
    }
}

/** 프리셋과 직접 지정으로 시간을 선택한다. 동일한 시작·종료는 저장할 수 없다. */
@Composable
private fun TimeSettingsDialog(context: Context, s: GuardianSettings, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var start by remember { mutableIntStateOf(s.startMinute) }
    var end by remember { mutableIntStateOf(s.endMinute) }
    fun pick(value: Int, update: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> update(h * 60 + m) }, value / 60, value % 60, true).show()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("매일 보호할 시간") }, text = {
        Column {
            listOf(0 to 420, 1380 to 420, 60 to 360).forEach { (a, b) ->
                TextButton(onClick = { start = a; end = b }) { Text("${timeLabel(a)} — ${timeLabel(b)}") }
            }
            TextButton(onClick = { pick(start) { start = it } }) { Text("시작 ${timeLabel(start)} · 직접 지정") }
            TextButton(onClick = { pick(end) { end = it } }) { Text("종료 ${timeLabel(end)} · 직접 지정") }
            if (start == end) Text("시작과 종료 시간을 다르게 정해주세요")
        }
    }, confirmButton = { TextButton(enabled = start != end, onClick = { onSave(start, end) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** 가상 조건을 순수 판단기에 전달한다. 실제 동작과 설정 변경은 발생하지 않는다. */
@Composable
private fun TestDialog(s: GuardianSettings, onDismiss: () -> Unit, onRealTest: () -> Unit) {
    var battery by remember { mutableIntStateOf(s.threshold) }
    var minute by remember { mutableIntStateOf(s.startMinute) }
    var plugged by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var alreadyRan by remember { mutableStateOf(false) }
    val now = LocalDateTime.now().withHour(minute / 60).withMinute(minute % 60)
    val simulated = s.copy(enabled = enabled, lastWindow = "")
    val first = ProtectionEvaluator.evaluate(simulated, now, battery, plugged)
    val result = ProtectionEvaluator.evaluate(simulated.copy(lastWindow = if (alreadyRan) first.window else ""), now, battery, plugged)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("가상 조건 테스트") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("실제 화면은 잠기지 않아요. 아래 값은 저장되지 않는 가상 값이에요.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가상 보호 ON", Modifier.weight(1f)); Switch(enabled, { enabled = it })
            }
            Text("가상 잔량 $battery%")
            Slider(battery.toFloat(), { battery = it.toInt() }, valueRange = 0f..100f, steps = 99)
            Text("가상 시각 ${timeLabel(minute)}")
            Row {
                TextButton(onClick = { minute = s.startMinute }) { Text("시작") }
                TextButton(onClick = { minute = s.endMinute }) { Text("종료") }
                TextButton(onClick = { minute = 120 }) { Text("02:00") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("충전 연결", Modifier.weight(1f)); Switch(plugged, { plugged = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("같은 밤 실행 이력", Modifier.weight(1f)); Switch(alreadyRan, { alreadyRan = it })
            }
            Text(if (result.shouldProtect) "판정: 보호 실행 대상" else "판정: ${result.message}")
            TextButton(onClick = onRealTest) { Text("실제 보호 테스트로 이동") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}
