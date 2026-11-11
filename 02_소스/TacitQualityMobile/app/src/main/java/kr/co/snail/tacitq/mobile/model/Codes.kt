package kr.co.snail.tacitq.mobile.model

/**
 * 코드 정의서 06 v1.2와 1:1 대응.
 *
 * 규칙: 화면의 선택지·배지·필터는 반드시 이 파일의 enum에서만 나온다.
 * 문자열 리터럴을 화면에 직접 쓰면 코드 정의서와 조용히 어긋난다.
 * `code`가 DB에 저장되는 값이고 `label`이 화면 표기다.
 */
interface CodeItem { val code: String; val label: String }

enum class Line(override val code: String, override val label: String) : CodeItem {
    A("A", "A라인"), B("B", "B라인"), C("C", "C라인")
}

enum class Shift(override val code: String, override val label: String) : CodeItem {
    DAY_1("DAY_1", "주간 1조"), DAY_2("DAY_2", "주간 2조"), NIGHT("NIGHT", "야간조")
}

enum class ViewCode(override val code: String, override val label: String) : CodeItem {
    TOP("TOP", "상면"), BOTTOM("BOTTOM", "하면"), SIDE("SIDE", "측면"), REAR("REAR", "후면")
}

enum class Verdict(override val code: String, override val label: String) : CodeItem {
    PASS("PASS", "합격"), FAIL("FAIL", "불합격"), REVIEW("REVIEW", "판단 보류")
}

enum class WorkerAct(override val code: String, override val label: String) : CodeItem {
    AUTO_PASS("AUTO_PASS", "무확인 자동 통과"), CONFIRMED("CONFIRMED", "확인 완료"),
    OBJECTED("OBJECTED", "이의 제기"), REVIEW_DECIDED("REVIEW_DECIDED", "REVIEW 판정"),
    TIMEOUT("TIMEOUT", "확인 시간 초과"),
    PENDING("PENDING", "확인 대기"),     // 불합격 판정 직후 — 이력에 바로 보이게 먼저 남기고, 확인·초과 시 덮어쓴다
    NONE("NONE", "확인 절차 없음")      // AI 서버 프레임 단위 판정(2026-11-03) — 사람 확인 절차가 없다
}

enum class InputMethod(override val code: String, override val label: String) : CodeItem {
    NONE("NONE", "입력 없음"), TRAY_SENSOR("TRAY_SENSOR", "트레이 투입 센서"),
    GESTURE("GESTURE", "헤드 제스처"), HEAD_CURSOR("HEAD_CURSOR", "헤드 커서 + 드웰"),
    FOOT_PEDAL("FOOT_PEDAL", "풋 페달"), HARD_BUTTON("HARD_BUTTON", "HMD 하드 버튼")
}

enum class ConfirmTrigger(override val code: String, override val label: String) : CodeItem {
    FAIL_VERDICT("FAIL_VERDICT", "불합격 판정"), REVIEW("REVIEW", "REVIEW 구간"),
    RANDOM_SAMPLE("RANDOM_SAMPLE", "랜덤 표본"), PATTERN_ALERT("PATTERN_ALERT", "연속 불량 경보"),
    LOW_AGREE("LOW_AGREE", "일치율 미달")
}

enum class DefectType(override val code: String, override val label: String, val active: Boolean = true) : CodeItem {
    DENT("DENT", "찍힘"), CRACK("CRACK", "균열"), SCRATCH("SCRATCH", "긁힘"),
    BURR("BURR", "버", false), STAIN("STAIN", "오염", false);
    companion object { fun active() = entries.filter { it.active } }
}

enum class Severity(override val code: String, override val label: String) : CodeItem {
    MINOR("MINOR", "경미"), MAJOR("MAJOR", "중결함"), CRITICAL("CRITICAL", "치명 결함")
}

enum class QualityFlag(override val code: String, override val label: String) : CodeItem {
    OK("OK", "정상"), BLUR("BLUR", "흔들림"), GLARE("GLARE", "반사"),
    TOO_FAR("TOO_FAR", "거리 과다"), PARTIAL("PARTIAL", "일부 누락")
}

enum class CaptureMode(override val code: String, override val label: String) : CodeItem {
    VIDEO_STREAM("VIDEO_STREAM", "연속 영상 스트림"), HYBRID("HYBRID", "영상 + 스틸 병행"),
    STILL("STILL", "스틸 단독")
}

enum class StreamState(override val code: String, override val label: String) : CodeItem {
    STREAMING("STREAMING", "정상 전송"), DEGRADED("DEGRADED", "화질 저하"),
    PAUSED("PAUSED", "일시 정지"), LOST("LOST", "끊김")
}

enum class PatternLevel(override val code: String, override val label: String) : CodeItem {
    WARN("WARN", "주의"), ALERT("ALERT", "경고"), CRITICAL("CRITICAL", "심각")
}

enum class TrayKind(override val code: String, override val label: String, val suffix: String) : CodeItem {
    GOOD("GOOD", "양품 트레이", "G"), DEFECT("DEFECT", "불량 트레이", "D")
}

enum class TrayStatus(override val code: String, override val label: String) : CodeItem {
    OPEN("OPEN", "개설"), CLOSED("CLOSED", "마감"),
    COLLECTED("COLLECTED", "수거 완료"), REGISTERED("REGISTERED", "등록 완료")
}

