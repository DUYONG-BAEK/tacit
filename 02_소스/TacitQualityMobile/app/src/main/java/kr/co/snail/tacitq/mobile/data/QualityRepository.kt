package kr.co.snail.tacitq.mobile.data

import kr.co.snail.tacitq.mobile.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 데이터 접근 경계.
 *
 * 지금은 메모리 구현만 있지만, 실증에서는 Room(로컬 큐) + Retrofit(Edge)로
 * 갈아끼운다. 화면은 이 인터페이스만 알면 되므로 교체해도 UI는 건드리지 않는다.
 */
interface QualityRepository {
    val shiftChecks: StateFlow<List<ShiftCheck>>
    val trays: StateFlow<List<Tray>>
    val inspections: StateFlow<List<Inspection>>
    val defectItems: StateFlow<List<DefectItem>>
    val equipActions: StateFlow<List<EquipAction>>
    val auditLogs: StateFlow<List<AuditLog>>

    fun profile(): PartProfile
    fun hmd(): Device
    fun mobile(): Device
    fun me(): User

    /** M-01 — 점검 항목 재시도 */
    fun retryCheck(item: CheckItem, result: CheckResult, measured: String)

    /** M-02 — 조치 등록. 파라미터 전/후는 이 화면의 핵심 산출물이다. */
    fun registerAction(
        actionId: String, cause: Cause, action: ActionTaken,
        paramBefore: String?, paramAfter: String?, note: String?
    ): Result<Unit>

    /** M-02 — 검증 시작 */
    fun startVerification(actionId: String)

    /** M-03 — 실물 재확인 결과와 처리 확정 */
    fun registerItem(
        itemId: String, matchesAi: Boolean, confirmedType: DefectType,
        confirmedSeverity: Severity, recaptureNeeded: Boolean,
        disposition: Disposition, objectionDetail: String?
    )

    /** M-04 — 폐기 승인. 권한 없으면 감사 로그에 DENIED로 남는다. */
    fun approveDisposal(traySetId: String, by: User): Result<Int>

    /** M-04 — 보관기한 연장 */
    fun extendStorage(traySetId: String, days: Int, by: User): Result<Int>

    /** M-05 — 데이터 반출 */
    fun exportHistory(by: User, rowCount: Int): Result<Unit>
}

class InMemoryQualityRepository : QualityRepository {

    private val _shiftChecks = MutableStateFlow(SampleData.shiftChecks)
    private val _trays = MutableStateFlow(SampleData.trays)
    private val _inspections = MutableStateFlow(SampleData.inspections)
    private val _items = MutableStateFlow(SampleData.defectItems)
    private val _actions = MutableStateFlow(listOf(SampleData.equipAction))
    private val _audit = MutableStateFlow(emptyList<AuditLog>())
    private var logSeq = 0

    override val shiftChecks: StateFlow<List<ShiftCheck>> = _shiftChecks
    override val trays: StateFlow<List<Tray>> = _trays
    override val inspections: StateFlow<List<Inspection>> = _inspections
    override val defectItems: StateFlow<List<DefectItem>> = _items
    override val equipActions: StateFlow<List<EquipAction>> = _actions
    override val auditLogs: StateFlow<List<AuditLog>> = _audit

    override fun profile() = SampleData.profile
    override fun hmd() = SampleData.hmd
    override fun mobile() = SampleData.mobile
    override fun me() = SampleData.currentUser

    override fun retryCheck(item: CheckItem, result: CheckResult, measured: String) {
        _shiftChecks.update { list ->
            list.map {
                if (it.checkItem == item.code)
                    it.copy(
                        checkResult = result.code, measuredVal = measured,
                        retryCnt = it.retryCnt + 1,
                        startBlocked = result == CheckResult.FAIL
                    )
                else it
            }
        }
    }

    override fun registerAction(
        actionId: String, cause: Cause, action: ActionTaken,
        paramBefore: String?, paramAfter: String?, note: String?
    ): Result<Unit> {
        // 기타 조치는 메모 없이 등록할 수 없다 — 조치 지식이 남지 않기 때문
        if (action == ActionTaken.OTHER && note.isNullOrBlank())
            return Result.failure(IllegalArgumentException("기타 조치는 메모가 필요합니다"))
        _actions.update { list ->
            list.map {
                if (it.actionId == actionId)
                    it.copy(
                        estimatedCause = cause.code, actionTaken = action.code,
                        paramName = action.paramName, paramBefore = paramBefore,
                        paramAfter = paramAfter, actionNote = note
                    )
                else it
            }
        }
        if (paramBefore != null && paramAfter != null) {
            audit(AuditAction.PLC_PARAM_CHANGE, "PLC", action.paramName ?: "-",
                paramBefore, paramAfter, ResultCd.SUCCESS)
        }
        return Result.success(Unit)
    }

    override fun startVerification(actionId: String) {
        _actions.update { list ->
            list.map {
                if (it.actionId == actionId) it.copy(verifyResult = VerifyResult.PENDING.code,
                    verifyMonitored = 0, verifyRecurrence = 0) else it
            }
        }
    }

