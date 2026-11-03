package kr.co.snail.tacitq.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TacitQApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RELAY, getString(R.string.channel_relay),
                NotificationManager.IMPORTANCE_LOW)
        )
    }
    companion object { const val CHANNEL_RELAY = "relay" }
}
