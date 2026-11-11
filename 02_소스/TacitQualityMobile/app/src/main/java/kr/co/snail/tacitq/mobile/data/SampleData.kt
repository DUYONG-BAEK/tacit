package kr.co.snail.tacitq.mobile.data

import kr.co.snail.tacitq.mobile.model.*

/**
 * 화면 검증용 시드 데이터.
 *
 * 값은 05 파일(v1.2) 예시 데이터와 같은 규칙으로 만들었다 —
 * PART_ID는 라인별 연속 채번, 트레이는 세트 ID + 종류 접미사,
 * 대역폭 8.2 Mbps, E2E 중앙값 약 594ms.
 * 실증 전환 시 이 객체를 RemoteQualityRepository로 갈아끼우면 된다.
 */
object SampleData {

    const val TODAY = "2026-09-13"
    const val MODEL_VERSION = "det-1.3.0 / cls-1.2.1"
    const val PROFILE_VERSION = "sev-2026.11"

    val currentUser = User(
        userId = "w_8842", userNm = "길○○", empNo = "OS-20142", roleCd = Role.WORKER.code,
        lineId = Line.A.code, deptNm = "프레스 1과", phoneNo = "010-4417-2206",
        userStatus = "ACTIVE", canPlcParam = false, canDispose = false,
        lastLoginAt = "$TODAY 07:48:00", createdAt = "2026-06-03 09:00:00", createdBy = "a_4410"
    )

    val master = User(
        userId = "m_5510", userNm = "정○○", empNo = "OS-20011", roleCd = Role.MASTER.code,
        lineId = null, deptNm = "품질경영실", phoneNo = null, userStatus = "ACTIVE",
        canPlcParam = false, canDispose = true,
        lastLoginAt = "$TODAY 08:10:00", createdAt = "2026-06-01 09:00:00", createdBy = "a_4410"
    )

    val profile = PartProfile(
        profileId = "prof_PRS-4410_A", partNo = "PRS-4410", partNm = "브라켓 성형품 4410",
        lineId = Line.A.code, viewCnt = 3, viewSeq = "상면 > 하면 > 측면",
        rotateGuide = "하면으로 돌리세요", targetTypes = "DENT,CRACK,SCRATCH",
        dentMajorMm2 = 2.0, dentCritMm2 = 6.0, scratchMajorMm = 5.0, scratchCritMm = 12.0,
        crackAlwaysCrit = true, reviewLow = 0.55, reviewHigh = 0.80,
        patternWindow = 20, patternWarn = 3, patternAlert = 5, patternCrit = 3,
        occupancyMinPct = 35, framesTarget = 4, minDwellMs = 600, agreeReview = 0.75,
        earlyExitYn = true, sampleRatePct = 5.0, storeDays = 30,
        profileVersion = PROFILE_VERSION, useYn = true,
        updatedAt = "2026-08-20 14:00:00", updatedBy = "a_4410"
    )

    val mobile = Device(
        deviceId = "mob-02", deviceType = DeviceType.MOBILE.code,
        modelNm = "Galaxy XCover7", serialNo = "MOB10230074", lineId = Line.A.code,
        assignedUser = "w_8842", deviceStatus = "NORMAL", batteryPct = 86,
        streamState = StreamState.STREAMING.code, avgBitrateKbps = 8200,
        fwVersion = "One UI 6.1", appVersion = "1.0.0",
        lastSeenAt = "$TODAY 07:51:00", registeredAt = "2026-09-01 10:00:00"
    )

    val trays = listOf(
        tray("T-0913-A", TrayKind.GOOD, TrayStatus.OPEN, "2026-09-13 06:05:00", 0, 0),
        tray("T-0913-A", TrayKind.DEFECT, TrayStatus.OPEN, "2026-09-13 06:05:00", 0, 0),
        tray("T-0912-A", TrayKind.DEFECT, TrayStatus.COLLECTED, "2026-09-12 06:05:00", 12, 12),
        tray("T-0906-A", TrayKind.DEFECT, TrayStatus.REGISTERED, "2026-09-06 06:05:00", 8, 8),
        tray("T-0903-A", TrayKind.DEFECT, TrayStatus.REGISTERED, "2026-09-03 06:05:00", 31, 31),
        tray("T-0815-A", TrayKind.DEFECT, TrayStatus.REGISTERED, "2026-08-15 06:05:00", 23, 23)
    )

