package com.kidwatch.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.kidwatch.app.MainActivity
import com.kidwatch.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that performs headless face detection when target apps
 * (e.g. YouTube) are in foreground. No camera UI is shown.
 *
 * Designed to scale: add package names to [TARGET_PACKAGES] for more apps.
 */
class FaceCaptureService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startForegroundCheckLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.face_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.face_capture_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.face_capture_notification_title))
            .setContentText(getString(R.string.face_capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCheckLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (isTargetAppInForeground()) {
                    ensureCameraRunning()
                } else {
                    stopCamera()
                }
                handler.postDelayed(this, FOREGROUND_CHECK_INTERVAL_MS)
            }
        })
    }

    private fun isTargetAppInForeground(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val end = System.currentTimeMillis()
        val start = end - FOREGROUND_QUERY_WINDOW_MS
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return false
        val foreground = stats
            .filter { it.lastTimeUsed > start }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName ?: return false
        return foreground in TARGET_PACKAGES
    }

    private fun ensureCameraRunning() {
        if (cameraProvider != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            bindCamera(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }
            provider.bindToLifecycle(this, selector, analyzer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        private var frameCount = 0
        private val detector by lazy {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.15f)
                    .build()
            )
        }

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            frameCount++
            if (frameCount % FACE_ANALYSIS_SKIP_FRAMES != 0) {
                imageProxy.close()
                return
            }
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        FaceCaptureState.onFaceDetected()
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    companion object {
        private const val TAG = "FaceCaptureService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "face_capture"
        private const val FOREGROUND_CHECK_INTERVAL_MS = 5_000L
        private const val FOREGROUND_QUERY_WINDOW_MS = 60_000L
        private const val FACE_ANALYSIS_SKIP_FRAMES = 15

        /** Apps for which face capture runs. Add more to scale. */
        val TARGET_PACKAGES = setOf("com.google.android.youtube")

        fun start(context: Context) {
            val intent = Intent(context, FaceCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FaceCaptureService::class.java))
        }
    }
}
