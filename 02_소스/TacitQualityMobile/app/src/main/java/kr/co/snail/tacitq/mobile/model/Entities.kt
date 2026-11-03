package kr.co.snail.tacitq.mobile.model

import kotlinx.serialization.Serializable

/**
 * 테이블 레이아웃 05 v1.2와 1:1 대응하는 도메인 모델.
 * 컬럼명은 스네이크 케이스(DB) → 카멜 케이스(Kotlin)로만 바꾸고 의미는 그대로 둔다.
 * 화면이 필요로 하는 파생값은 프로퍼티로 계산하되 저장하지 않는다.
 */

/** TB_INSPECTION — 부품 1건당 1행 */
@Serializable
data class Inspection(
    val inspectionId: String,
    val capturedAt: String,
    val lineId: String,
    val partNo: String,
    val partId: String,              // 라인별 연속 채번 (A_0031)
    val workerId: String,
    val hmdId: String,
    val mobileId: String,
    val shiftCd: String,
    val captureMode: String = CaptureMode.VIDEO_STREAM.code,
    val streamSessionId: String,
    val videoRef: String? = null,
    val framesReceived: Int,
    val framesSelected: Int,         // 면별 framesEval 합과 일치해야 한다
    val viewCntPlan: Int,
    val viewCntCaptured: Int,
    val earlyExit: Boolean,
    val aiApplied: Boolean = true,
    val aiVerdict: String,
    val verdictFromView: String? = null,
    val aiConfidence: Double,
    val modelVersion: String,
    val profileVersion: String,
    val sequenceMs: Int,
    val finalE2eMs: Int,
    val confirmRequired: Boolean,
    val confirmTrigger: String? = null,
    val workerAction: String,
    val inputMethod: String,
    val gestureRetries: Int = 0,
    val actedBy: String? = null,
    val actedAt: String? = null,
    val dwellMs: Int? = null,
    val lateRecovery: Boolean = false,
    val trayId: String? = null,
    val patternFlag: String? = null,
    val actionId: String? = null,
    val objectionReason: String? = null,
    val objectionHandling: String? = null,
    val resolvedVerdict: String? = null,
    val resolvedBy: String? = null,
    val syncedAt: String? = null
) {
    val verdict: Verdict get() = Verdict.entries.first { it.code == aiVerdict }
    val objected: Boolean get() = workerAction == WorkerAct.OBJECTED.code
    /** 아직 Edge로 못 올라간 건. 교대 마감 때 이 값이 0이어야 한다. */
    val queued: Boolean get() = syncedAt == null
}

/** TB_INSPECTION_VIEW — 스트림에서 잘라낸 면 구간 1개 */
@Serializable
data class InspectionView(
    val viewRowId: String,
    val inspectionId: String,
    val viewId: String,
    val viewSeq: Int,
    val captureSource: String = "HMD_VIDEO",
    val segmentTrigger: String,
    val resolution: String = "1920x1080@60",
    val segmentStartMs: Int,
    val segmentEndMs: Int,
    val dwellMs: Int,
    val rotateMs: Int? = null,
    val framesInSegment: Int,
    val framesEval: Int,
    val dropBlur: Int,
    val dropGlare: Int,
    val dropAngle: Int,
    val dropDuplicate: Int,
    val ensembleMethod: String = "MAX_SEV_MEAN_CONF",
    val agreeRate: Double,
    val qualityScore: Double,
    val qualityFlag: String,
    val inferMs: Int,
    val aiVerdict: String,
    val defectCnt: Int,
    val frameRef: String? = null,
    val skippedRsn: String? = null,
    val createdAt: String
) {
    val dropped: Int get() = dropBlur + dropGlare + dropAngle + dropDuplicate
    val skipped: Boolean get() = skippedRsn != null
    val viewLabel: String get() = ViewCode.entries.first { it.code == viewId }.label
}

