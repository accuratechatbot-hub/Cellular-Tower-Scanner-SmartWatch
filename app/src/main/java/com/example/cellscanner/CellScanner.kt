package com.example.cellscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reads nearby cell towers from the watch's modem and the watch's own cellular IP. */
object CellScanner {

    private val executor = Executors.newSingleThreadExecutor()

    /** Asks the modem for a fresh scan, falling back to the cached list. Throws SecurityException. */
    fun scanOnce(context: Context): List<CellInfo> {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val latch = CountDownLatch(1)
        var fresh: List<CellInfo>? = null
        tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                fresh = cellInfo.toList()
                latch.countDown()
            }

            override fun onError(errorCode: Int, detail: Throwable?) {
                latch.countDown()
            }
        })
        latch.await(8, TimeUnit.SECONDS)
        return fresh ?: tm.allCellInfo ?: emptyList()
    }

    private fun num(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
    private fun plmn(mcc: String?, mnc: String?) = if (mcc == null || mnc == null) "--" else "$mcc-$mnc"
    private fun dbm(v: Int): Int? = if (v == CellInfo.UNAVAILABLE || v > 0) null else v

    fun parse(list: List<CellInfo>): List<CellStore.Tower> {
        val towers = list.mapNotNull { info ->
            when (info) {
                is CellInfoLte -> {
                    val id = info.cellIdentity
                    val ss = info.cellSignalStrength
                    val ta = ss.timingAdvance
                    CellStore.Tower(
                        "LTE", num(id.ci), plmn(id.mccString, id.mncString),
                        "TAC ${num(id.tac)}", "PCI ${num(id.pci)} · EARFCN ${num(id.earfcn)}",
                        dbm(ss.dbm), info.isRegistered,
                        if (info.isRegistered && ta != CellInfo.UNAVAILABLE) ta * 78 else null
                    )
                }
                is CellInfoNr -> {
                    val id = info.cellIdentity as CellIdentityNr
                    val ss = info.cellSignalStrength as CellSignalStrengthNr
                    CellStore.Tower(
                        "5G",
                        if (id.nci == CellInfo.UNAVAILABLE_LONG) "--" else id.nci.toString(),
                        plmn(id.mccString, id.mncString),
                        "TAC ${num(id.tac)}", "PCI ${num(id.pci)} · NRARFCN ${num(id.nrarfcn)}",
                        dbm(ss.dbm), info.isRegistered, null
                    )
                }
                is CellInfoGsm -> {
                    val id = info.cellIdentity
                    CellStore.Tower(
                        "GSM", num(id.cid), plmn(id.mccString, id.mncString),
                        "LAC ${num(id.lac)}", "ARFCN ${num(id.arfcn)}",
                        dbm(info.cellSignalStrength.dbm), info.isRegistered, null
                    )
                }
                is CellInfoWcdma -> {
                    val id = info.cellIdentity
                    CellStore.Tower(
                        "3G", num(id.cid), plmn(id.mccString, id.mncString),
                        "LAC ${num(id.lac)}", "PSC ${num(id.psc)} · UARFCN ${num(id.uarfcn)}",
                        dbm(info.cellSignalStrength.dbm), info.isRegistered, null
                    )
                }
                else -> null
            }
        }
        // Serving tower first, then strongest signal.
        return towers.sortedWith(
            compareByDescending<CellStore.Tower> { it.serving }.thenByDescending { it.dbm ?: -999 }
        )
    }

    /** The watch's own IP address(es) on the cellular network, if it is connected right now. */
    fun cellularIps(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val ips = ArrayList<String>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.forEach { la ->
                val a = la.address
                if (!a.isLinkLocalAddress && !a.isLoopbackAddress) {
                    ips.add(a.hostAddress?.substringBefore('%') ?: return@forEach)
                }
            }
        }
        // IPv4 first
        return ips.sortedBy { it.contains(':') }
    }
}
