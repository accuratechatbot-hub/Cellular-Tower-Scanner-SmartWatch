package com.example.cellscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder

/** Foreground service that keeps scanning for nearby cell towers. */
class CellService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            running = true
            worker = Thread({ loop() }, "cell-scan").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        CellStore.state = CellStore.State.STOPPED
        CellStore.changed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        try {
            while (running) {
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                    CellStore.state = CellStore.State.NO_CELLULAR
                    CellStore.changed()
                    pause(10_000)
                    continue
                }
                CellStore.state = CellStore.State.SCANNING
                CellStore.changed()
                try {
                    val towers = CellScanner.parse(CellScanner.scanOnce(this))
                    if (!running) break
                    CellStore.towers = towers
                    CellStore.watchIps = CellScanner.cellularIps(this)
                    CellStore.state = CellStore.State.IDLE
                } catch (e: SecurityException) {
                    CellStore.state = CellStore.State.NO_PERMISSION
                }
                CellStore.changed()
                pause(SCAN_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            // service is stopping
        }
    }

    private fun pause(ms: Long) {
        var left = ms
        while (running && left > 0) {
            Thread.sleep(minOf(500L, left))
            left -= 500L
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cell scanning", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cell)
            .setContentTitle("Encompass")
            .setContentText("Scanning nearby cell towers")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 10_000L
        private const val CHANNEL_ID = "cell_scan"
        private const val NOTIFICATION_ID = 1
    }
}
