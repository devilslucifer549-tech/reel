package com.example.handscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GestureService : LifecycleService() {

    companion object {
        @Volatile var running = false
        @Volatile var lastEvent = ""
        @Volatile var lastEventAt = 0L
        const val ACTION_STOP = "com.example.handscroll.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.handscroll.TOGGLE_PAUSE"
        private const val CHANNEL = "hand_scroll"
        private const val NOTIF_ID = 1
        private const val MIN_FRAME_GAP_MS = 55L   // ~18 fps is plenty, saves battery
    }

    private lateinit var prefs: Prefs
    private lateinit var detector: SwipeDetector
    private var landmarker: HandLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var cameraOn = false
    private var paused = false
    private var screenOn = true
    private var lastFrame = 0L
    private var vibrator: Vibrator? = null

    private val sleepRunnable = Runnable {
        Toast.makeText(this, "Hand Scroll auto-stopped", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        detector = SwipeDetector(prefs)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        screenOn = getSystemService(PowerManager::class.java).isInteractive

        createChannel()
        startFg()

        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            landmarker = HandLandmarker.createFromOptions(
                this,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(ModelManager.baseOptions(this))
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .build()
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Hand model failed to load: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        running = true
        if (prefs.sleepMs > 0) main.postDelayed(sleepRunnable, prefs.sleepMs)
        SwipeAccessibilityService.listener = { refresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_PAUSE -> paused = !paused
        }
        refresh()
        return START_NOT_STICKY
    }

    // ---- camera on/off -------------------------------------------------

    private fun refresh() {
        val wanted = !paused && screenOn &&
                (prefs.allApps || SwipeAccessibilityService.isTargetActive())
        if (wanted && !cameraOn) startCamera() else if (!wanted && cameraOn) stopCamera()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startCamera() {
        if (landmarker == null) return
        cameraOn = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraOn) return@addListener
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                detector.reset()
            } catch (e: Exception) {
                cameraOn = false
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraOn = false
        try { provider?.unbindAll() } catch (_: Exception) {}
    }

    // ---- frame analysis ------------------------------------------------

    private fun analyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame < MIN_FRAME_GAP_MS) return
            lastFrame = now
            val lm = landmarker ?: return

            val bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot == 0) bmp else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(rot.toFloat()) }, true
            )

            val hand = lm.detect(BitmapImageBuilder(upright).build()).landmarks().firstOrNull()
            if (hand == null) {
                detector.onNoHand()
            } else {
                val dir = detector.onHand(hand, upright.width.toFloat() / upright.height, now)
                if (dir != null) main.post { perform(dir) }
            }
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun perform(action: SwipeDetector.Action) {
        val svc = SwipeAccessibilityService.instance ?: return
        val sent = when (action) {
            SwipeDetector.Action.NEXT -> svc.swipe(true)
            SwipeDetector.Action.PREVIOUS -> svc.swipe(false)
            SwipeDetector.Action.PLAY_PAUSE -> svc.tap(false)
            SwipeDetector.Action.LIKE -> svc.tap(true)
        }
        if (!sent) return
        lastEvent = when (action) {
            SwipeDetector.Action.NEXT -> "⬆ Next reel"
            SwipeDetector.Action.PREVIOUS -> "⬇ Previous reel"
            SwipeDetector.Action.PLAY_PAUSE -> "✊ Play / pause"
            SwipeDetector.Action.LIKE -> "👍 Like"
        }
        lastEventAt = SystemClock.elapsedRealtime()
        if (action == SwipeDetector.Action.NEXT || action == SwipeDetector.Action.PREVIOUS) prefs.bumpCount()
        if (prefs.haptics) {
            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- notification --------------------------------------------------

    private fun statusText() = when {
        paused -> "Paused"
        !screenOn -> "Screen off – camera off"
        cameraOn -> "Watching for your hand ✋"
        else -> "Waiting for Instagram – camera off"
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Hand Scroll", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, GestureService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_notif)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hand Scroll")
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, if (paused) "Resume" else "Pause", svc(ACTION_TOGGLE_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(icon, "Stop", svc(ACTION_STOP, 2)).build())
            .build()
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(sleepRunnable)
        SwipeAccessibilityService.listener = null
        stopCamera()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        executor.shutdown()
        try { executor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        landmarker?.close()
        super.onDestroy()
    }
}