    private fun tray(setId: String, kind: TrayKind, st: TrayStatus, opened: String,
                     sensor: Int, insp: Int) = Tray(
        trayId = "$setId-${kind.suffix}", traySetId = setId, trayKind = kind.code,
        lineId = Line.A.code, partNo = "PRS-4410", shiftCd = Shift.DAY_1.code,
        trayStatus = st.code, openedAt = opened, openedBy = "w_8842",
        closedAt = if (st == TrayStatus.OPEN) null else opened.replace("06:05", "14:05"),
        closedBy = if (st == TrayStatus.OPEN) null else "w_8842",
        collectedAt = if (st == TrayStatus.OPEN) null else opened.replace("06:05", "08:10"),
        collectedBy = if (st == TrayStatus.OPEN) null else "l_7710",
        sensorCnt = sensor, inspectionCnt = insp, countMismatch = sensor != insp
    )

    /** M-05 타임라인에 쓰이는 오늘치 검사 5건 */
    val inspections = listOf(
        inspection("A_0031", "09:14", Verdict.FAIL, 594, WorkerAct.CONFIRMED, InputMethod.TRAY_SENSOR),
        inspection("A_0030", "09:12", Verdict.FAIL, 612, WorkerAct.OBJECTED, InputMethod.GESTURE),
        inspection("A_0027", "09:06", Verdict.PASS, 588, WorkerAct.AUTO_PASS, InputMethod.TRAY_SENSOR),
        inspection("A_0022", "08:55", Verdict.REVIEW, 640, WorkerAct.REVIEW_DECIDED, InputMethod.GESTURE),
        inspection("A_0019", "08:31", Verdict.PASS, 2610, WorkerAct.CONFIRMED, InputMethod.GESTURE, late = true)
    )

    private fun inspection(
        partId: String, hhmm: String, v: Verdict, e2e: Int,
        act: WorkerAct, input: InputMethod, late: Boolean = false
    ) = Inspection(
        inspectionId = "insp_20260913_$partId", capturedAt = "$TODAY $hhmm:00",
        lineId = Line.A.code, partNo = "PRS-4410", partId = partId,
        workerId = "w_8842", hmdId = "hmd-01", mobileId = "mob-02", shiftCd = Shift.DAY_1.code,
        streamSessionId = "str_20260913_day_1_hmd-01",
        videoRef = "/stream/20260913/$partId.mp4",
        framesReceived = 108, framesSelected = 12, viewCntPlan = 3,
        viewCntCaptured = if (v == Verdict.FAIL) 2 else 3,
        earlyExit = v == Verdict.FAIL,
        aiVerdict = v.code,
        verdictFromView = if (v == Verdict.PASS) null else ViewCode.BOTTOM.code,
        aiConfidence = if (v == Verdict.REVIEW) 0.68 else 0.94,
        modelVersion = MODEL_VERSION, profileVersion = PROFILE_VERSION,
        sequenceMs = 5400, finalE2eMs = e2e,
        confirmRequired = act != WorkerAct.AUTO_PASS,
        confirmTrigger = when (v) {
            Verdict.FAIL -> ConfirmTrigger.FAIL_VERDICT.code
            Verdict.REVIEW -> ConfirmTrigger.REVIEW.code
            Verdict.PASS -> if (act == WorkerAct.AUTO_PASS) null else ConfirmTrigger.RANDOM_SAMPLE.code
        },
        workerAction = act.code, inputMethod = input.code,
        gestureRetries = if (input == InputMethod.GESTURE) 1 else 0,
        actedBy = if (act == WorkerAct.AUTO_PASS) null else "w_8842",
        actedAt = if (act == WorkerAct.AUTO_PASS) null else "$TODAY $hhmm:06",
        dwellMs = 1400, lateRecovery = late,
        trayId = if (v == Verdict.FAIL) "T-0913-A-D" else "T-0913-A-G",
        objectionReason = if (act == WorkerAct.OBJECTED) ObjectionReason.AI_FALSE_POSITIVE.code else null,
        objectionHandling = if (act == WorkerAct.OBJECTED) "HOLD" else null,
        syncedAt = "$TODAY $hhmm:03"
    )

    /** M-03에 표시되는 대표 면 — A_0031의 2면(하면) */
    val representativeView = InspectionView(
        viewRowId = "insp_20260913_A_0031_v2", inspectionId = "insp_20260913_A_0031",
        viewId = ViewCode.BOTTOM.code, viewSeq = 2, segmentTrigger = "ROTATE_DETECT",
        segmentStartMs = 1860, segmentEndMs = 2620, dwellMs = 760, rotateMs = 1180,
        framesInSegment = 45, framesEval = 4,
        dropBlur = 22, dropGlare = 11, dropAngle = 3, dropDuplicate = 5,
        agreeRate = 0.75, qualityScore = 0.81, qualityFlag = QualityFlag.GLARE.code,
        inferMs = 188, aiVerdict = Verdict.FAIL.code, defectCnt = 1,
        frameRef = "/frame/20260913/A_0031_v2_rep.jpg", createdAt = "$TODAY 09:14:02"
    )

