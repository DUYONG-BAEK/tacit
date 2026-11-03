package kr.co.snail.tacitq.mobile.core

import kotlinx.serialization.Serializable

/**
 * 상태 기계의 출력 — "할 일 목록". 상태 기계는 이 목록을 만들 뿐,
 * 실제 부작용(화면 전송·진동·저장·전송)은 서비스 층이 실행한다.
 * 이렇게 분리해야 상태 기계가 순수 함수로 남고 장비 없이 시험된다.
 */
sealed interface Effect {
    data class ShowScreen(val command: ShowCommand) : Effect      // → C4 HmdRenderer (BLE SHOW)
    data class Vibrate(val times: Int) : Effect                   // Navigator 520 에 햅틱 없음 → 모바일이 담당
    data class Persist(val record: InspectionRecord) : Effect     // → C5 LocalStore
    data class Enqueue(val payload: ByteArray) : Effect {         // → C2 StreamRelay 큐
        override fun equals(other: Any?) =
            this === other || (other is Enqueue && payload.contentEquals(other.payload))
        override fun hashCode() = payload.contentHashCode()
    }
    data class StartTimer(val key: String, val millis: Long, val partId: String?) : Effect
    data class CancelTimer(val key: String) : Effect
    data class NotifyLead(val alert: String) : Effect             // 조장 단말 알림
}

/**
 * HMD 로 보낼 "쪽지"(BLE SHOW) — 구조설계서 §3 "화면별 fields 명세".
 * 상태 기계는 화면과 내용을 정하고, seq 번호는 C4 HmdRenderer 가 전송 시 채운다.
 * fields 는 화면(H-01~H-09)별 계약서에 맞춘 문자열 맵이다.
 */
@Serializable
data class ShowCommand(
    val screen: String,                          // "H-01" ~ "H-09" · "OVERLAY"
    val ttlMs: Long = 0,                          // 0 = 수동 소거, >0 = 자동 소거
    val gestureWindow: String = "NONE",          // NONE·OBJECT·YESNO·CURSOR·RETRY
    val windowId: String? = null,                // 제스처 창 식별자 — HMD 가 GESTURE 로 되돌려 문맥 게이팅
    val vibrate: Int = 0,
    val fields: Map<String, String> = emptyMap(),
    val seq: Long = 0,                            // 렌더러가 채움
)

/**
 * 저장 레코드 — TB_INSPECTION 으로 흘러갈 검사 1건의 결정 결과.
 * 상태 기계는 android/Room 을 모르므로 여기서 최소 필드만 만든다.
 * C5 LocalStore 가 이 레코드를 받아 model.Inspection 행으로 완성한다.
 */
@Serializable
data class InspectionRecord(
    val partId: String,
    val aiVerdict: String?,               // model.Verdict.code
    val workerAction: String,             // model.WorkerAct.code
    val confirmTrigger: String? = null,   // model.ConfirmTrigger.code
    val inputMethod: String,              // model.InputMethod.code
    val lateRecovery: Boolean = false,
    val objectionReason: String? = null,  // model.ObjectionReason.code
    val at: Long = 0,
    val aiScore: Double? = null,          // AI 서버 이상 점수(score) — 이력 표시용
)