    override fun registerItem(
        itemId: String, matchesAi: Boolean, confirmedType: DefectType,
        confirmedSeverity: Severity, recaptureNeeded: Boolean,
        disposition: Disposition, objectionDetail: String?
    ) {
        _items.update { list ->
            list.map {
                if (it.itemId == itemId)
                    it.copy(
                        matchesAi = matchesAi,
                        confirmedType = confirmedType.code,
                        confirmedSeverity = confirmedSeverity.code,
                        recaptureNeeded = recaptureNeeded,
                        disposition = disposition.code,
                        objectionDetail = objectionDetail,
                        // 재촬영이 필요하면 폐기 대상에서 빼고 보류로 돌린다
                        holdReason = if (recaptureNeeded) HoldReason.RECAPTURE.code else it.holdReason,
                        itemStatus = if (recaptureNeeded) ItemStatus.HOLD.code else it.itemStatus,
                        toTrainset = matchesAi
                    )
                else it
            }
        }
    }

    override fun approveDisposal(traySetId: String, by: User): Result<Int> {
        if (!by.canDispose) {
            audit(AuditAction.DISPOSAL_APPROVE, "TB_DEFECT_ITEM", traySetId,
                null, null, ResultCd.DENIED, by)
            return Result.failure(SecurityException("폐기 승인 권한이 없습니다 (명장·관리자만 가능)"))
        }
        var n = 0
        _items.update { list ->
            list.map {
                // 보류가 걸린 개체는 기한이 지나도 폐기하지 않는다
                if (it.trayId.startsWith(traySetId) && it.holdReason == null &&
                    it.itemStatus == ItemStatus.DISPOSE_READY.code
                ) {
                    n++
                    it.copy(
                        itemStatus = ItemStatus.DISPOSED.code,
                        disposedAt = "${SampleData.TODAY} 09:25:00",
                        disposedBy = by.userId, disposalNote = "보관 기한 만료 · ${by.role.label} 승인"
                    )
                } else it
            }
        }
        audit(AuditAction.DISPOSAL_APPROVE, "TB_DEFECT_ITEM", traySetId,
            ItemStatus.DISPOSE_READY.label, "${ItemStatus.DISPOSED.label} ${n}건",
            ResultCd.SUCCESS, by)
        return Result.success(n)
    }

    override fun extendStorage(traySetId: String, days: Int, by: User): Result<Int> {
        if (!by.canDispose) {
            audit(AuditAction.STORE_EXTEND, "TB_DEFECT_ITEM", traySetId, null, null, ResultCd.DENIED, by)
            return Result.failure(SecurityException("보관기한 연장 권한이 없습니다"))
        }
        var before: String? = null; var after: String? = null; var n = 0
        _items.update { list ->
            list.map {
                if (it.trayId.startsWith(traySetId)) {
                    n++
                    before = before ?: it.storeUntil
                    val next = shiftDate(it.storeUntil, days)
                    after = next
                    it.copy(
                        storeUntil = next, storeExtendedCnt = it.storeExtendedCnt + 1,
                        itemStatus = if (it.itemStatus == ItemStatus.DISPOSE_READY.code)
                            ItemStatus.STORED.code else it.itemStatus
                    )
                } else it
            }
        }
        audit(AuditAction.STORE_EXTEND, "TB_DEFECT_ITEM", traySetId, before, after, ResultCd.SUCCESS, by)
        return Result.success(n)
    }

    override fun exportHistory(by: User, rowCount: Int): Result<Unit> {
        // 연구원·관리자만 반출할 수 있다. 반출은 예외 없이 감사 대상이다.
        val allowed = by.role == Role.RESEARCHER || by.role == Role.ADMIN
        audit(
            AuditAction.EXPORT, "TB_INSPECTION", "검사 이력", null, "${rowCount}건 CSV",
            if (allowed) ResultCd.SUCCESS else ResultCd.DENIED, by
        )
        return if (allowed) Result.success(Unit)
        else Result.failure(SecurityException("데이터 반출 권한이 없습니다"))
    }

    private fun audit(
        action: AuditAction, targetType: String, targetId: String,
        before: String?, after: String?, result: ResultCd, by: User = me()
    ) {
        logSeq++
        _audit.update {
            it + AuditLog(
                logId = "log_%06d".format(logSeq),
                occurredAt = "${SampleData.TODAY} 09:%02d:00".format(20 + logSeq),
                userId = by.userId, roleCd = by.roleCd, actionCd = action.code,
                targetType = targetType, targetId = targetId,
                beforeVal = before, afterVal = after, resultCd = result.code,
                ipAddr = "10.42.1.34",
                logNote = if (result == ResultCd.DENIED) "권한 없음 — 요청 거부" else null
            )
        }
    }

    private fun shiftDate(date: String, days: Int): String {
        val (y, m, d) = date.split("-").map { it.toInt() }
        val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var yy = y; var mm = m; var dd = d + days
        while (dd > dim[mm - 1]) { dd -= dim[mm - 1]; mm++; if (mm > 12) { mm = 1; yy++ } }
        return "%04d-%02d-%02d".format(yy, mm, dd)
    }
}
