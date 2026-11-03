package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict

/**
 * AI 서버 메시지 → 상태기계 이벤트. 안드로이드·WebRTC 를 모른다(단위 시험 대상). 설계서 §9.
 *
 *  - 판정: OK → 합격 · NG·REVIEW·모르는 값 → 불합격 (판정 3가지 규칙). 부품 ID = result_id, 순번은 여기서 매긴다.
 *  - 지연: captured 받은 시각 → result 받은 시각 (폰 시계).
 *  - 끊긴 사이 판정: captured 후 result 전인 result_id 는 [pending] 에 남아, 재연결 뒤 조회 결과를 [onPolled] 로 넣는다.
 *  - 같은 result_id 판정은 한 번만 넘긴다(실시간과 조회가 겹쳐도).
 */
class AiFlow(
    private val emit: (Event) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** HMD 조준 화면에 띄울 진행 안내 — 바뀔 때만 부른다(BLE HINT) */
    private val onHint: (text: String, tone: String) -> Unit = { _, _ -> },
) {
    private var lastHint: String? = null

    private fun hint(text: String, tone: String) {
        if (text == lastHint) return
        lastHint = text
        onHint(text, tone)
    }
    private var seq = 0L
    private val capturedAt = LinkedHashMap<String, Long>()    // captured 후 result 전
    private val done = LinkedHashSet<String>()

    /** 화면 안내 문구 — 마지막 서버 상태 */
    @Volatile var guide: String = "연결 전"; private set
    @Volatile var lastJudge: String? = null; private set
    @Volatile var lastDelayMs: Long? = null; private set

    val pending: List<String> get() = synchronized(this) { capturedAt.keys.toList() }

    /** 연결 성공 = searching 으로 본다(API §5.4 — 첫 메시지를 기다리지 않는다). */
    fun onConnected() { guide = "종이 없음"; hint("종이를 비춰 주세요", "IDLE") }

    fun onDisconnected() { guide = "연결 끊김"; lastHint = null }

    @Synchronized
    fun onMessage(m: AiMsg) {
        when (m) {
            is AiMsg.Searching -> when (m.reason) {
                "too_small" -> { guide = "종이가 작음 — 더 가까이"; hint("더 가까이 — 종이를 크게", "WARN") }
                "no_paper" -> { guide = "종이 없음"; hint("종이를 비춰 주세요", "IDLE") }
                else -> { guide = "종이 찾는 중"; hint("종이 찾는 중", "IDLE") }
            }
            is AiMsg.Hold -> {
                guide = "수집 ${m.collected}/${m.max} — 계속 들고 계세요"
                hint("수집 ${m.collected}/${m.max} — 그대로", "OK")
            }
            is AiMsg.Captured -> {
                capturedAt[m.resultId] = clock()
                guide = "판정 중 — 내려놓아도 됩니다"
                hint("판정 중 — 내려놓아도 됩니다", "OK")
            }
            is AiMsg.Result -> judge(m.resultId, m.decision, m.defectLabel, m.score)
            is AiMsg.Alert -> emit(Event.PatternAlert(level = m.type, defectType = m.resultIds.joinToString(","),
                                                      hit = m.count, window = m.count))
            is AiMsg.Unknown -> Unit
        }
    }

    /** 재연결 뒤 `GET /results/{id}` 결과. done 이면 판정, failed 면 버림, pending 이면 그대로 둔다. */
    @Synchronized
    fun onPolled(r: AiResultView) {
        when (r.status) {
            "done" -> {
                judge(r.resultId, r.decision ?: "", r.defectLabel, r.score)
                r.alert?.let { onMessage(it) }
            }
            "failed" -> capturedAt.remove(r.resultId)          // 그 종이는 다시 검사(API §7)
        }
    }

    private fun judge(resultId: String, decision: String, label: String?, score: Double?) {
        if (!done.add(resultId)) return
        if (done.size > KEEP) done.remove(done.first())
        val now = clock()
        val start = capturedAt.remove(resultId) ?: now
        val verdict = if (decision == "OK") Verdict.PASS else Verdict.FAIL
        lastJudge = "$resultId ${verdict.code}"
        lastDelayMs = now - start
        guide = "판정 ${verdict.label} ($decision)"
        lastHint = null                                  // 판정 화면 뒤 조준 화면으로 돌아오면 다음 안내를 다시 보낸다
        emit(Event.JudgeResult(
            seq = ++seq, partId = resultId, verdict = verdict, severity = null,
            confidence = score ?: 0.0, agreeRate = 1.0, evidence = label ?: decision,
            frameRef = null, bbox = null, capturedEndAt = start, arrivedAt = now,
        ))
    }

    companion object { private const val KEEP = 500 }
}
