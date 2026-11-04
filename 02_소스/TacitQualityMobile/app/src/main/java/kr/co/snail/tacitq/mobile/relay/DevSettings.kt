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

    /** HMD 결함 원 — true 판정 글자 + 결함 위치 원 / false 판정 글자만(표시 지연이 클 때) */
    var liveMarks: Boolean
        get() = prefs.getBoolean(KEY_LIVE_MARKS, true)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_MARKS, v).apply()

    var stationId: String
        get() = prefs.getString(KEY_STATION, DEFAULT_STATION)!!
        set(v) = prefs.edit().putString(KEY_STATION, v.trim()).apply()

    companion object {
        private const val KEY_HMD = "hmd_url"
        private const val KEY_EDGE = "edge_url"
        private const val KEY_KIND = "edge_kind"
        private const val KEY_AI = "ai_url"
        private const val KEY_STATION = "station_id"
        private const val KEY_LIVE_MARKS = "live_marks"
        const val EDGE_MOCK = "mock"
        const val EDGE_AI = "ai"
        const val DEFAULT_HMD = "http://172.20.10.3:8080"
        const val DEFAULT_EDGE = "ws://172.20.10.2:8765/v1/stream"
        const val DEFAULT_AI = "http://192.168.1.11:8080"
        const val DEFAULT_STATION = "ST-02"
    }
}
