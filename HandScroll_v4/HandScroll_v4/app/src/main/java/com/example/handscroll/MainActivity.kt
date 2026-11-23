package com.example.handscroll

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.handscroll.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var downloading = false
    private var progress = 0

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateUi() }

    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            b.root.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.btnToggle.setOnClickListener { onMainButton() }
        b.btnInstagram.setOnClickListener { openInstagram() }

        b.sliderSens.value = prefs.sensitivity.toFloat()
        b.sliderDelay.value = prefs.delayUnits.toFloat()
        b.sliderSleep.value = prefs.sleepUnits.toFloat()
        b.swPalm.isChecked = prefs.requireOpenPalm
        b.swFist.isChecked = prefs.fistPlayPause
        b.swThumb.isChecked = prefs.thumbLike
        b.swHaptic.isChecked = prefs.haptics
        b.swAll.isChecked = prefs.allApps
        showSettingLabels()

        b.sliderSens.addOnChangeListener { _, v, _ -> prefs.sensitivity = v.toInt(); showSettingLabels() }
        b.sliderDelay.addOnChangeListener { _, v, _ -> prefs.delayUnits = v.toInt(); showSettingLabels() }
        b.sliderSleep.addOnChangeListener { _, v, _ -> prefs.sleepUnits = v.toInt(); showSettingLabels() }
        b.swPalm.setOnCheckedChangeListener { _, c -> prefs.requireOpenPalm = c }
        b.swFist.setOnCheckedChangeListener { _, c -> prefs.fistPlayPause = c }
        b.swThumb.setOnCheckedChangeListener { _, c -> prefs.thumbLike = c }
        b.swHaptic.setOnCheckedChangeListener { _, c -> prefs.haptics = c }
        b.swAll.setOnCheckedChangeListener { _, c -> prefs.allApps = c }
    }

    override fun onResume() {
        super.onResume()
        b.root.removeCallbacks(ticker)
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        b.root.removeCallbacks(ticker)
    }

    private fun showSettingLabels() {
        b.tvSens.text = "Sensitivity: ${prefs.sensitivity}/10"
        b.tvDelay.text = "Delay between actions: %.1f s".format(prefs.delayUnits / 10f)
        b.tvSleep.text = if (prefs.sleepUnits == 0) "Auto-stop timer: Off"
        else "Auto-stop after ${prefs.sleepUnits * 5} min"
    }

    private fun hasCamera() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, SwipeAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    /** One button that always does the next thing that is needed. */
    private fun onMainButton() {
        when {
            GestureService.running -> stopService(Intent(this, GestureService::class.java))
            !hasCamera() -> {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(perms.toTypedArray())
            }
            !isAccessibilityOn() -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            !ModelManager.isAvailable(this) -> downloadModel()
            else -> {
                startForegroundService(Intent(this, GestureService::class.java))
                Toast.makeText(this, "Started — scroll Reels with your hand ✋", Toast.LENGTH_SHORT).show()
                openInstagram()
            }
        }
        b.root.postDelayed({ updateUi() }, 400)
    }

    private fun openInstagram() {
        val i = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (i != null) startActivity(i)
        else Toast.makeText(this, "Instagram is not installed", Toast.LENGTH_SHORT).show()
    }

    private fun tick(ok: Boolean) = if (ok) "✅" else "⬜"

    private fun updateUi() {
        val cam = hasCamera()
        val acc = isAccessibilityOn()
        val model = ModelManager.isAvailable(this)
        val running = GestureService.running

        b.tvCamera.text = tick(cam) + "  Camera permission"
        b.tvAccess.text = tick(acc) + "  Accessibility service"
        b.tvModel.text = tick(model) + "  Hand-tracking model"

        b.btnToggle.isEnabled = !downloading
        b.btnToggle.text = when {
            running -> "STOP"
            downloading -> "DOWNLOADING… $progress%"
            !cam -> "STEP 1 · ALLOW CAMERA"
            !acc -> "STEP 2 · TURN ON ACCESSIBILITY"
            !model -> "STEP 3 · DOWNLOAD MODEL"
            else -> "START"
        }
        b.tvStatus.text = when {
            running -> "Running ✅  Open Instagram Reels and wave your hand."
            !cam -> "Tap the button and allow the camera."
            !acc -> "Open “Installed apps”, tap “Hand Scroll” and switch it ON. If it is greyed out: Settings → Apps → Hand Scroll → ⋮ → Allow restricted settings."
            !model -> "One-time download (about 8 MB). Needs internet."
            else -> "All set. Tap START."
        }

        val last = GestureService.lastEvent
        b.tvLast.text = if (last.isEmpty()) "Last gesture: –" else {
            val sec = (SystemClock.elapsedRealtime() - GestureService.lastEventAt) / 1000
            "Last gesture: $last  (${sec}s ago)"
        }
        b.tvStats.text = "Reels scrolled today: ${prefs.countToday()}"
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        progress = 0
        updateUi()
        Thread {
            try {
                ModelManager.download(this) { p -> runOnUiThread { progress = p } }
                runOnUiThread { downloading = false; updateUi() }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    Toast.makeText(this, "Download failed: ${e.message}. Check internet and tap again.", Toast.LENGTH_LONG).show()
                    updateUi()
                }
            }
        }.start()
    }
}
