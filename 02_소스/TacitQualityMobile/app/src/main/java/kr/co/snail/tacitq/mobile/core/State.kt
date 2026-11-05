package kr.co.snail.tacitq.mobile.core

import kr.co.snail.tacitq.mobile.model.Verdict

/**
 * C3 상태 기계의 상태 정의 — 구조설계서 §2 "두 층의 상태".
 *
 * 이 패키지(core)는 **안드로이드를 몰라야 한다.** android.* 를 한 줄이라도 참조하면
 * 그 순간 노트북에서 시험할 수 없게 되고 V-M05(순서·중복·지연 시험)가 불가능해진다.
 * 입력은 [Event], 출력은 (새 [AppState], [Effect] 목록) — 그 외에 아무것도 하지 않는다.
 *
 * Verdict 는 코드 정의서(model.Codes)의 단일 정의를 재사용한다. 판정 어휘가 두 벌이 되면
 * 화면·저장·상태 기계가 조용히 어긋난다.
 */

/** 앱 전체 상태 — 구조설계서 §2 상위 층 */
enum class Mode { INIT, READY, RUNNING, DEGRADED, OFFLINE, LINE_STOPPED, CLOSED }

/** 검사 건별 상태 — 구조설계서 §2 하위 층 */
enum class PartPhase { CAPTURING, PENDING, JUDGED, CONFIRMED, OBJECTED, TIMEOUT, RECOVERED }

/** 검사 1건(부품 1개)의 상태 */
data class Part(
    val id: String,                       // A_0248
    val phase: PartPhase,
    val verdict: Verdict? = null,
    val severity: String? = null,
    val confidence: Double? = null,
    val agreeRate: Double? = null,        // v0.7 — 프레임 간 일치율
    val judgedAt: Long? = null,
    val trayDetectedAt: Long? = null,     // 판정보다 먼저 올 수 있다 (R05·R08)
    val sampled: Boolean = false,         // 랜덤 표본 당첨 (R02)
)

/**
 * 품번 프로파일에서 내려오는 값 — 하드코딩 금지 (TB_PART_PROFILE).
 * 타이머 값들은 구조설계서 §2 "타이머 목록"과 1:1 대응한다. 실측 후 재조정.
 */
data class Profile(
    val viewCount: Int = 3,
    val framesTarget: Int = 4,
    val agreeReview: Double = 0.75,       // 이 값 미만이면 REVIEW (R04)
    val sampleRatePct: Double = 3.0,      // 랜덤 표본 비율 (R02)
    val lateMs: Long = 1_500,             // 결과 지연 경계 → 회수 (R05)
    val confirmWaitMs: Long = 10_000,     // 확인 대기 (R12)
    val commitGraceMs: Long = 1_000,      // 제스처 확정 유예 (R09)
    // ── AI 서버 프레임 단위 판정(2026-11-03 규격) — 출발값, 현장 시험으로 조정 ──
    val confirmWindow: Int = 5,           // 결함 확정: 최근 confirmWindow 장 중
    val confirmHits: Int = 2,             //   confirmHits 장 이상이 NG·REVIEW 면 확정
    val segmentEndMs: Long = 1_000,       // 마지막 inspect 뒤 이만큼 inspect 가 없으면 제품 구간 끝
    val markHoldMs: Long = 500,           // 결함 표시 유지 — HMD LiveHold 와 같은 값
    val liveMarks: Boolean = true,        // HMD 에 결함 원(상자)을 보낸다 — 끄면 판정 글자만
    // 앱 쪽 보수 기준(InspectFilter) — 가모델 오탐이 시험을 방해해 서버 결과를 더 엄격하게 본다. 1.0·0.0 이면 서버 그대로
    val pdMinConf: Double = 0.5,          // 제품 상자 신뢰도 하한 — 미만은 제품 없음으로
    val qiFactor: Double = 1.3,           // 결함 기준값 = 서버 threshold × qiFactor
)

/** 앱 전체 상태. 이 객체 하나가 곧 진실이다. */
data class AppState(
    val mode: Mode = Mode.INIT,
    val line: String = "",
    val partNo: String = "",
    val profile: Profile = Profile(),
    val parts: Map<String, Part> = emptyMap(),
    val lastSeq: Long = 0,                // R06 — 처리한 마지막 판정 순번
    val handled: Set<String> = emptySet(),// R07 — 이미 처리한 건 (멱등)
    val doneCount: Int = 0,
    val openWindowId: String? = null,     // 지금 열어둔 제스처 창
)

/** HMD HELLO 메시지가 싣고 오는 단말 정보 */
data class DeviceInfo(
    val deviceId: String,
    val fw: String = "",
    val battery: Int = 100,
    val storageFreeMb: Int = 0,
)