/** TB_DEFECT — 면별로 검출된 개별 결함 */
@Serializable
data class Defect(
    val defectId: String,
    val inspectionId: String,
    val viewRowId: String,
    val viewId: String,
    val defectType: String,
    val severity: String,
    val confidence: Double,
    val bboxX: Double, val bboxY: Double, val bboxW: Double, val bboxH: Double,
    val normX: Double? = null, val normY: Double? = null,
    val areaMm2: Double? = null, val lengthMm: Double? = null,
    val locationText: String? = null,
    val seenInFrames: Int,           // M-03 "4장 중 3장"의 분자
    val framesEval: Int,             // 분모
    val frameRef: String? = null,
    val frameOffsetMs: Int? = null,
    val heatmapRef: String? = null,
    val createdAt: String
) {
    val typeLabel: String get() = DefectType.entries.first { it.code == defectType }.label
    val severityLabel: String get() = Severity.entries.first { it.code == severity }.label
    val detectionText: String get() = "${framesEval}장 중 ${seenInFrames}장 검출"
    /** 1~2장만 검출됐으면 오검출 의심 표본으로 따로 모은다. */
    val suspectFalsePositive: Boolean get() = seenInFrames <= 2 && framesEval >= 4
}

/** TB_TRAY — 세트 ID(화면 표기)와 개체 ID(저장 단위)를 함께 갖는다 */
@Serializable
data class Tray(
    val trayId: String,              // T-0913-A-D
    val traySetId: String,           // T-0913-A  ← 화면 표기 단위
    val trayKind: String,
    val lineId: String,
    val partNo: String,
    val shiftCd: String,
    val trayStatus: String,
    val openedAt: String,
    val openedBy: String,
    val closedAt: String? = null,
    val closedBy: String? = null,
    val collectedAt: String? = null,
    val collectedBy: String? = null,
    val sensorCnt: Int,
    val inspectionCnt: Int,
    val countMismatch: Boolean,
    val remark: String? = null
) {
    val kind: TrayKind get() = TrayKind.entries.first { it.code == trayKind }
}

/** TB_DEFECT_ITEM — 수거 후 실물로 확정한 개체 */
@Serializable
data class DefectItem(
    val itemId: String,
    val trayId: String,
    val inspectionId: String,
    val partId: String,
    val confirmedType: String,
    val confirmedSeverity: String,
    val matchesAi: Boolean,
    val estimatedCause: String? = null,
    val objectionDetail: String? = null,
    val recaptureNeeded: Boolean,
    val recaptureRef: String? = null,
    val toTrainset: Boolean,
    val disposition: String,
    val itemStatus: String,
    val registeredBy: String,
    val registeredAt: String,
    val storeUntil: String,
    val storeExtendedCnt: Int = 0,
    val holdReason: String? = null,
    val disposedAt: String? = null,
    val disposedBy: String? = null,
    val disposalNote: String? = null
) {
    val status: ItemStatus get() = ItemStatus.entries.first { it.code == itemStatus }
    val held: Boolean get() = holdReason != null
}

/** TB_EQUIP_ACTION — 감지에서 재개까지 1건 */
@Serializable
data class EquipAction(
    val actionId: String,
    val lineId: String,
    val partNo: String,
    val detectedAt: String,
    val severityLevel: String,
    val defectType: String,
    val windowSize: Int,
    val hitCnt: Int,
    val consecutiveCnt: Int,
    val locationClustered: Boolean,
    val stopRequestedBy: String? = null,
    val stopInputMethod: String? = null,
    val lineStopped: Boolean,
    val stoppedAt: String? = null,
    val approvedBy: String? = null,
    val estimatedCause: String,
    val actionTaken: String,
    val paramName: String? = null,
    val paramBefore: String? = null,
    val paramAfter: String? = null,
    val actionNote: String? = null,
    val actorId: String,
    val verifySample: String? = null,
    val verifyMonitored: Int? = null,
    val verifyRecurrence: Int? = null,
    val verifyResult: String,
    val resumedAt: String? = null,
    val downtimeSec: Int,
    val masterVerdict: String? = null
) {
    val detectionText: String get() =
        "${DefectType.entries.first { it.code == defectType }.label} $hitCnt/$windowSize 건"
    val paramChangeText: String? get() =
        if (paramBefore != null && paramAfter != null) "$paramBefore → $paramAfter" else null
}

