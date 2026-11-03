package kr.co.snail.tacitq.mobile.relay

import android.content.Context

/**
 * 개발용 연결 설정 — HMD 영상 주소 · 엣지 종류 · 엣지 주소. 기기에 저장한다.
 * 엣지 종류: [EDGE_MOCK] 모의 엣지(WebSocket) / [EDGE_AI] 실제 AI 서버(WebRTC, 설계서 §9).
 */
class DevSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dev_connection", Context.MODE_PRIVATE)

    var hmdUrl: String
        get() = prefs.getString(KEY_HMD, DEFAULT_HMD)!!
        set(v) = prefs.edit().putString(KEY_HMD, v.trim()).apply()

    var edgeUrl: String
        get() = prefs.getString(KEY_EDGE, DEFAULT_EDGE)!!
        set(v) = prefs.edit().putString(KEY_EDGE, v.trim()).apply()

    var edgeKind: String
        get() = prefs.getString(KEY_KIND, EDGE_AI)!!
        set(v) = prefs.edit().putString(KEY_KIND, v).apply()

    var aiUrl: String
        get() = prefs.getString(KEY_AI, DEFAULT_AI)!!
        set(v) = prefs.edit().putString(KEY_AI, v.trim()).apply()

    /** 노트북 모의 AI 서버 — 사무실 AI 서버가 응답하지 않을 때(사내망 밖) 자동으로 쓴다 */
    var mockAiUrl: String
        get() = prefs.getString(KEY_MOCK_AI, "")!!
        set(v) = prefs.edit().putString(KEY_MOCK_AI, v.trim()).apply()

    /** 판정 화면 전환 — true 자동(시간 뒤 카메라) / false 수동(HMD 버튼) */
    var autoDismiss: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISMISS, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_DISMISS, v).apply()

    var stationId: String
        get() = prefs.getString(KEY_STATION, DEFAULT_STATION)!!
        set(v) = prefs.edit().putString(KEY_STATION, v.trim()).apply()

    companion object {
        private const val KEY_HMD = "hmd_url"
        private const val KEY_EDGE = "edge_url"
        private const val KEY_KIND = "edge_kind"
        private const val KEY_AI = "ai_url"
        private const val KEY_STATION = "station_id"
        private const val KEY_MOCK_AI = "mock_ai_url"
        private const val KEY_AUTO_DISMISS = "auto_dismiss"
        const val EDGE_MOCK = "mock"
        const val EDGE_AI = "ai"
        const val DEFAULT_HMD = "http://172.20.10.3:8080"
        const val DEFAULT_EDGE = "ws://172.20.10.2:8765/v1/stream"
        const val DEFAULT_AI = "http://192.168.1.11:8080"
        const val DEFAULT_STATION = "ST-02"
    }
}
