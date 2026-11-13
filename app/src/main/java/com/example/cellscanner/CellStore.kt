package com.example.cellscanner

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Latest scan results, shared between the background service and the UI. */
object CellStore {
    data class Tower(
        val tech: String,        // LTE, 5G, GSM, 3G
        val cellId: String,
        val plmn: String,        // MCC-MNC, identifies the carrier
        val area: String,        // TAC / LAC
        val channel: String,     // PCI / EARFCN etc.
        val dbm: Int?,
        val serving: Boolean,
        val distanceM: Int?      // rough, only for the serving LTE cell
    )

    enum class State { STOPPED, NO_CELLULAR, NO_PERMISSION, SCANNING, IDLE }

    @Volatile var state = State.STOPPED
    @Volatile var towers: List<Tower> = emptyList()
    @Volatile var watchIps: List<String> = emptyList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }
}