    val representativeDefect = Defect(
        defectId = "insp_20260913_A_0031_v2_d01", inspectionId = "insp_20260913_A_0031",
        viewRowId = representativeView.viewRowId, viewId = ViewCode.BOTTOM.code,
        defectType = DefectType.DENT.code, severity = Severity.CRITICAL.code, confidence = 0.91,
        bboxX = 0.68, bboxY = 0.56, bboxW = 0.20, bboxH = 0.26,
        normX = 0.71, normY = 0.58, areaMm2 = 7.4, lengthMm = null,
        locationText = "우하단 찍힘", seenInFrames = 3, framesEval = 4,
        frameRef = representativeView.frameRef, frameOffsetMs = 2140,
        heatmapRef = "/frame/20260913/A_0031_v2_cam.png", createdAt = "$TODAY 09:14:02"
    )

    /** M-04 재고 목록 — 트레이 단위로 집계해 보여준다 */
    val defectItems: List<DefectItem> = buildList {
        addAll(items("T-0815-A-D", 23, "2026-08-16", ItemStatus.DISPOSE_READY, null))
        addAll(items("T-0903-A-D", 31, "2026-09-04", ItemStatus.STORED, null))
        addAll(items("T-0912-A-D", 12, "2026-09-13", ItemStatus.STORED, null))
        addAll(items("T-0906-A-D", 8, "2026-09-07", ItemStatus.HOLD, HoldReason.MASTER_REVIEW))
    }

    private fun items(trayId: String, n: Int, regDate: String, st: ItemStatus, hold: HoldReason?) =
        (1..n).map { i ->
            DefectItem(
                itemId = "item_${trayId.replace("-", "")}_%03d".format(i),
                trayId = trayId, inspectionId = "insp_20260913_A_%04d".format(i),
                partId = "A_%04d".format(i),
                confirmedType = DefectType.DENT.code,
                confirmedSeverity = if (i % 3 == 0) Severity.MAJOR.code else Severity.CRITICAL.code,
                matchesAi = i % 7 != 0,
                estimatedCause = Cause.MOLD_DEBRIS.code,
                objectionDetail = null, recaptureNeeded = i % 9 == 0, recaptureRef = null,
                toTrainset = true, disposition = Disposition.SCRAP.code, itemStatus = st.code,
                registeredBy = "l_7710", registeredAt = "$regDate 08:30:00",
                storeUntil = plusDays(regDate, profile.storeDays),
                storeExtendedCnt = 0, holdReason = hold?.code
            )
        }

    /** M-02에 뜨는 진행 중 설비 조치 */
    val equipAction = EquipAction(
        actionId = "act_20260913_A_01", lineId = Line.A.code, partNo = "PRS-4410",
        detectedAt = "$TODAY 08:42:00", severityLevel = PatternLevel.ALERT.code,
        defectType = DefectType.DENT.code, windowSize = 20, hitCnt = 5, consecutiveCnt = 3,
        locationClustered = true, stopRequestedBy = "w_8842", stopInputMethod = "HEAD_CURSOR",
        lineStopped = true, stoppedAt = "$TODAY 08:44:00", approvedBy = "l_7710",
        estimatedCause = Cause.MOLD_DEBRIS.code, actionTaken = ActionTaken.MOLD_CLEANING.code,
        paramName = "오일 도포 시간", paramBefore = "420ms", paramAfter = "480ms",
        actionNote = "금형 3번 캐비티 이물 제거", actorId = "e_6610",
        verifySample = null, verifyMonitored = null, verifyRecurrence = null,
        verifyResult = VerifyResult.PENDING.code, resumedAt = null, downtimeSec = 1080
    )

    /** M-05 요약 — 교대 누적치 (247건 기준) */
    const val SHIFT_TOTAL = 247
    const val SHIFT_FAIL = 3
    const val SHIFT_OBJECTION = 2
    const val SHIFT_REVIEW = 8

    private fun plusDays(date: String, days: Int): String {
        val (y, m, d) = date.split("-").map { it.toInt() }
        val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var yy = y; var mm = m; var dd = d + days
        while (dd > dim[mm - 1]) { dd -= dim[mm - 1]; mm++; if (mm > 12) { mm = 1; yy++ } }
        return "%04d-%02d-%02d".format(yy, mm, dd)
    }
}
