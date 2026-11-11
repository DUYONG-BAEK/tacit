package kr.co.snail.tacitq.hmd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kr.co.snail.tacitq.hmd.link.LinkService

/**
 * HMD 부팅이 끝나거나 앱이 업데이트되면 대기 서비스([LinkService], BLE)를 켠다. 화면은 띄우지 않는다(홈 그대로).
 * 폰에서 검사 시작 → BLE 연결 → 서비스가 검사 화면을 앞으로 띄워 카메라를 켠다. HMD 에서 앱을 실행할 필요 없음.
 *
 * 2026-11-11: 전에는 화면(MainActivity)을 뒤에서 띄워 BLE 를 들게 했다 — 화면이 정리되면 BLE 도 사라졌다.
 * 업데이트(MY_PACKAGE_REPLACED)도 받는다 — 전에는 앱을 새로 깔면 재부팅 전까지 대기가 안 됐다.
 * 화면을 띄우려면 "다른 앱 위에 표시" 허용이 필요하다(HMD_설치.bat). 허용 전이면 경고만 남긴다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Settings.canDrawOverlays(context)) Log.w(TAG, "'다른 앱 위에 표시' 허용 필요 — 폰이 연결해도 화면을 띄우지 못함")
        Log.i(TAG, "$action → 대기 서비스 시작 (BLE)")
        LinkService.start(context)
    }

    companion object { private const val TAG = "TacitQ.Boot" }
}
