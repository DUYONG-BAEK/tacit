package kr.co.snail.tacitq.mobile.relay

import android.content.Context
import android.os.Build
import android.provider.Settings
import kr.co.snail.tacitq.mobile.ai.FeedbackMode

/**
 * 연결 설정 — HMD 영상 주소 · AI 서버 주소 · 스테이션 등. 기기에 저장한다.
 * 2026-11-11: 모의 엣지(WebSocket) 선택지 제거 — AI 서버(WebRTC, 설계서 §3)만 쓴다.
 */
class DevSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("dev_connection", Context.MODE_PRIVATE)

    var hmdUrl: String
        get() = prefs.getString(KEY_HMD, DEFAULT_HMD)!!
        set(v) = prefs.edit().putString(KEY_HMD, v.trim()).apply()

    var aiUrl: String
        get() = prefs.getString(KEY_AI, DEFAULT_AI)!!
        set(v) = prefs.edit().putString(KEY_AI, v.trim()).apply()

    /** HMD 결함 원 — true 판정 글자 + 결함 위치 원 / false 판정 글자만(표시 지연이 클 때) */
    var liveMarks: Boolean
        get() = prefs.getBoolean(KEY_LIVE_MARKS, true)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_MARKS, v).apply()

    /** 피드백 모드 — [FeedbackMode.MANUAL] 제품마다 폰에서 맞음·틀림·확인필요 / [FeedbackMode.AUTO] 피드백 건너뜀 */
    var feedbackMode: String
        get() = prefs.getString(KEY_FEEDBACK_MODE, FeedbackMode.MANUAL)!!
        set(v) = prefs.edit().putString(KEY_FEEDBACK_MODE, v).apply()

    /** 피드백 변경 기록에 남길 기기 이름(인증 아님) — 모델명 + ANDROID_ID 앞 6자리 */
    val deviceLabel: String
        get() = Build.MODEL + "-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "").take(6)

    var stationId: String
        get() = prefs.getString(KEY_STATION, DEFAULT_STATION)!!
        set(v) = prefs.edit().putString(KEY_STATION, v.trim()).apply()

    companion object {
        private const val KEY_HMD = "hmd_url"
        private const val KEY_AI = "ai_url"
        private const val KEY_STATION = "station_id"
        private const val KEY_LIVE_MARKS = "live_marks"
        private const val KEY_FEEDBACK_MODE = "feedback_mode"
        const val DEFAULT_HMD = "http://172.20.10.3:8080"
        const val DEFAULT_AI = "http://192.168.1.11:8080"
        const val DEFAULT_STATION = "ST-02"
    }
}
