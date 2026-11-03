package kr.co.snail.tacitq.mobile.core

import kr.co.snail.tacitq.mobile.model.ConfirmTrigger
import kr.co.snail.tacitq.mobile.model.InputMethod
import kr.co.snail.tacitq.mobile.model.WorkerAct

/**
 * 저장 레코드 공장 — 상태 기계가 남기는 결정 결과를 코드 정의서(model)의 어휘로 만든다.
 * workerAction·inputMethod·confirmTrigger 는 반드시 enum 코드에서만 가져온다.
 */
object Records {

    /** R01 — 합격 무확인 자동 통과 */
    fun autoPass(p: Part) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.AUTO_PASS.code,
        inputMethod = InputMethod.NONE.code,
        at = p.judgedAt ?: 0,
        aiScore = p.confidence,
    )

    /** R03 — 불합격 판정 직후. 이력에 바로 보이게 먼저 남긴다(같은 partId 로 확인·초과 기록이 덮어쓴다). */
    fun failPending(p: Part) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.PENDING.code,
        confirmTrigger = ConfirmTrigger.FAIL_VERDICT.code,
        inputMethod = InputMethod.NONE.code,
        at = p.judgedAt ?: 0,
        aiScore = p.confidence,
    )

    /** R08 — 트레이 투입 = 확인 */
    fun confirmed(p: Part, trigger: String) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.CONFIRMED.code,
        confirmTrigger = trigger,
        inputMethod = InputMethod.TRAY_SENSOR.code,
        at = p.trayDetectedAt ?: p.judgedAt ?: 0,
        aiScore = p.confidence,
    )

    /** R05 — 지연 후 회수. 안전측으로 불합격을 유지하고 회수 플래그를 남긴다. */
    fun late(p: Part, delayMs: Long) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.CONFIRMED.code,
        confirmTrigger = ConfirmTrigger.FAIL_VERDICT.code,
        inputMethod = InputMethod.NONE.code,
        lateRecovery = true,
        at = p.judgedAt ?: 0,
    )

    /** R12 — 확인 시간 초과. 판정을 바꾸지 않고 TIMEOUT 만 남긴다(안전측). */
    fun timeout(p: Part) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.TIMEOUT.code,
        inputMethod = InputMethod.NONE.code,
        at = p.judgedAt ?: 0,
        aiScore = p.confidence,
    )

    /** R09 — REVIEW 를 제스처로 판정 확정 */
    fun reviewDecided(p: Part) = InspectionRecord(
        partId = p.id,
        aiVerdict = p.verdict?.code,
        workerAction = WorkerAct.REVIEW_DECIDED.code,
        confirmTrigger = ConfirmTrigger.REVIEW.code,
        inputMethod = InputMethod.GESTURE.code,
        at = p.judgedAt ?: 0,
    )

    /** R11 — 이의 제기. 사유는 A-03 명장 리뷰 큐로 간다("이의가 자산이다"). */
    fun objection(partId: String, optionId: String) = InspectionRecord(
        partId = partId,
        aiVerdict = null,
        workerAction = WorkerAct.OBJECTED.code,
        inputMethod = InputMethod.HEAD_CURSOR.code,
        objectionReason = optionId,
    )
}

/**
 * 랜덤 표본 추출(R02) — 결정적(deterministic)이어야 한다.
 * reduce() 가 "같은 입력이면 같은 출력"을 보장하려면 표본 판정이 partId 만의 함수여야 한다.
 * 난수를 쓰면 같은 판정이 시험에서 재현되지 않는다.
 */
object Sampler {
    fun hit(ratePct: Double, partId: String): Boolean {
        if (ratePct <= 0.0) return false
        val bucket = (((partId.hashCode() % 10000) + 10000) % 10000) / 100.0  // 0.00 ~ 99.99
        return bucket < ratePct
    }
}

/**
 * 전송 페이로드 자리표시자 — 실제 조립은 C2 StreamRelay·C5 LocalStore 가 한다.
 * 상태 기계는 "큐를 비워라 / 재전송하라"는 의도만 낸다.
 */
object Payloads {
    fun flush(): ByteArray = "FLUSH".toByteArray()      // R20 교대 마감 강제 플러시
    fun resend(): ByteArray = "RESEND".toByteArray()    // R18 복구 후 순서 재전송
}