enum class ItemStatus(override val code: String, override val label: String) : CodeItem {
    STORED("STORED", "보관 중"), DISPOSE_READY("DISPOSE_READY", "폐기 대기"),
    HOLD("HOLD", "보류"), DISPOSED("DISPOSED", "폐기 완료"), REWORKED("REWORKED", "재작업 완료")
}

/** M-03 하단 3버튼과 1:1 대응한다. */
enum class Disposition(override val code: String, override val label: String, val active: Boolean = true) : CodeItem {
    SCRAP("SCRAP", "폐기"), REWORK("REWORK", "재작업"), CONCESSION("CONCESSION", "특채"),
    RETURN("RETURN", "반품", false);
    companion object { fun active() = entries.filter { it.active } }
}

enum class HoldReason(override val code: String, override val label: String) : CodeItem {
    RECAPTURE("RECAPTURE", "재촬영 필요"), MASTER_REVIEW("MASTER_REVIEW", "명장 검토"),
    CLAIM("CLAIM", "클레임 대응")
}

enum class ObjectionReason(override val code: String, override val label: String) : CodeItem {
    AI_FALSE_POSITIVE("AI_FALSE_POSITIVE", "양품인데 불합격"),
    AI_FALSE_NEGATIVE("AI_FALSE_NEGATIVE", "불량인데 합격")
}

/** M-02 추정 원인. v1.2에서 MOLD_DAMAGE(금형 면 손상)가 추가됐다. */
enum class Cause(override val code: String, override val label: String) : CodeItem {
    LUBE_SHORTAGE("LUBE_SHORTAGE", "오일 도포 부족"), MOLD_WEAR("MOLD_WEAR", "금형 마모"),
    MOLD_DEBRIS("MOLD_DEBRIS", "금형 이물"), MOLD_DAMAGE("MOLD_DAMAGE", "금형 면 손상"),
    TRANSFER_HIT("TRANSFER_HIT", "이송 충격"), MATERIAL_LOT("MATERIAL_LOT", "소재 로트 편차"),
    UNKNOWN("UNKNOWN", "원인 미상")
}

/** M-02 조치 라디오. OTHER 선택 시 메모가 필수다. */
enum class ActionTaken(
    override val code: String, override val label: String,
    val paramName: String? = null, val active: Boolean = true
) : CodeItem {
    MOLD_CLEANING("MOLD_CLEANING", "금형 청소"),
    PRESS_ADJUST("PRESS_ADJUST", "프레스 압력 조정", "프레스 압력"),
    OIL_ADJUST("OIL_ADJUST", "오일 도포 시간 조정", "오일 도포 시간"),
    MATERIAL_CHANGE("MATERIAL_CHANGE", "소재 로트 교체"),
    OTHER("OTHER", "기타"),
    MOLD_CHANGE("MOLD_CHANGE", "금형 교체", null, false);
    companion object { fun active() = entries.filter { it.active } }
}

enum class VerifyResult(override val code: String, override val label: String) : CodeItem {
    PENDING("PENDING", "검증 중"), SUCCESS("SUCCESS", "해소"), FAILED("FAILED", "재발")
}

enum class Role(override val code: String, override val label: String) : CodeItem {
    WORKER("WORKER", "작업자"), LEADER("LEADER", "조장"), EQUIP("EQUIP", "설비담당"),
    MASTER("MASTER", "명장"), ADMIN("ADMIN", "관리자"), RESEARCHER("RESEARCHER", "연구원")
}

enum class DeviceType(override val code: String, override val label: String) : CodeItem {
    HMD("HMD", "HMD"), MOBILE("MOBILE", "모바일"), EDGE("EDGE", "Edge 서버"), PLC("PLC", "PLC")
}

/** 감사 액션. v1.2에서 STORE_EXTEND 신설, EXPORT 사용 개시. */
enum class AuditAction(override val code: String, override val label: String) : CodeItem {
    VERDICT_OVERRIDE("VERDICT_OVERRIDE", "판정 수정"),
    THRESHOLD_CHANGE("THRESHOLD_CHANGE", "임계값 변경"),
    LINE_STOP("LINE_STOP", "라인 정지"),
    PLC_PARAM_CHANGE("PLC_PARAM_CHANGE", "PLC 파라미터 변경"),
    DISPOSAL_APPROVE("DISPOSAL_APPROVE", "폐기 승인"),
    STORE_EXTEND("STORE_EXTEND", "보관기한 연장"),
    ROLE_CHANGE("ROLE_CHANGE", "권한 변경"),
    USER_CREATE("USER_CREATE", "사용자 등록"),
    DEVICE_DISABLE("DEVICE_DISABLE", "단말 사용 중지"),
    MODEL_DEPLOY("MODEL_DEPLOY", "모델 배포"),
    MODEL_ROLLBACK("MODEL_ROLLBACK", "모델 롤백"),
    STREAM_POLICY_CHANGE("STREAM_POLICY_CHANGE", "영상 보관 정책 변경"),
    ENSEMBLE_PARAM_CHANGE("ENSEMBLE_PARAM_CHANGE", "앙상블 파라미터 변경"),
    EXPORT("EXPORT", "데이터 반출")
}

enum class ResultCd(override val code: String, override val label: String) : CodeItem {
    SUCCESS("SUCCESS", "성공"), DENIED("DENIED", "권한 거부"), ERROR("ERROR", "오류")
}
