package kr.co.snail.tacitq.hmd.hud

import kr.co.snail.tacitq.hmd.protocol.ShowWire
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 글자 색 — 08_HMD_프로토타입 팔레트. 색만으로 뜻을 전하지 않도록 문구에 기호(✓ ✕ ? ⚠)를 함께 쓴다. */
enum class Tone(val argb: Int) {
    OK(0xFF5FB98F.toInt()),
    BAD(0xFFFF6B61.toInt()),
    WARN(0xFFF0A95C.toInt()),
    IDLE(0xFF8FA2B5.toInt()),
    STOP(0xFFC9D4E0.toInt()),
}

/**
 * HUD 틀 한 장 — 모든 화면이 같은 뼈대(상단줄 · 큰 글씨 · 본문 · 선택지 · 키 안내)를 쓴다.
 * [bg] 가 투명이면 아래의 카메라 미리보기가 비친다(H-01 조준).
 */
data class HudSpec(
    val screen: String,
    val bg: Int,
    val topLeft: String = "",
    val topLeftTone: Tone = Tone.STOP,
    val topRight: String = "",
    val topRightTone: Tone = Tone.IDLE,
    val big: String = "",
    val bigTone: Tone = Tone.STOP,
    val lines: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    val keys: List<String> = emptyList(),
)

/**
 * SHOW 쪽지 → HUD 틀. 화면 번호별 문구·색은 08_HMD_프로토타입과 구조설계서 §3 화면별 fields 명세를 따른다.
 * 모바일이 아직 안 보내는 필드는 비어 있으면 그 줄을 생략한다.
 */
object Screens {

    private const val CLEAR = 0x00000000
    private val BG_AIM = 0xFF050A08.toInt()
    private val BG_PASS = 0xFF0A1A13.toInt()
    private val BG_BAD = 0xFF1D0C0B.toInt()
    private val BG_WARN = 0xFF1F1508.toInt()
    private val BG_CURSOR = 0xFF05070A.toInt()
    private val BG_STOP = 0xFF101620.toInt()

    fun from(show: ShowWire): HudSpec {
        val f = show.fields
        return when (show.screen) {
            "H-01" -> if (f["state"] == "IDLE") idle() else aim(f)
            "H-1b" -> rotate(f)
            "H-02" -> pass(f)
            "H-03" -> fail(f)
            "H-04" -> if (f["countdown"] != null) countdown(f) else review(f)
            "H-05" -> objection(f)
            "H-06" -> alert(f)
            "H-07" -> stopped(f)
            "H-08" -> recover(f)
            "H-09" -> manual(f["reason"])
            else -> HudSpec(show.screen, BG_AIM, big = "알 수 없는 화면 ${show.screen}", bigTone = Tone.WARN)
        }
    }

    /** 앱 시작 직후 — 모바일이 아직 붙지 않음 */
    fun waiting(linkState: String) = HudSpec(
        screen = "WAIT", bg = CLEAR,
        topLeft = "TacitQ HMD", topRight = linkState,
        big = "모바일 연결 대기", bigTone = Tone.IDLE,
    )

    fun idle() = HudSpec(
        screen = "H-01", bg = CLEAR,
        topLeft = "● 대기", topLeftTone = Tone.IDLE,
        big = "검사 대기", bigTone = Tone.IDLE,
        lines = listOf("다음 부품을 준비하세요"),
    )

    /** H-09 수동 판정 — 모바일이 아니라 HMD 가 스스로 띄우는 유일한 화면(R16) */
    fun manual(reason: String?) = HudSpec(
        screen = "H-09", bg = BG_BAD,
        topLeft = "⚠ AI 미적용 — 육안 검사", topLeftTone = Tone.BAD,
        big = "수동 판정", bigTone = Tone.BAD,
        // TODO(3단계): 로컬 링버퍼 녹화가 붙으면 "HMD 로컬 녹화 계속" 줄 추가
        lines = listOf("사유 : ${reason ?: "모바일 중계 끊김"}", "복구 시 소급 판정됩니다"),
        keys = listOf("↕ 끄덕임 = 합격", "↔ 가로젓기 = 불합격"),
    )

    private fun aim(f: Map<String, String>): HudSpec {
        val count = listOfNotNull(f["done_cnt"], f["target_cnt"]).joinToString("/")
        val view = f["view_seq"]?.let { seq ->
            "$seq/${f["view_total"] ?: "?"} ${f["view_name"].orEmpty()}".trim()
        }
        return HudSpec(
            screen = "H-01", bg = CLEAR,
            topLeft = listOf("● REC", f["part_no"], count.ifEmpty { null }).filterNotNull().joinToString(" · "),
            topLeftTone = Tone.BAD,
            topRight = view ?: f["view_total"]?.let { "${it}면 검사" }.orEmpty(),
            topRightTone = Tone.OK,
            lines = listOfNotNull(
                f["frames_target"]?.let { "프레임 확보 ${f["frames_got"] ?: 0} / $it" },
                f["occupancy_pct"]?.let { "화각 점유율 $it%" },
            ),
            keys = listOf("↻ 다음 면으로 돌리세요"),
        )
    }

    private fun rotate(f: Map<String, String>) = HudSpec(
        screen = "H-1b", bg = BG_WARN,
        topLeft = f["done_view"]?.let { "✓ ${it}면 수집 완료" }.orEmpty(), topLeftTone = Tone.OK,
        topRight = f["next_seq"]?.let { "$it/${f["view_total"] ?: "?"}" }.orEmpty(), topRightTone = Tone.WARN,
        big = "↻ ${f["rotate_text"] ?: "다음 면으로 돌리세요"}", bigTone = Tone.WARN,
        keys = listOf("응시 “다시” = 재수집"),
    )

