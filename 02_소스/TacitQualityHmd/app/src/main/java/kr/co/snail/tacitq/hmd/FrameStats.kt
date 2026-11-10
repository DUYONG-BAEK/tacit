package kr.co.snail.tacitq.hmd

import java.util.Locale

/**
 * HMD 영상 5초 요약 — 어디가 느린지(카메라 · NV21 복사 · JPEG 압축) 가른다. 2026-11-10 송출 초당 약 7장(목표 12) 원인 측정용.
 *
 * 센서 장수는 카메라 캡처 콜백에서 센다 — 분석 스레드가 받은 장수는 변환 시간에 묶여 카메라 실제 속도를 보여 주지 못한다.
 * 카메라 스레드·분석 스레드에서 쌓고 화면 스레드에서 [take] 로 꺼낸다.
 */
class FrameStats(private var since: Long) {

    class Summary(
        val windowMs: Long,
        val sensor: Int, val analyzed: Int, val sent: Int,
        val nv21Ms: Double, val jpegMs: Double, val jpegKb: Int,
        val exposureMs: Double?, val iso: Int?, val ae: String?,
    ) {
        /** 카메라가 돌았거나 프레임이 지나갔으면 true — 앱이 뒤에 있는 동안은 남기지 않는다 */
        val active: Boolean get() = sensor > 0 || analyzed > 0

        private fun rate(n: Int) = f1(n * 1000.0 / windowMs)
        private val busy: Int get() = ((nv21Ms + jpegMs) * sent * 100 / windowMs).toInt()

        fun line(): String =
            "최근 ${f1(windowMs / 1000.0)}초: 센서 ${sensor}장(${rate(sensor)}/초) · 분석 ${analyzed}장 · 송출 ${sent}장(${rate(sent)}/초) · " +
                "NV21 평균 ${f1(nv21Ms)}ms · JPEG 평균 ${f1(jpegMs)}ms · 평균 ${jpegKb}KB · 변환 점유 $busy% · " +
                "노출 ${exposureMs?.let { "${f1(it)}ms" } ?: "-"} · ISO ${iso ?: "-"} · AE ${ae ?: "-"}"

        /** HMD 상태줄용 한 줄 */
        fun short(): String = "센서 ${rate(sensor)}/초 · 송출 ${rate(sent)}/초 · NV21 ${f1(nv21Ms)}ms · JPEG ${f1(jpegMs)}ms"

        private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    }

    private var sensor = 0
    private var analyzed = 0
    private var sent = 0
    private var nv21Ns = 0L
    private var jpegNs = 0L
    private var jpegBytes = 0L
    private var exposureNs: Long? = null
    private var iso: Int? = null
    private var ae: String? = null
    private var aeLower = -1
    private var aeUpper = -1

    /** 카메라가 한 장 찍음 (캡처 콜백). 노출·ISO·AE 범위는 마지막 값을 남긴다. */
    @Synchronized
    fun onSensor(exposureNs: Long?, iso: Int?, aeLower: Int?, aeUpper: Int?) {
        sensor++
        this.exposureNs = exposureNs
        this.iso = iso
        if (aeLower != null && aeUpper != null && (aeLower != this.aeLower || aeUpper != this.aeUpper)) {
            this.aeLower = aeLower; this.aeUpper = aeUpper; ae = "[$aeLower, $aeUpper]"
        }
    }

    /** 분석 스레드가 한 장 받음 (송출 간격으로 버리는 장 포함) */
    @Synchronized
    fun onAnalyzed() { analyzed++ }

    /** JPEG 한 장 송출 */
    @Synchronized
    fun onSent(nv21Ns: Long, jpegNs: Long, bytes: Int) {
        sent++
        this.nv21Ns += nv21Ns
        this.jpegNs += jpegNs
        jpegBytes += bytes
    }

    /** [WINDOW_MS] 가 지났으면 요약을 꺼내고 0 부터 다시 센다. 아직이면 null. */
    @Synchronized
    fun take(now: Long): Summary? {
        val window = now - since
        if (window < WINDOW_MS) return null
        val s = Summary(
            windowMs = window, sensor = sensor, analyzed = analyzed, sent = sent,
            nv21Ms = if (sent > 0) nv21Ns / 1e6 / sent else 0.0,
            jpegMs = if (sent > 0) jpegNs / 1e6 / sent else 0.0,
            jpegKb = if (sent > 0) (jpegBytes / sent / 1024).toInt() else 0,
            exposureMs = exposureNs?.let { it / 1e6 }, iso = iso, ae = ae,
        )
        since = now
        sensor = 0; analyzed = 0; sent = 0; nv21Ns = 0; jpegNs = 0; jpegBytes = 0
        return s
    }

    companion object {
        const val WINDOW_MS = 5_000L
    }
}
