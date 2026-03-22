package com.kidwatch.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.kidwatch.app.MainActivity
import com.kidwatch.app.R
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EvidenceCaptureService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraLifecycleOwner = ServiceCameraLifecycleOwner()

    private lateinit var repository: LocalMonitoringRepository

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var cameraBindingInProgress = false
    private var cameraBound = false
    private var loopStarted = false

    private var activeSessionId: Long? = null
    private var activePackageName: String? = null
    private var activeCapturePolicy: LocalMonitoringRepository.CapturePolicy? = null
    private var activeSessionStartedAt: Long = 0L
    private var lastScreenshotAt: Long = 0L
    private var lastFaceObservationAt: Long = 0L
    private var projectionReadyAt: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                stopProjection(clearGrant = true)
                refreshNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraLifecycleOwner.onCreate()
        repository = LocalMonitoringRepository(applicationContext)
        createNotificationChannel()
        EvidencePreferences.syncScreenCaptureAvailability(this, MediaProjectionPermissionStore.hasGrant())
        serviceScope.launch {
            repository.syncMonitoredAppPolicies()
            val cleanupSummary = repository.purgeInvalidEvidence()
            if (cleanupSummary.totalRemoved > 0) {
                Log.i(
                    TAG,
                    "Removed invalid evidence: screenshots=${cleanupSummary.removedScreenshots}, faces=${cleanupSummary.removedFaces}"
                )
            }
            repository.pruneOldTelemetry()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!EvidencePreferences.isAutomaticEvidenceEnabled(this)) {
            stopCamera()
            stopProjection(clearGrant = false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!loopStarted) {
            loopStarted = true
            startCaptureLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopProjection(clearGrant = false)
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        cameraLifecycleOwner.onDestroy()
    }

    private fun startCaptureLoop() {
        handler.post(object : Runnable {
            override fun run() {
                serviceScope.launch {
                    runCatching { tick() }
                        .onFailure { Log.e(TAG, "Evidence tick failed", it) }
                }
                handler.postDelayed(this, CAPTURE_LOOP_INTERVAL_MS)
            }
        })
    }

    private suspend fun tick() {
        if (!EvidencePreferences.isAutomaticEvidenceEnabled(this)) {
            stopCamera()
            stopProjection(clearGrant = false)
            stopSelf()
            return
        }

        EvidencePreferences.setCameraCaptureEnabled(this, hasCameraPermission())
        EvidencePreferences.syncScreenCaptureAvailability(this, MediaProjectionPermissionStore.hasGrant())

        val activeTarget = resolveActiveTarget()
        val sessionId = activeTarget?.sessionId
        val packageName = activeTarget?.packageName
        val sessionStartedAt = activeTarget?.sessionStartedAt ?: 0L

        if (sessionId == null || packageName.isNullOrBlank() || sessionStartedAt <= 0L) {
            if (activeSessionId != null) {
                Log.d(TAG, "No active monitored session. Clearing evidence target.")
            }
            clearActiveTarget()
            stopCamera()
            pauseProjection()
            refreshNotification()
            return
        }

        val capturePolicy = repository.getCapturePolicy(packageName)
        if (!capturePolicy.trackSessions) {
            clearActiveTarget()
            stopCamera()
            pauseProjection()
            refreshNotification()
            return
        }

        val targetChanged = sessionId != activeSessionId || packageName != activePackageName
        if (targetChanged) {
            activeSessionId = sessionId
            activePackageName = packageName
            activeCapturePolicy = capturePolicy
            activeSessionStartedAt = sessionStartedAt
            lastScreenshotAt = 0L
            lastFaceObservationAt = 0L
            Log.d(
                TAG,
                "Tracking evidence for session=$activeSessionId package=$activePackageName startedAt=$activeSessionStartedAt"
            )
        } else {
            activeCapturePolicy = capturePolicy
            if (activeSessionStartedAt <= 0L) {
                activeSessionStartedAt = sessionStartedAt
            }
        }

        if (capturePolicy.allowFaceCapture &&
            EvidencePreferences.isCameraCaptureEnabled(this) &&
            hasCameraPermission()
        ) {
            ensureCameraRunning()
        } else {
            stopCamera()
        }

        if (capturePolicy.allowScreenshots && isScreenCaptureUsable()) {
            maybeCaptureScreenshot(sessionId, System.currentTimeMillis())
        } else {
            pauseProjection()
        }

        refreshNotification()
    }

    private fun clearActiveTarget() {
        activeSessionId = null
        activePackageName = null
        activeCapturePolicy = null
        activeSessionStartedAt = 0L
        lastScreenshotAt = 0L
        lastFaceObservationAt = 0L
    }

    private suspend fun maybeCaptureScreenshot(sessionId: Long, now: Long) {
        if (activeSessionStartedAt <= 0L) return
        if (now - activeSessionStartedAt < MIN_SESSION_AGE_FOR_SCREENSHOT_MS) return
        ensureProjection()
        if (projectionReadyAt <= 0L || now - projectionReadyAt < PROJECTION_WARMUP_MS) {
            return
        }

        val contentChangeRequested = EvidenceRuntimeState.consumePendingScreenshot(sessionId)
        val triggerType = when {
            contentChangeRequested -> "content_change"
            lastScreenshotAt == 0L -> "session_start"
            now - lastScreenshotAt >= PERIODIC_SCREENSHOT_INTERVAL_MS -> "interval"
            else -> null
        } ?: return

        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            Log.d(TAG, "No screenshot frame available yet for session=$sessionId trigger=$triggerType")
            return
        }
        val bitmap = image.toBitmap().also { image.close() } ?: return
        val saved = repository.saveSessionScreenshot(sessionId, bitmap, triggerType)
        Log.d(
            TAG,
            if (saved != null) {
                "Saved screenshot for session=$sessionId trigger=$triggerType"
            } else {
                "Skipped screenshot save for session=$sessionId trigger=$triggerType"
            }
        )
        lastScreenshotAt = now
    }

    private fun ensureProjection() {
        if (!isScreenCaptureUsable()) return
        if (mediaProjection != null && virtualDisplay != null && imageReader != null) return

        val projection = mediaProjection ?: acquireProjection() ?: return
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                "KidWatchEvidence",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        }.getOrElse {
            releaseProjectionResources(clearProjection = true)
            runCatching { projection.stop() }
            MediaProjectionPermissionStore.clear()
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, false)
            Log.e(TAG, "Failed to create virtual display", it)
            return
        }
        projectionReadyAt = System.currentTimeMillis()
        Log.d(TAG, "Projection ready for screenshots")
    }

    private fun acquireProjection(): MediaProjection? {
        val grant = MediaProjectionPermissionStore.get() ?: run {
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, false)
            Log.d(TAG, "Projection grant missing; screenshots paused")
            return null
        }
        startForegroundCompat()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching {
            projectionManager.getMediaProjection(grant.resultCode, grant.data)
        }.getOrElse {
            MediaProjectionPermissionStore.clear()
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, false)
            Log.e(TAG, "Failed to obtain MediaProjection", it)
            return null
        }
        runCatching {
            projection.registerCallback(projectionCallback, handler)
        }.onFailure {
            runCatching { projection.stop() }
            MediaProjectionPermissionStore.clear()
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, false)
            Log.e(TAG, "Failed to register projection callback", it)
            return null
        }
        mediaProjection = projection
        return projection
    }

    private fun pauseProjection() {
        releaseProjectionResources(clearProjection = false)
    }

    private fun stopProjection(clearGrant: Boolean) {
        val projection = mediaProjection
        runCatching { projection?.unregisterCallback(projectionCallback) }
        releaseProjectionResources(clearProjection = true)
        runCatching { projection?.stop() }
        if (clearGrant) {
            MediaProjectionPermissionStore.clear()
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, false)
        }
    }

    private fun releaseProjectionResources(clearProjection: Boolean) {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        if (clearProjection) {
            mediaProjection = null
        }
        projectionReadyAt = 0L
    }

    private suspend fun resolveActiveTarget(): ActiveEvidenceTarget? {
        val runtimeSessionId = EvidenceRuntimeState.currentSessionId
        val runtimePackageName = EvidenceRuntimeState.currentPackageName
        val runtimeStartedAt = EvidenceRuntimeState.currentSessionStartedAt
        if (runtimeSessionId != null && !runtimePackageName.isNullOrBlank() && runtimeStartedAt != null) {
            return ActiveEvidenceTarget(
                sessionId = runtimeSessionId,
                packageName = runtimePackageName,
                sessionStartedAt = runtimeStartedAt
            )
        }

        val fallback = repository.getLikelyActiveSession() ?: return null
        return ActiveEvidenceTarget(
            sessionId = fallback.id,
            packageName = fallback.packageName,
            sessionStartedAt = fallback.startTime
        )
    }

    private fun ensureCameraRunning() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { ensureCameraRunning() }
            return
        }
        if (!hasCameraPermission()) return
        cameraLifecycleOwner.moveToActive()
        cameraProvider?.let { provider ->
            if (!cameraBound && !cameraBindingInProgress) {
                bindCamera(provider)
            }
            return
        }
        if (cameraBindingInProgress) return
        cameraBindingInProgress = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                val provider = runCatching { future.get() }
                    .onFailure { Log.e(TAG, "Failed to get camera provider", it) }
                    .getOrNull()
                    ?: run {
                        cameraBindingInProgress = false
                        return@addListener
                    }
                cameraProvider = provider
                bindCamera(provider)
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        cameraBindingInProgress = false
        cameraLifecycleOwner.moveToActive()
        try {
            cameraAnalysis?.clearAnalyzer()
            provider.unbindAll()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, FaceAnalyzer()) }
            cameraAnalysis = analysis
            provider.bindToLifecycle(
                cameraLifecycleOwner,
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build(),
                analysis
            )
            cameraBound = true
            Log.d(TAG, "Front camera bound for automatic evidence capture")
        } catch (error: Exception) {
            cameraProvider = null
            cameraAnalysis?.clearAnalyzer()
            cameraAnalysis = null
            cameraBound = false
            Log.e(TAG, "Failed to bind camera", error)
        }
    }

    private fun stopCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stopCamera() }
            return
        }
        cameraBound = false
        cameraBindingInProgress = false
        cameraLifecycleOwner.moveToStopped()
        val provider = cameraProvider ?: return
        val analysis = cameraAnalysis
        cameraAnalysis = null
        ContextCompat.getMainExecutor(this).execute {
            analysis?.clearAnalyzer()
            runCatching { provider.unbindAll() }
                .onFailure { Log.e(TAG, "Failed to unbind camera", it) }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isScreenCaptureUsable(): Boolean {
        return EvidencePreferences.isScreenCaptureConfigured(this) &&
            EvidencePreferences.isScreenCaptureCurrentlyAvailable(this) &&
            MediaProjectionPermissionStore.hasGrant()
    }

    private fun refreshNotification() {
        startForegroundCompat()
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = buildForegroundServiceTypes()
            if (serviceTypes != 0) {
                startForeground(NOTIFICATION_ID, notification, serviceTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.evidence_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.evidence_capture_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationText = when {
            !EvidencePreferences.isAutomaticEvidenceEnabled(this) -> getString(
                R.string.evidence_capture_notification_disabled
            )
            activePackageName.isNullOrBlank() -> getString(R.string.evidence_capture_notification_ready)
            isScreenCaptureUsable() -> getString(
                R.string.evidence_capture_notification_active,
                resolveAppLabel(activePackageName.orEmpty())
            )
            else -> getString(
                R.string.evidence_capture_notification_partial,
                resolveAppLabel(activePackageName.orEmpty())
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.evidence_capture_notification_title))
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_stat_kidwatch)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun resolveAppLabel(packageName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        private val detector by lazy {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.08f)
                    .enableTracking()
                    .build()
            )
        }
        private var frameCount = 0

        @ExperimentalGetImage
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
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        maybePersistFaceObservation(imageProxy, faces.first())
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "Face analysis failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }

        private fun maybePersistFaceObservation(imageProxy: ImageProxy, face: Face) {
            val sessionId = activeSessionId ?: return
            val packageName = activePackageName ?: return
            val capturePolicy = activeCapturePolicy ?: return
            val now = System.currentTimeMillis()
            if (!capturePolicy.allowFaceCapture) return
            if (now - activeSessionStartedAt < MIN_SESSION_AGE_FOR_FACE_MS) return
            if (now - lastFaceObservationAt < FACE_OBSERVATION_INTERVAL_MS) return

            FaceCaptureState.onFaceDetected()
            val uprightBitmap = imageProxyToBitmap(imageProxy) ?: return
            val rotated = rotateBitmap(uprightBitmap, imageProxy.imageInfo.rotationDegrees)
            val identityCrop = cropFace(rotated, face.boundingBox, FACE_IDENTITY_CROP_PADDING_RATIO) ?: return
            val viewerSnapshot = createViewerSnapshot(rotated, face.boundingBox) ?: identityCrop
            val mirroredViewerSnapshot = mirrorBitmapHorizontally(viewerSnapshot) ?: viewerSnapshot
            lastFaceObservationAt = now
            Log.d(TAG, "Saving face observation for session=$sessionId package=$packageName")
            serviceScope.launch {
                runCatching {
                    repository.recordFaceObservation(
                        sessionId = sessionId,
                        faceBitmap = mirroredViewerSnapshot,
                        embeddingSourceBitmap = identityCrop,
                        confidence = 0.85f
                    )
                }.onFailure {
                    Log.e(TAG, "Failed to save face observation", it)
                }
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, stream)
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropFace(bitmap: Bitmap, bounds: Rect, paddingRatio: Float): Bitmap? {
        val paddingX = (bounds.width() * paddingRatio).toInt()
        val paddingY = (bounds.height() * paddingRatio).toInt()
        val safeLeft = (bounds.left - paddingX).coerceAtLeast(0)
        val safeTop = (bounds.top - paddingY).coerceAtLeast(0)
        val safeRight = (bounds.right + paddingX).coerceAtMost(bitmap.width)
        val safeBottom = (bounds.bottom + paddingY).coerceAtMost(bitmap.height)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 24 || height <= 24) return null
        return runCatching { Bitmap.createBitmap(bitmap, safeLeft, safeTop, width, height) }.getOrNull()
    }

    private fun createViewerSnapshot(bitmap: Bitmap, bounds: Rect): Bitmap? {
        val faceWidth = bounds.width().coerceAtLeast(1)
        val faceHeight = bounds.height().coerceAtLeast(1)
        val desiredHeight = maxOf(
            (faceHeight * VIEWER_SNAPSHOT_HEIGHT_RATIO).toInt(),
            ((faceWidth * VIEWER_SNAPSHOT_WIDTH_RATIO) / VIEWER_SNAPSHOT_ASPECT_RATIO).toInt()
        )
        val desiredWidth = maxOf(
            (faceWidth * VIEWER_SNAPSHOT_WIDTH_RATIO).toInt(),
            (desiredHeight * VIEWER_SNAPSHOT_ASPECT_RATIO).toInt()
        )
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY() + faceHeight * VIEWER_SNAPSHOT_VERTICAL_BIAS
        return cropBitmap(
            bitmap = bitmap,
            left = (centerX - desiredWidth / 2f).toInt(),
            top = (centerY - desiredHeight / 2f).toInt(),
            right = (centerX + desiredWidth / 2f).toInt(),
            bottom = (centerY + desiredHeight / 2f).toInt()
        )
    }

    private fun cropBitmap(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Bitmap? {
        val safeLeft = left.coerceAtLeast(0)
        val safeTop = top.coerceAtLeast(0)
        val safeRight = right.coerceAtMost(bitmap.width)
        val safeBottom = bottom.coerceAtMost(bitmap.height)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 24 || height <= 24) return null
        return runCatching {
            Bitmap.createBitmap(bitmap, safeLeft, safeTop, width, height)
        }.getOrNull()
    }

    private fun mirrorBitmapHorizontally(bitmap: Bitmap): Bitmap? {
        val matrix = Matrix().apply {
            postScale(-1f, 1f)
            postTranslate(bitmap.width.toFloat(), 0f)
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val nv21 = ByteArray(width * height + (width * height / 2))

        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[outputIndex++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[outputIndex++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
                nv21[outputIndex++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }
        return nv21
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun buildForegroundServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var serviceTypes = 0
        if (EvidencePreferences.isCameraCaptureEnabled(this) && hasCameraPermission()) {
            serviceTypes = serviceTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (isScreenCaptureUsable()) {
            serviceTypes = serviceTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return serviceTypes
    }

    companion object {
        private const val TAG = "EvidenceCaptureService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "evidence_capture"
        private const val CAPTURE_LOOP_INTERVAL_MS = 3_000L
        private const val FACE_ANALYSIS_SKIP_FRAMES = 8
        private const val FACE_OBSERVATION_INTERVAL_MS = 30_000L
        private const val PERIODIC_SCREENSHOT_INTERVAL_MS = 30_000L
        private const val MIN_SESSION_AGE_FOR_SCREENSHOT_MS = 10_000L
        private const val MIN_SESSION_AGE_FOR_FACE_MS = 5_000L
        private const val PROJECTION_WARMUP_MS = 1_200L
        private const val FACE_IDENTITY_CROP_PADDING_RATIO = 0.35f
        private const val VIEWER_SNAPSHOT_WIDTH_RATIO = 2.6f
        private const val VIEWER_SNAPSHOT_HEIGHT_RATIO = 3.7f
        private const val VIEWER_SNAPSHOT_ASPECT_RATIO = 0.78f
        private const val VIEWER_SNAPSHOT_VERTICAL_BIAS = 0.32f

        fun start(context: Context) {
            if (!EvidencePreferences.isAutomaticEvidenceEnabled(context)) return
            val intent = Intent(context, EvidenceCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EvidenceCaptureService::class.java))
        }
    }

    private data class ActiveEvidenceTarget(
        val sessionId: Long,
        val packageName: String,
        val sessionStartedAt: Long
    )

    private class ServiceCameraLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun onCreate() {
            if (registry.currentState == Lifecycle.State.INITIALIZED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
        }

        fun moveToActive() {
            onCreate()
            when (registry.currentState) {
                Lifecycle.State.CREATED -> {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
                Lifecycle.State.STARTED -> {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
                Lifecycle.State.RESUMED -> Unit
                else -> Unit
            }
        }

        fun moveToStopped() {
            when (registry.currentState) {
                Lifecycle.State.RESUMED -> {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                Lifecycle.State.STARTED -> {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                else -> Unit
            }
        }

        fun onDestroy() {
            moveToStopped()
            if (registry.currentState == Lifecycle.State.CREATED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }
}
