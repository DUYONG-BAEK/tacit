package kr.co.snail.tacitq.mobile.ui.screen

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiMessages
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.ai.FeedbackMode
import kr.co.snail.tacitq.mobile.hmd.HmdHello
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-01 검사 시작 (P0) — 2026-11-11 재구성. 모두 실제로 잰 값만 보인다(샘플 점검 6항목·HMD 고정값 제거).
 *
 * 시작 전 확인(필수 — 하나라도 안 되면 '검사 시작'이 막힌다): 스테이션·AI 서버 주소 입력 · 폰 블루투스 켜짐 ·
 *   AI 서버 응답(`GET /storage`). 블루투스 권한은 '검사 시작'을 누를 때 묻고, 거부하면 시작하지 않는다
 *   (HMD 는 폰 BLE 쪽지가 와야 앞으로 나와 카메라를 켠다).
 * HMD(연결·카메라·영상·배터리)는 폰이 BLE 로 불러야 켜지므로 시작 뒤 '검사 중 상태'에 실시간으로 보인다(막지 않음).
 * 스테이션이 서버에 등록돼 있지 않으면 연결 때 서버가 403 으로 거절하고 '검사 중 상태'에 설정 오류가 뜬다.
 */
@Composable
fun ShiftPrepScreen(vm: AppViewModel) {
    val serviceRunning by InspectionService.running.collectAsStateWithLifecycle()
    val relayFlag by vm.relayRunning.collectAsStateWithLifecycle()
    val relay = relayFlag || serviceRunning
    val ctx = LocalContext.current
    val settings = remember { DevSettings(ctx) }
    var hmdUrl by remember { mutableStateOf(settings.hmdUrl) }
    var aiUrl by remember { mutableStateOf(settings.aiUrl) }
    var liveMarks by remember { mutableStateOf(settings.liveMarks) }
    var feedbackMode by remember { mutableStateOf(settings.feedbackMode) }
    var stationId by remember { mutableStateOf(settings.stationId) }

    // 시작 전 확인 — 화면이 떠 있는 동안 CHECK_MS 마다(검사 중에는 멈춤). null = 확인 중
    var btOn by remember { mutableStateOf<Boolean?>(null) }
    var server by remember { mutableStateOf<ServerCheck?>(null) }
    LaunchedEffect(aiUrl, relay) {
        if (relay) return@LaunchedEffect
        server = null
        delay(500)                                    // 주소를 고치는 중이면 다 칠 때까지 기다린다
        val api = AiResultsApi(aiUrl)
        while (true) {
            btOn = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            server = if (aiUrl.isBlank()) ServerCheck(false, "주소 없음") else withContext(Dispatchers.IO) { checkServer(api) }
            delay(CHECK_MS)
        }
    }
    val inputsOk = stationId.isNotBlank() && aiUrl.isNotBlank()
    val ready = inputsOk && btOn == true && server?.ok == true

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (BT_PERMISSIONS.any { r[it] == false }) {
            vm.toast("블루투스 권한이 없으면 HMD 를 불러올 수 없어 검사를 시작하지 않습니다")
            return@rememberLauncherForActivityResult
        }
        InspectionService.start(ctx, hmdUrl)
        vm.setRelay(true)
        vm.openLiveOnHmd = true                 // HMD 영상이 들어오면 영상 탭으로
        vm.toast("검사 시작 — HMD 연결 중")
    }

    SectionTitle("스테이션")
    OutlinedTextField(
        value = stationId, onValueChange = { stationId = it; settings.stationId = it },
        label = { Text("스테이션 (예: ST-02)") }, singleLine = true, enabled = !relay,
        modifier = Modifier.fillMaxWidth(),
    )

    if (relay) RunningStatus()
    else {
        SectionTitle("시작 전 확인")
        CheckRow("입력값", if (inputsOk) "스테이션 · AI 서버 주소" else "스테이션 · AI 서버 주소를 넣으세요", inputsOk)
        CheckRow(
            "폰 블루투스",
            when (btOn) { null -> "확인 중"; true -> "켜짐"; false -> "꺼짐 — 블루투스를 켜세요" },
            btOn,
        )
        CheckRow("AI 서버", server?.text ?: "확인 중", server?.ok)
    }

    SectionTitle("연결 설정")
    OutlinedTextField(
        value = aiUrl, onValueChange = { aiUrl = it; settings.aiUrl = it },
        label = { Text("AI 서버 주소") }, singleLine = true, enabled = !relay,
        modifier = Modifier.fillMaxWidth(),
    )
    // HMD 가 BLE HELLO 로 알려 주면 자동으로 바뀐다 — 옛 HMD 앱이거나 BLE 가 안 될 때만 손으로 넣는다
    OutlinedTextField(
        value = hmdUrl, onValueChange = { hmdUrl = it; settings.hmdUrl = it },
        label = { Text("HMD 영상 주소 (BLE 로 자동)") }, singleLine = true, enabled = !relay,
        modifier = Modifier.fillMaxWidth(),
    )
    // HMD 결함 원 — 켜면 판정 글자 + 결함 위치 원, 끄면 판정 글자만(표시 지연이 커서 원이 어긋나 보일 때)
    Choice("HMD 결함 원", listOf(true to "켜기", false to "끄기 (판정 글자만)"), liveMarks, enabled = !relay) {
        liveMarks = it; settings.liveMarks = it
    }
    // 피드백 모드 — 수동: 제품마다 폰 화면에서 맞음·틀림·확인필요 / 자동: 피드백 건너뜀. 검사 중에는 못 바꾼다(연결 때 서버에 알림)
    Choice("모드", listOf(FeedbackMode.MANUAL to "수동 (폰에서 피드백)", FeedbackMode.AUTO to "자동 (피드백 건너뜀)"), feedbackMode, enabled = !relay) {
        feedbackMode = it; settings.feedbackMode = it
    }

    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        text = if (relay) "검사 진행 중 — 교대 마감" else "검사 시작 — 스트림 개시",
        color = if (relay) T.Teal else T.Purple,
        enabled = relay || ready,
    ) {
        if (relay) {
            InspectionService.stop(ctx); vm.setRelay(false); vm.toast("중계 종료 — 교대 마감")
        } else {
            // HMD 를 부르는 BLE 권한(필수)과 검사 중 알림 권한(선택)을 받은 뒤 시작한다
            permission.launch(START_PERMISSIONS)
        }
    }
    if (!relay && !ready) {
        Spacer(Modifier.height(6.dp))
        Text("시작 전 확인이 모두 통과해야 검사를 시작할 수 있습니다.", fontSize = 11.5.sp, color = T.Red)
    }
}

