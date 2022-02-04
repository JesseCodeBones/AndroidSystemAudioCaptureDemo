package com.stockhome.app.audiorecorder.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.stockhome.app.audiorecorder.MainActivity

class MediaRecorderService  : Service() {

    companion object {
        private const val CHANNEL_ID = "ForegroundServiceChannel"
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        createNotificationChannel()
        Intent(this, MainActivity::class.java).also {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, it, 0
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText("Call Recording Service")
                .setContentIntent(pendingIntent)
                .build()
            startForeground(1, notification)
            return START_NOT_STICKY
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(){
        NotificationChannel(CHANNEL_ID, "Foreground Service ID", NotificationManager.IMPORTANCE_DEFAULT).also {
            getSystemService(NotificationManager::class.java)!!.createNotificationChannel(it)
        }
    }
}