package kr.co.snail.tacitq.hmd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * HMD 부팅이 끝나면 품질검사 앱을 **뒤에서** 띄워 BLE 를 켜 둔다(화면은 홈 그대로).
 * 폰에서 검사 시작 → BLE 연결 → 앱이 스스로 앞으로 나와 카메라를 켠다. 작업자가 "품질검사"를 말할 필요 없음.
 *
 * Android 10+ 는 뒤에서 화면(Activity)을 띄우는 것을 막는다. 예외 조건인 "다른 앱 위에 표시"(SYSTEM_ALERT_WINDOW)
 * 허용이 있어야 실제로 뜬다. 허용 전이면 로그만 남기고 아무것도 하지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "부팅 자동 실행 불가 — '다른 앱 위에 표시' 허용 필요")
            return
        }
        Log.i(TAG, "부팅 완료 → 품질검사 앱 대기 실행 (BLE 만)")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_BACKGROUND, true)
        )
    }

    companion object { private const val TAG = "TacitQ.Boot" }
}