/** 검사 중 상태 — HMD·AI 서버 실시간. 막지 않고 보이기만 한다. */
@Composable
private fun RunningStatus() {
    val s by InspectionService.relayStatus.collectAsStateWithLifecycle()
    val hmdOn by InspectionService.hmdVideo.collectAsStateWithLifecycle()
    val hmdBle by InspectionService.hmdBle.collectAsStateWithLifecycle()
    val hmd by InspectionService.hmdInfo.collectAsStateWithLifecycle()
    val st = s

    SectionTitle("검사 중 상태")
    Row {
        Field("HMD 연결 (BLE)", hmdBle, accent = if (hmdBle == "HMD 연결됨") null else T.Red, modifier = Modifier.weight(1f))
        Field("HMD 영상", if (hmdOn) "수신" else "끊김", accent = if (hmdOn) null else T.Red, modifier = Modifier.weight(1f))
    }
    Row {
        val cam = hmd?.camera
        Field("HMD 카메라", cam ?: "-", accent = if (cam == null || cam.startsWith("정상")) null else T.Red, modifier = Modifier.weight(1f))
        Field("HMD 배터리", hmdBatteryText(hmd), accent = if (hmd?.batteryLow == true) T.Red else null, modifier = Modifier.weight(1f))
    }
    val configError = st?.guide?.takeIf { it.startsWith("설정 오류") }
    Field(
        "AI 서버",
        when {
            st == null -> "연결 준비 중"
            st.edgeConnected -> "연결 · 보냄 ${st.sent}장"
            configError != null -> configError
            else -> "끊김 — 다시 연결 중"
        },
        accent = if (st?.edgeConnected == true) null else T.Red,
    )
    if (configError == null) st?.guide?.let { Field("서버 진행", it, accent = T.Teal) }
    st?.lastSegment?.let {
        Field(
            "마지막 제품",
            "${if (it.pass) "합격" else "불합격"} (${it.aiDecision}${it.defectLabel?.let { l -> " $l" } ?: ""}) · 결함 프레임 ${it.defectFrames}/${it.frames}장",
            accent = if (it.pass) T.Green else T.Red,
        )
    }
}

/** 확인 한 줄 — ok: true 통과 · false 안 됨 · null 확인 중 */
@Composable
private fun CheckRow(label: String, value: String, ok: Boolean?) {
    val (text, fg, bg) = when (ok) {
        true -> Triple("통과", T.Green, T.GreenLight)
        false -> Triple("안 됨", T.Red, T.RedLight)
        null -> Triple("확인 중", T.Amber, T.AmberLight)
    }
    Row {
        Field(label, value, accent = if (ok == false) fg else null, modifier = Modifier.weight(1f))
        Column(Modifier.padding(start = 8.dp, top = 14.dp)) { Badge(text, fg, bg) }
    }
}

/** 둘 중 하나 고르기 — ● 선택 ○ 비선택 */
@Composable
private fun <V> Choice(title: String, options: List<Pair<V, String>>, value: V, enabled: Boolean, onPick: (V) -> Unit) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(title, fontSize = 13.sp, color = T.Ink, modifier = Modifier.padding(end = 14.dp))
        options.forEach { (v, label) ->
            val on = value == v
            Text(
                (if (on) "● " else "○ ") + label,
                fontSize = 13.sp, color = if (on) T.Purple else T.Muted,
                modifier = Modifier
                    .padding(end = 18.dp)
                    .clickable(enabled = enabled) { onPick(v) },
            )
        }
    }
}

/** HMD 배터리 한 줄 — M-01·M-07 공통. 옛 HMD 앱은 HELLO 에 배터리가 없다. */
internal fun hmdBatteryText(h: HmdHello?): String {
    if (h == null) return "-"
    val b = h.battery ?: return "알 수 없음 (HMD 앱 업데이트 필요)"
    return "$b%" + (if (h.charging == true) " · 충전 중" else "") + (if (h.batteryLow) " — 충전 필요" else "")
}

private class ServerCheck(val ok: Boolean, val text: String)

/** AI 서버 응답 확인 — `GET /storage` 200 이면 통과(남은 디스크도 보인다) */
private fun checkServer(api: AiResultsApi): ServerCheck = try {
    val r = api.storage()
    if (r.code != 200) ServerCheck(false, "응답 ${r.code} — 주소 확인")
    else {
        val free = runCatching { AiMessages.parseStorage(r.body).diskFree }.getOrNull()
        ServerCheck(true, "응답" + (free?.let { " · 디스크 여유 %.0f GB".format(it / 1e9) } ?: ""))
    }
} catch (e: Exception) {
    ServerCheck(false, "응답 없음 — 주소 · Wi-Fi 확인")
}

private const val CHECK_MS = 5_000L

private val BT_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else emptyList()

private val START_PERMISSIONS: Array<String> =
    (BT_PERMISSIONS + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        .toTypedArray()
