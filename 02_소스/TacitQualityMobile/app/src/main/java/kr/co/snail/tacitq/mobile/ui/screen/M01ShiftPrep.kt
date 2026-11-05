package kr.co.snail.tacitq.mobile.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.model.*
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-01 교대 준비 (P0) — 모바일에서 하는 마지막 조작.
 *
 * 점검 6항목이 모두 통과해야 "검사 시작"이 눌린다. v0.7에서 '촬영 품질 점검'이
 * 스트림 대역폭 + 프레임 게이팅 시험으로 바뀌었다. 셔터가 없으니 점검할 대상이
 * 사진 한 장이 아니라 흐름이다.
 *
 * 각 항목의 결과는 TB_SHIFT_CHECK에 항목 1건당 1행으로 남는다 — 특히
 * 제스처 캘리브레이션 결과는 O-23 검증의 1차 데이터다.
 */
@Composable
fun ShiftPrepScreen(vm: AppViewModel) {
    val checks by vm.repo.shiftChecks.collectAsStateWithLifecycle()
    val serviceRunning by InspectionService.running.collectAsStateWithLifecycle()
    val relayFlag by vm.relayRunning.collectAsStateWithLifecycle()
    val relay = relayFlag || serviceRunning
    val status by InspectionService.relayStatus.collectAsStateWithLifecycle()
    val hmdOn by InspectionService.hmdVideo.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val settings = remember { DevSettings(ctx) }
    var hmdUrl by remember { mutableStateOf(settings.hmdUrl) }
    var edgeUrl by remember { mutableStateOf(settings.edgeUrl) }
    var edgeKind by remember { mutableStateOf(settings.edgeKind) }
    var aiUrl by remember { mutableStateOf(settings.aiUrl) }
    var liveMarks by remember { mutableStateOf(settings.liveMarks) }
    var stationId by remember { mutableStateOf(settings.stationId) }
    val hmdBle by InspectionService.hmdBle.collectAsStateWithLifecycle()
    val blePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (!r.values.all { it }) vm.toast("블루투스 권한이 없어 HMD 화면 표시는 안 됩니다 (영상 중계는 진행)")
        InspectionService.start(ctx, vm.repo.me().lineId ?: "A", vm.repo.profile().partNo, hmdUrl, edgeUrl)
        vm.setRelay(true)
        vm.openLiveOnHmd = true                 // HMD 영상이 들어오면 영상 탭으로
        vm.toast("검사 시작 — HMD 연결 중")
    }
    val me = vm.repo.me()
    val profile = vm.repo.profile()
    val hmd = vm.repo.hmd()

    val blocked = checks.any { it.result == CheckResult.FAIL }
    val allPassed = checks.all { it.result == CheckResult.PASS }

    Field("작업자", me.display)
    Field("라인 · 품번", "${me.lineId} · ${profile.partNo} · ${profile.viewSummary}")

    SectionTitle("점검 ${checks.count { it.result == CheckResult.PASS }} / ${checks.size}")
    checks.forEach { chk ->
        val (fg, bg) = when (chk.result) {
            CheckResult.PASS -> T.Green to T.GreenLight
            CheckResult.WARN -> T.Amber to T.AmberLight
            CheckResult.FAIL -> T.Red to T.RedLight
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    // 재점검: 실패한 항목만 다시 돌린다
                    vm.repo.retryCheck(chk.item, CheckResult.PASS, chk.measuredVal ?: "재점검 통과")
                    vm.toast("${chk.item.label} 재점검 — 통과")
                }
        ) {
            Field(
                label = chk.item.label,
                value = chk.measuredVal ?: "-",
                accent = if (chk.result == CheckResult.PASS) null else fg,
                modifier = Modifier.weight(1f)
            )
            Column(Modifier.padding(start = 8.dp, top = 14.dp)) {
                Badge(chk.result.label, fg, bg)
                if (chk.retryCnt > 0)
                    Text("재시도 ${chk.retryCnt}", fontSize = 9.sp, color = T.Muted)
            }
        }
    }

    Field("모델 · 임계값", Profile().let { "${vm.repo.profile().profileVersion} · 앱 보수 기준 PD ≥ ${it.pdMinConf} · QI 서버 기준값 ×${it.qiFactor}" })
    Field("HMD", "${hmd.deviceId} · 배터리 ${hmd.batteryPct}% · ${"%.1f".format(hmd.bitrateMbps)} Mbps")

    NoteBox(
        "v0.7 변경",
        "'촬영 품질 점검'이 스트림 대역폭 + 프레임 게이팅 시험으로 바뀌었다. " +
            "셔터가 없으므로 점검할 대상이 사진 한 장이 아니라 흐름이다. " +
            "점검 결과는 TB_SHIFT_CHECK에 남아 제스처 인식률 추이를 뒤에서 확인할 수 있다."
    )

    // 개발용 연결 설정 — C1(BLE HELLO) 전까지 HMD 주소는 손으로 넣는다
    SectionTitle("연결 설정 (개발용)")
    OutlinedTextField(
        value = hmdUrl, onValueChange = { hmdUrl = it; settings.hmdUrl = it },
        label = { Text("HMD 영상 주소") }, singleLine = true, enabled = !relay,
        modifier = Modifier.fillMaxWidth(),
    )
    // HMD 결함 원 — 켜면 판정 글자 + 결함 위치 원, 끄면 판정 글자만(표시 지연이 커서 원이 어긋나 보일 때)
    Row(Modifier.padding(top = 8.dp)) {
        Text("HMD 결함 원", fontSize = 13.sp, color = T.Ink, modifier = Modifier.padding(end = 14.dp))
        listOf(true to "켜기", false to "끄기 (판정 글자만)").forEach { (v, label) ->
            val on = liveMarks == v
            Text(
                (if (on) "● " else "○ ") + label,
                fontSize = 13.sp, color = if (on) T.Purple else T.Muted,
                modifier = Modifier
                    .padding(end = 18.dp)
                    .clickable(enabled = !relay) { liveMarks = v; settings.liveMarks = v },
            )
        }
    }
    // 엣지 종류 — 실제 AI 서버(WebRTC) / 모의 엣지(WebSocket, PC)
    Row(Modifier.padding(top = 8.dp)) {
        listOf(DevSettings.EDGE_AI to "AI 서버", DevSettings.EDGE_MOCK to "모의 엣지").forEach { (k, label) ->
            val on = edgeKind == k
            Text(
                (if (on) "● " else "○ ") + label,
                fontSize = 13.sp, color = if (on) T.Purple else T.Muted,
                modifier = Modifier
                    .padding(end = 18.dp)
                    .clickable(enabled = !relay) { edgeKind = k; settings.edgeKind = k },
            )
        }
    }
    if (edgeKind == DevSettings.EDGE_AI) {
        OutlinedTextField(
            value = aiUrl, onValueChange = { aiUrl = it; settings.aiUrl = it },
            label = { Text("AI 서버 주소") }, singleLine = true, enabled = !relay,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = stationId, onValueChange = { stationId = it; settings.stationId = it },
            label = { Text("스테이션") }, singleLine = true, enabled = !relay,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = edgeUrl, onValueChange = { edgeUrl = it; settings.edgeUrl = it },
            label = { Text("엣지 주소") }, singleLine = true, enabled = !relay,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (relay) {
        val s = status
        Text(
            if (s == null) "중계 준비 중…" else
                "HMD 영상 ${if (hmdOn) "수신" else "끊김"} · 화면 $hmdBle · 엣지 ${if (s.edgeConnected) "연결" else "끊김"} · " +
                    "보냄 ${s.sent} · 큐 ${s.queued} · 버림 ${s.dropped}" +
                    (s.lastSegment?.let { " · 마지막 구간 ${if (it.pass) "합격" else "불합격"}(${it.aiDecision}) · 결함 프레임 ${it.defectFrames}/${it.frames}장" }
                        ?: s.lastJudge?.let { " · 판정 $it (${s.lastDelayMs}ms)" } ?: "") +
                    (s.guide?.let { "\n서버: $it" } ?: ""),
            fontSize = 11.5.sp, color = if (s?.edgeConnected == true) T.Teal else T.Red,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        text = if (relay) "검사 진행 중 — 교대 마감" else "검사 시작 — 스트림 개시",
        color = if (relay) T.Teal else T.Purple,
        enabled = relay || (allPassed && !blocked && hmdUrl.isNotBlank() &&
            (if (edgeKind == DevSettings.EDGE_AI) aiUrl.isNotBlank() && stationId.isNotBlank() else edgeUrl.isNotBlank()))
    ) {
        if (relay) {
            InspectionService.stop(ctx); vm.setRelay(false); vm.toast("중계 종료 — 교대 마감")
        } else {
            // HMD 화면 지시(BLE)에 필요한 권한을 먼저 받고 시작한다. 거부해도 영상 중계는 돈다.
            blePermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
    }
    if (blocked) {
        Spacer(Modifier.height(6.dp))
        Text(
            "기준 미달 항목이 있어 검사를 시작할 수 없습니다. 항목을 눌러 재점검하세요.",
            fontSize = 11.5.sp, color = T.Red
        )
    }
}