    private fun pass(f: Map<String, String>) = HudSpec(
        screen = "H-02", bg = BG_PASS,
        topLeft = f["part_id"].orEmpty(),
        topRight = f["view_total"]?.let { "$it/$it 완료" }.orEmpty(), topRightTone = Tone.OK,
        big = "✓ 합격", bigTone = Tone.OK,
        lines = listOfNotNull(f["confidence"]?.let { "신뢰도 ${pct(it)}" }, "양품 트레이에 넣으세요"),
        keys = listOf(f["action_text"] ?: "↔ 가로젓기 = 이의"),
    )

    private fun fail(f: Map<String, String>) = HudSpec(
        screen = "H-03", bg = BG_BAD,
        topLeft = f["part_id"].orEmpty(),
        topRight = if (f["early_exit"] == "true") "조기 종료" else "", topRightTone = Tone.BAD,
        big = listOfNotNull("✕ 불합격", f["severity"]?.ifBlank { null }?.let(::severity)).joinToString(" · "),
        bigTone = Tone.BAD,
        lines = listOfNotNull(f["evidence"]?.ifBlank { null }),
        keys = listOf(f["action_text"] ?: "▣ 불량 트레이 투입 = 확인"),
    )

    private fun review(f: Map<String, String>) = HudSpec(
        screen = "H-04", bg = BG_WARN,
        topLeft = f["part_id"].orEmpty(),
        topRight = "판정 유보", topRightTone = Tone.WARN,
        big = "? 확인 요청", bigTone = Tone.WARN,
        lines = listOfNotNull(
            f["confidence"]?.let { "결함 후보 · 신뢰도 ${pct(it)}" },
            f["agree_rate"]?.let { "검출 일치율 ${pct(it)}" },
        ),
        keys = listOf("↕ 끄덕임 = 합격", "↔ 가로젓기 = 불합격"),
    )

    /** H-04 카운트다운 — 제스처 확정 전 1초 유예(R09) */
    private fun countdown(f: Map<String, String>) = HudSpec(
        screen = "H-04", bg = BG_WARN,
        topLeft = f["part_id"].orEmpty(),
        topRight = "${f["countdown"]}초", topRightTone = Tone.WARN,
        big = "${f["decision"] ?: ""} 확정 중…",
        bigTone = if (f["decision"] == "불합격") Tone.BAD else Tone.OK,
        lines = listOf("취소하려면 반대 제스처"),
    )

    private fun objection(f: Map<String, String>) = HudSpec(
        screen = "H-05", bg = BG_CURSOR,
        topLeft = "이의 사유 — 응시해서 선택",
        options = listOfNotNull(f["option_1"], f["option_2"]).map(::option),
        keys = listOf("응시 1.5초 · 세부 사유는 수거 때"),
    )

    private fun alert(f: Map<String, String>): HudSpec {
        val defect = defect(f["defect_type"].orEmpty())
        return HudSpec(
            screen = "H-06", bg = BG_WARN,
            topLeft = "⚠ 연속 불량", topLeftTone = Tone.WARN,
            big = f["streak"]?.let { "$defect ${it}연속" } ?: "$defect 반복", bigTone = Tone.WARN,
            lines = listOfNotNull(
                f["window_size"]?.let { "최근 ${it}건 중 ${f["window_hit"] ?: "?"}건" },
                f["location_hint"],
                "설비 점검 권고 — 소거되지 않습니다",
            ),
            options = listOf("정지 요청", "계속"),
        )
    }

    private fun stopped(f: Map<String, String>) = HudSpec(
        screen = "H-07", bg = BG_STOP,
        topLeft = "■ 라인 정지", topLeftTone = Tone.STOP,
        topRight = f["elapsed_sec"]?.toLongOrNull()?.let { "경과 %02d분".format(it / 60) }.orEmpty(),
        big = "조치 중", bigTone = Tone.STOP,
        lines = listOfNotNull(
            f["reason"],
            f["stopped_at"]?.toLongOrNull()?.let { at ->
                listOfNotNull("${hhmm(at)} 정지", f["responder"]).joinToString(" · ")
            },
            "재개는 반장 승인 후 자동 해제",
        ),
    )

    private fun recover(f: Map<String, String>) = HudSpec(
        screen = "H-08", bg = BG_WARN,
        topLeft = "↩ 회수 필요", topLeftTone = Tone.WARN,
        topRight = f["part_id"].orEmpty(),
        big = "양품 트레이에서 꺼내 주세요", bigTone = Tone.WARN,
        lines = listOf(
            "불합격 판정" + (f["delay_ms"]?.toLongOrNull()?.let { " · 결과 지연 %.1f초".format(it / 1000.0) } ?: "") +
                " — 이미 투입됨",
        ),
        keys = listOf("↕ 끄덕임 = 회수 완료"),
    )

    /** "0.982" 또는 "98.2" → "98.2%" */
    internal fun pct(raw: String): String {
        val v = raw.toDoubleOrNull() ?: return raw
        val p = if (v <= 1.0) v * 100 else v
        return String.format(Locale.US, "%.1f%%", p)
    }

    private fun severity(s: String) = when (s.uppercase()) {
        "CRITICAL" -> "Critical"; "MAJOR" -> "Major"; "MINOR" -> "Minor"; else -> s
    }

    private fun defect(code: String) = when (code) {
        "DENT" -> "찍힘"; "CRACK" -> "균열"; "SCRATCH" -> "긁힘"; else -> code
    }

    private fun option(code: String) = when (code) {
        "AI_FALSE_POSITIVE" -> "과검출 / 결함 아님"
        "AI_FALSE_NEGATIVE" -> "미검출 / 더 있음"
        else -> code
    }

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private fun hhmm(epochMs: Long) = HHMM.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
