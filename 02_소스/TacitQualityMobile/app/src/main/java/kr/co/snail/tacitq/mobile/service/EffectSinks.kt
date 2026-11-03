package kr.co.snail.tacitq.mobile.service

import kr.co.snail.tacitq.mobile.core.AppState
import kr.co.snail.tacitq.mobile.core.InspectionRecord
import kr.co.snail.tacitq.mobile.core.ShowCommand

/**
 * 상태 기계 출력([kr.co.snail.tacitq.mobile.core.Effect])이 나가는 바깥 경계.
 *
 * [InspectionEngine] 은 이 인터페이스만 알고, 실제 부품(HmdRenderer·LocalStore·진동·알림)은
 * 안드로이드 호스트([InspectionService])가 주입한다. 시험에서는 가짜(fake)로 갈아끼워
 * 장비 없이 이벤트 루프의 순서·유실을 검증한다.
 */
interface EffectSinks {
    /** Effect.ShowScreen — HMD 로 쪽지 전송(ACK 대기가 있어 느릴 수 있다). */
    suspend fun show(cmd: ShowCommand)

    /** Effect.Persist — 검사 결정 1건을 이력+큐에 영속화. **호출 순서가 곧 outbox 순서다.** */
    suspend fun persist(record: InspectionRecord)

    /** Effect.Enqueue — 이미 만들어진 페이로드를 전송 큐에 적재. 순서 보존. */
    suspend fun enqueue(payload: ByteArray)

    /** Effect.Vibrate — 촉각 피드백(즉시). Navigator 520 에 햅틱이 없어 이 단말이 담당. */
    fun vibrate(times: Int)

    /** Effect.NotifyLead — 조장 단말 알림(큐 적재). */
    suspend fun notifyLead(alert: String)

    /** 매 이벤트 처리 후 최신 상태 — 알림 문구 갱신·스냅샷 저장용. 기본 무동작. */
    suspend fun snapshot(state: AppState) {}
}
