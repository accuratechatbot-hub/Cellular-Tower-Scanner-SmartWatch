package com.example.cellscanner

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private lateinit var textCount: TextView
    private lateinit var textInfo: TextView
    private lateinit var buttonToggle: Button
    private lateinit var listContainer: LinearLayout
    private lateinit var dotLive: View

    private var rendered: List<CellStore.Tower>? = null
    private val listener: () -> Unit = { refresh() }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScanning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)
        textCount = findViewById(R.id.textCount)
        textInfo = findViewById(R.id.textInfo)
        buttonToggle = findViewById(R.id.buttonToggle)
        listContainer = findViewById(R.id.listContainer)
        dotLive = findViewById(R.id.dotLive)

        buttonToggle.setOnClickListener {
            if (CellStore.state == CellStore.State.STOPPED) requestAccess() else stopScanning()
        }

        ObjectAnimator.ofFloat(dotLive, View.ALPHA, 1f, 0.2f).apply {
            duration = 900
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        if (savedInstanceState == null) requestAccess()
    }

    override fun onStart() {
        super.onStart()
        CellStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        CellStore.removeListener(listener)
        super.onStop()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { !granted(it) }
        if (missing.isEmpty()) startScanning() else permissions.launch(missing.toTypedArray())
    }

    private fun startScanning() {
        // Cell scanning is location data, so the service only starts with location access.
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ContextCompat.startForegroundService(this, Intent(this, CellService::class.java))
        } else {
            CellStore.state = CellStore.State.NO_PERMISSION
            refresh()
        }
    }

    private fun stopScanning() {
        stopService(Intent(this, CellService::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val state = CellStore.state
        textStatus.text = when (state) {
            CellStore.State.STOPPED -> "STOPPED"
            CellStore.State.NO_CELLULAR -> "NO CELLULAR"
            CellStore.State.NO_PERMISSION -> "NEEDS LOCATION"
            CellStore.State.SCANNING -> "SCANNING"
            CellStore.State.IDLE -> "LIVE"
        }
        buttonToggle.text = if (state == CellStore.State.STOPPED) "START SCAN" else "STOP"

        val towers = CellStore.towers
        textCount.text = if (towers.isEmpty()) "--" else towers.size.toString()
        textInfo.text = when {
            state == CellStore.State.NO_CELLULAR -> "This watch has no cellular radio (needs an LTE model)"
            state == CellStore.State.NO_PERMISSION -> "Tap START SCAN and allow location access"
            CellStore.watchIps.isNotEmpty() -> "WATCH IP " + CellStore.watchIps.joinToString("  ")
            state == CellStore.State.IDLE -> "WATCH IP: not on cellular right now"
            else -> ""
        }

        if (towers !== rendered) {
            rendered = towers
            listContainer.removeAllViews()
            towers.forEach { addRow(it) }
        }
    }

    private fun addRow(t: CellStore.Tower) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        row.addView(TextView(this).apply {
            text = "${t.tech}  ${t.cellId}"
            typeface = Typeface.create("monospace", Typeface.BOLD)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_400))
        })
        val dbmText = t.dbm?.let { "$it dBm" } ?: "-- dBm"
        val line = buildList {
            if (t.serving) add("SERVING")
            add(dbmText)
            add(t.plmn)
            add(t.area)
            add(t.channel)
            t.distanceM?.let { add("~$it m") }
        }
        row.addView(TextView(this).apply {
            text = line.joinToString(" · ")
            textSize = 9f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity, if (t.serving) R.color.green_500 else R.color.gray_400
                )
            )
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
        listContainer.addView(row, lp)
    }
}