/** TB_PART_PROFILE — 배포 없이 반영되는 기준 정보 */
@Serializable
data class PartProfile(
    val profileId: String,
    val partNo: String,
    val partNm: String,
    val lineId: String,
    val viewCnt: Int,
    val viewSeq: String,             // "상면 > 하면 > 측면"
    val rotateGuide: String? = null,
    val targetTypes: String,
    val dentMajorMm2: Double, val dentCritMm2: Double,
    val scratchMajorMm: Double, val scratchCritMm: Double,
    val crackAlwaysCrit: Boolean,
    val reviewLow: Double, val reviewHigh: Double,
    val patternWindow: Int, val patternWarn: Int, val patternAlert: Int, val patternCrit: Int,
    val occupancyMinPct: Int,
    val framesTarget: Int,
    val minDwellMs: Int,
    val agreeReview: Double,
    val earlyExitYn: Boolean,
    val sampleRatePct: Double,
    val storeDays: Int,              // M-04 "보관 30일" 정책값
    val profileVersion: String,
    val useYn: Boolean,
    val updatedAt: String,
    val updatedBy: String
) {
    /** M-01 표기: "3면 (상→하→측)" */
    val viewSummary: String get() =
        "${viewCnt}면 (" + viewSeq.split(" > ").joinToString("→") { it.first().toString() } + ")"
}

/** TB_SHIFT_CHECK — M-01 점검 6항목의 이력. v1.2 신설 */
@Serializable
data class ShiftCheck(
    val checkId: String,
    val shiftDate: String,
    val shiftCd: String,
    val lineId: String,
    val workerId: String,
    val hmdId: String,
    val mobileId: String,
    val partNo: String,
    val checkItem: String,
    val checkResult: String,
    val measuredVal: String? = null,
    val thresholdVal: String? = null,
    val retryCnt: Int = 0,
    val checkedAt: String,
    val startBlocked: Boolean = false,
    val remark: String? = null
) {
    val item: CheckItem get() = CheckItem.entries.first { it.code == checkItem }
    val result: CheckResult get() = CheckResult.entries.first { it.code == checkResult }
}

@Serializable
data class User(
    val userId: String, val userNm: String, val empNo: String, val roleCd: String,
    val lineId: String? = null, val deptNm: String, val phoneNo: String? = null,
    val userStatus: String, val canPlcParam: Boolean, val canDispose: Boolean,
    val lastLoginAt: String? = null, val createdAt: String, val createdBy: String
) {
    val role: Role get() = Role.entries.first { it.code == roleCd }
    val display: String get() = "$userNm ($userId)"
}

@Serializable
data class Device(
    val deviceId: String, val deviceType: String, val modelNm: String, val serialNo: String,
    val lineId: String? = null, val assignedUser: String? = null, val deviceStatus: String,
    val batteryPct: Int? = null, val streamState: String? = null,
    val avgBitrateKbps: Int? = null, val fwVersion: String? = null, val appVersion: String? = null,
    val lastSeenAt: String? = null, val registeredAt: String
) {
    val bitrateMbps: Double get() = (avgBitrateKbps ?: 0) / 1000.0
}

@Serializable
data class AuditLog(
    val logId: String, val occurredAt: String, val userId: String, val roleCd: String,
    val actionCd: String, val targetType: String, val targetId: String,
    val beforeVal: String? = null, val afterVal: String? = null,
    val resultCd: String, val ipAddr: String? = null, val logNote: String? = null
)
