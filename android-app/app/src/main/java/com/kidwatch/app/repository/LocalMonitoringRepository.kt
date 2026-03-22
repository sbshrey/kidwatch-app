package com.kidwatch.app.repository

import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.room.withTransaction
import com.kidwatch.app.analytics.AnalyticsTracker
import com.kidwatch.app.data.local.KidWatchDatabase
import com.kidwatch.app.data.local.entity.ActivitySessionEntity
import com.kidwatch.app.data.local.entity.AppUsageEntity
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.FaceObservationEntity
import com.kidwatch.app.data.local.entity.IdentityClusterEntity
import com.kidwatch.app.data.local.entity.MonitoredAppPolicyEntity
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.data.local.entity.SessionInsightEntity
import com.kidwatch.app.data.local.entity.SessionScreenshotEntity
import com.kidwatch.app.data.local.entity.SyncQueueEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
import com.kidwatch.app.insights.FaceEmbeddingEngine
import com.kidwatch.app.insights.OpenAiSessionSuitabilityAnalyzer
import com.kidwatch.app.insights.SessionInsightGenerator
import com.kidwatch.app.services.AccessibilityCaptureCatalog
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.EvidenceFileManager
import com.kidwatch.app.services.EvidencePreferences
import com.kidwatch.app.services.EvidenceQualityEvaluator
import com.kidwatch.app.services.EvidenceRuntimeState
import com.kidwatch.app.services.MonitoringPolicyCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

class LocalMonitoringRepository(
    context: Context
) {
    private val appContext = context.applicationContext
    private val database: KidWatchDatabase = KidWatchDatabase.getInstance(appContext)
    private val evidenceFiles = EvidenceFileManager(appContext)
    private val faceEmbeddingEngine = FaceEmbeddingEngine()
    private val evidenceQualityEvaluator = EvidenceQualityEvaluator()
    private val openAiSessionSuitabilityAnalyzer = OpenAiSessionSuitabilityAnalyzer()
    private val analyticsTracker = AnalyticsTracker(appContext)

    fun initializeDatabase() {
        database.openHelper.writableDatabase
    }

    suspend fun enqueueSummary(entityType: String, payloadJson: String) {
        database.syncQueueDao().insert(
            SyncQueueEntity(
                entityType = entityType,
                payloadJson = payloadJson,
                createdAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
    }

    suspend fun saveAppUsage(appPackage: String, startTime: Long, endTime: Long, duration: Long) {
        if (duration <= 0L) return
        val latest = database.appUsageDao().getLatestForPackage(appPackage)
        if (latest != null && startTime <= latest.endTime + SESSION_MERGE_WINDOW_MS) {
            val mergedStart = minOf(latest.startTime, startTime)
            val mergedEnd = maxOf(latest.endTime, endTime)
            database.appUsageDao().updateSession(
                id = latest.id,
                startTime = mergedStart,
                endTime = mergedEnd,
                duration = mergedEnd - mergedStart
            )
            return
        }
        database.appUsageDao().insert(
            AppUsageEntity(
                packageName = appPackage,
                startTime = startTime,
                endTime = endTime,
                duration = duration
            )
        )
    }

    suspend fun upsertActivitySession(
        packageName: String,
        startTime: Long,
        endTime: Long
    ): Long {
        if (endTime < startTime) return -1L
        val capturePolicy = getCapturePolicy(packageName)
        if (!capturePolicy.trackSessions) return -1L
        val now = System.currentTimeMillis()
        val latest = database.activitySessionDao().getLatestForPackage(packageName)
        val sessionId = if (latest != null && startTime <= latest.endTime + SESSION_MERGE_WINDOW_MS) {
            val mergedStart = minOf(latest.startTime, startTime)
            val mergedEnd = maxOf(latest.endTime, endTime)
            val updated = latest.copy(
                startTime = mergedStart,
                endTime = mergedEnd,
                duration = (mergedEnd - mergedStart).coerceAtLeast(0L),
                updatedAt = now
            )
            database.activitySessionDao().update(updated)
            updated.id
        } else {
            database.activitySessionDao().insert(
                ActivitySessionEntity(
                    packageName = packageName,
                    appName = resolveAppName(packageName),
                    startTime = startTime,
                    endTime = endTime,
                    duration = (endTime - startTime).coerceAtLeast(0L),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        attachUnassignedVideoEvents(sessionId, packageName, startTime, endTime)
        refreshSessionDerivedState(sessionId)
        if (sessionId > 0L) {
            analyticsTracker.markFirstSessionRecordedIfNeeded(packageName)
        }
        return sessionId
    }

    suspend fun extendActivitySession(sessionId: Long, endTime: Long) {
        val existing = database.activitySessionDao().getById(sessionId) ?: return
        if (endTime <= existing.endTime) return
        database.activitySessionDao().update(
            existing.copy(
                endTime = endTime,
                duration = endTime - existing.startTime,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveVideoEvent(
        packageName: String,
        title: String,
        channel: String,
        timestamp: Long,
        canonicalUrl: String? = null,
        faceDetected: Boolean = false,
        sessionId: Long? = null
    ): Long? {
        if (title.isBlank() && channel.isBlank()) return null
        val sanitizedCanonicalUrl = canonicalUrl?.trim()?.takeIf { it.isNotBlank() }
        val sanitizedLinkKind = if (sanitizedCanonicalUrl != null) "exact" else "none"
        val sanitizedLinkSource = if (sanitizedCanonicalUrl != null) "accessibility_text" else "none"
        val latest = database.videoEventsDao().getLatest()
        val resolvedSessionId = sessionId
            ?: database.activitySessionDao().findMatchingSession(packageName, timestamp)?.id
        if (
            latest != null &&
            latest.packageName == packageName &&
            latest.title.equals(title, ignoreCase = true) &&
            latest.channel.equals(channel, ignoreCase = true) &&
            timestamp - latest.timestamp < VIDEO_EVENT_MIN_INTERVAL_MS
        ) {
            val enriched = latest.copy(
                sessionId = latest.sessionId ?: resolvedSessionId,
                faceDetected = latest.faceDetected || faceDetected,
                canonicalUrl = when {
                    sanitizedCanonicalUrl != null -> sanitizedCanonicalUrl
                    else -> latest.canonicalUrl
                },
                fallbackUrl = null,
                linkKind = resolvePreferredLinkKind(latest.linkKind, sanitizedLinkKind),
                linkSource = resolvePreferredLinkSource(
                    currentKind = latest.linkKind,
                    currentSource = latest.linkSource,
                    incomingKind = sanitizedLinkKind,
                    incomingSource = sanitizedLinkSource
                )
            )
            if (enriched != latest) {
                database.videoEventsDao().update(enriched)
                enqueueContentEvent(enriched)
                analyticsTracker.logContentEventRecorded(
                    packageName = packageName,
                    hasCanonicalUrl = !enriched.canonicalUrl.isNullOrBlank()
                )
                val sessionToRefresh = enriched.sessionId ?: resolvedSessionId
                if (sessionToRefresh != null) {
                    refreshSessionDerivedState(sessionToRefresh)
                }
            }
            return enriched.sessionId ?: resolvedSessionId
        }
        val insertedId = database.videoEventsDao().insert(
            VideoEventEntity(
                packageName = packageName,
                title = title.ifBlank { "Unknown" },
                channel = channel.ifBlank { "Unknown" },
                timestamp = timestamp,
                canonicalUrl = sanitizedCanonicalUrl,
                fallbackUrl = null,
                linkKind = sanitizedLinkKind,
                linkSource = sanitizedLinkSource,
                faceDetected = faceDetected,
                sessionId = resolvedSessionId
            )
        )
        enqueueContentEvent(
            VideoEventEntity(
                id = insertedId,
                packageName = packageName,
                title = title.ifBlank { "Unknown" },
                channel = channel.ifBlank { "Unknown" },
                timestamp = timestamp,
                canonicalUrl = sanitizedCanonicalUrl,
                fallbackUrl = null,
                linkKind = sanitizedLinkKind,
                linkSource = sanitizedLinkSource,
                faceDetected = faceDetected,
                sessionId = resolvedSessionId
            )
        )
        analyticsTracker.logContentEventRecorded(
            packageName = packageName,
            hasCanonicalUrl = sanitizedCanonicalUrl != null
        )
        if (resolvedSessionId != null) {
            refreshSessionDerivedState(resolvedSessionId)
        }
        return resolvedSessionId
    }

    suspend fun saveSessionScreenshot(
        sessionId: Long,
        bitmap: Bitmap,
        triggerType: String
    ): SessionScreenshotEntity? {
        val session = database.activitySessionDao().getById(sessionId) ?: return null
        if (!getCapturePolicy(session.packageName).allowScreenshots) return null
        if (!evidenceQualityEvaluator.isValidScreenshot(bitmap)) return null
        val screenshotCount = database.sessionScreenshotDao().countForSession(sessionId)
        if (screenshotCount >= MAX_SCREENSHOTS_PER_SESSION) return null
        val filePath = evidenceFiles.saveScreenshot(bitmap, sessionId, System.currentTimeMillis())
        val entry = SessionScreenshotEntity(
            sessionId = sessionId,
            filePath = filePath,
            capturedAt = System.currentTimeMillis(),
            triggerType = triggerType,
            isRepresentative = screenshotCount == 0
        )
        database.sessionScreenshotDao().insert(entry)
        refreshSessionDerivedState(sessionId)
        analyticsTracker.markFirstScreenshotRecordedIfNeeded(session.packageName, triggerType)
        return entry
    }

    suspend fun recordFaceObservation(
        sessionId: Long,
        faceBitmap: Bitmap,
        embeddingSourceBitmap: Bitmap = faceBitmap,
        confidence: Float
    ): FaceObservationEntity? {
        val session = database.activitySessionDao().getById(sessionId) ?: return null
        if (!getCapturePolicy(session.packageName).allowFaceCapture) return null
        if (!evidenceQualityEvaluator.isValidFaceCapture(faceBitmap)) return null
        val embedding = faceEmbeddingEngine.createEmbedding(embeddingSourceBitmap)
        val cropPath = evidenceFiles.saveFaceCrop(faceBitmap, sessionId, System.currentTimeMillis())
        val cluster = assignCluster(embedding, cropPath)
        val entry = FaceObservationEntity(
            sessionId = sessionId,
            clusterId = cluster.id,
            cropPath = cropPath,
            embedding = encodeEmbedding(embedding),
            confidence = confidence,
            observedAt = System.currentTimeMillis()
        )
        val insertedId = database.faceObservationDao().insert(entry)
        refreshSessionDerivedState(sessionId)
        analyticsTracker.markFirstFaceObservationRecordedIfNeeded(session.packageName)
        return entry.copy(id = insertedId)
    }

    suspend fun purgeInvalidEvidence(): InvalidEvidenceCleanupSummary {
        val touchedSessions = mutableSetOf<Long>()
        var removedScreenshots = 0
        var removedFaces = 0

        database.activitySessionDao().getSince(0L).forEach { session ->
            val invalidScreenshots = database.sessionScreenshotDao().getForSession(session.id)
                .filter { !evidenceQualityEvaluator.isValidScreenshotFile(it.filePath) }
            if (invalidScreenshots.isNotEmpty()) {
                invalidScreenshots.forEach { evidenceFiles.deletePath(it.filePath) }
                database.sessionScreenshotDao().deleteByIds(invalidScreenshots.map { it.id })
                removedScreenshots += invalidScreenshots.size
                touchedSessions += session.id
            }

            val invalidFaces = database.faceObservationDao().getForSession(session.id)
                .filter { !evidenceQualityEvaluator.isValidFaceFile(it.cropPath) }
            if (invalidFaces.isNotEmpty()) {
                invalidFaces.forEach { evidenceFiles.deletePath(it.cropPath) }
                database.faceObservationDao().deleteByIds(invalidFaces.map { it.id })
                removedFaces += invalidFaces.size
                touchedSessions += session.id
            }
        }

        if (removedFaces > 0) {
            repairIdentityClusters()
        }

        touchedSessions.forEach { refreshSessionDerivedState(it) }

        return InvalidEvidenceCleanupSummary(
            removedScreenshots = removedScreenshots,
            removedFaces = removedFaces
        )
    }

    suspend fun getActivitySessionFeed(startMs: Long): List<ActivitySessionEntity> {
        return database.activitySessionDao().getSince(startMs)
    }

    suspend fun getActivitySessionPage(
        startMs: Long,
        filter: com.kidwatch.app.ui.ActivityFeedFilter,
        beforeStartTime: Long?,
        limit: Int
    ): ActivityFeedPage {
        val pageItems = when (filter) {
            com.kidwatch.app.ui.ActivityFeedFilter.ALL -> database.activitySessionDao().getPageSince(
                startMs = startMs,
                beforeStartTime = beforeStartTime,
                limit = limit
            )
            com.kidwatch.app.ui.ActivityFeedFilter.NEEDS_REVIEW -> database.activitySessionDao().getNeedsReviewPageSince(
                startMs = startMs,
                beforeStartTime = beforeStartTime,
                limit = limit
            )
            com.kidwatch.app.ui.ActivityFeedFilter.UNKNOWN_PERSON -> database.activitySessionDao().getUnknownViewerPageSince(
                startMs = startMs,
                beforeStartTime = beforeStartTime,
                limit = limit
            )
            com.kidwatch.app.ui.ActivityFeedFilter.CONTENT_APPS -> database.activitySessionDao().getContentAppsPageSince(
                startMs = startMs,
                beforeStartTime = beforeStartTime,
                limit = limit,
                supportedPackages = AccessibilityCaptureCatalog.supportedPackages().ifEmpty { setOf(NO_PACKAGE_PLACEHOLDER) }.toList()
            )
        }
        return ActivityFeedPage(
            items = pageItems,
            nextCursorStartTime = pageItems.lastOrNull()?.startTime,
            hasMore = pageItems.size == limit
        )
    }

    suspend fun getActivityFeedCounts(startMs: Long): ActivityFeedCounts {
        return ActivityFeedCounts(
            totalSessions = database.activitySessionDao().countSince(startMs),
            reviewCount = database.activitySessionDao().countNeedsReviewSince(startMs),
            unknownCount = database.activitySessionDao().countUnknownViewerSince(startMs)
        )
    }

    suspend fun getActivitySessionsByIds(ids: List<Long>): List<ActivitySessionEntity> {
        if (ids.isEmpty()) return emptyList()
        return database.activitySessionDao().getByIds(ids)
    }

    suspend fun getLikelyActiveSession(
        nowMillis: Long = System.currentTimeMillis(),
        freshnessWindowMs: Long = ACTIVE_SESSION_FRESHNESS_MS
    ): ActivitySessionEntity? {
        val session = database.activitySessionDao()
            .getLatestUpdatedSince(nowMillis - freshnessWindowMs)
            ?: return null
        if (nowMillis - session.updatedAt > freshnessWindowMs) return null
        if (nowMillis - session.endTime > freshnessWindowMs) return null
        if (!getCapturePolicy(session.packageName).trackSessions) return null
        return session
    }

    suspend fun getSessionDetail(sessionId: Long): ActivitySessionDetail? {
        val session = database.activitySessionDao().getById(sessionId) ?: return null
        val screenshots = database.sessionScreenshotDao().getForSession(sessionId)
        val videos = database.videoEventsDao().getForSession(sessionId)
        val observations = database.faceObservationDao().getForSession(sessionId)
        val clusters = database.identityClusterDao().getAll().associateBy { it.id }
        val insight = database.sessionInsightDao().getForSession(sessionId)
        return ActivitySessionDetail(
            session = session,
            screenshots = screenshots,
            videos = videos,
            faceObservations = observations.map { FaceObservationWithCluster(it, it.clusterId?.let(clusters::get)) },
            insight = insight
        )
    }

    suspend fun deleteActivitySession(sessionId: Long): Boolean {
        val detail = getSessionDetail(sessionId) ?: return false
        val screenshotPaths = detail.screenshots.map { it.filePath }
        val faceCropPaths = detail.faceObservations.mapNotNull { it.observation.cropPath }
        val videoEventIds = detail.videos.map { it.id }.toSet()
        val hadFaceObservations = detail.faceObservations.isNotEmpty()

        database.withTransaction {
            database.videoEventsDao().deleteForSession(sessionId)
            database.sessionInsightDao().deleteForSession(sessionId)
            database.activitySessionDao().deleteById(sessionId)
        }

        screenshotPaths.forEach(evidenceFiles::deletePath)
        faceCropPaths.forEach(evidenceFiles::deletePath)
        if (EvidenceRuntimeState.currentSessionId == sessionId) {
            EvidenceRuntimeState.clearActiveSession()
        }
        deletePendingContentEventQueueItems(videoEventIds, sessionId)
        if (hadFaceObservations) {
            repairIdentityClusters()
        }
        return true
    }

    suspend fun getUnknownClusters(): List<IdentityClusterEntity> {
        return database.identityClusterDao().getUnknownClusters()
    }

    suspend fun getPersonProfiles(): List<PersonProfileEntity> {
        return dedupeProfiles(database.personProfileDao().getAll())
    }

    suspend fun getChildProfiles(): List<PersonProfileEntity> {
        return dedupeProfiles(database.personProfileDao().getChildren())
            .filter { it.role == "child" }
    }

    suspend fun ensureDeviceOwnerProfile(fallbackName: String): PersonProfileEntity {
        val owners = database.personProfileDao().getAll()
            .filter { it.isDeviceOwner }
            .sortedByDescending { it.updatedAt }
        val existing = owners.firstOrNull()
        if (existing != null) {
            if (owners.size > 1) {
                normalizeDuplicateOwners(existing, owners.drop(1))
            }
            return database.personProfileDao().getById(existing.id) ?: existing
        }
        val now = System.currentTimeMillis()
        val profile = PersonProfileEntity(
            name = fallbackName.trim().ifBlank {
                DeviceInfoProvider(appContext).getDeviceInfo().deviceName
            },
            role = "parent",
            ageYears = null,
            isDeviceOwner = true,
            createdAt = now,
            updatedAt = now
        )
        val insertedId = database.personProfileDao().insert(profile)
        return profile.copy(id = insertedId)
    }

    suspend fun updateDeviceOwnerProfile(name: String): PersonProfileEntity {
        val existing = ensureDeviceOwnerProfile(name)
        val trimmed = name.trim().ifBlank { existing.name }
        val updated = existing.copy(
            name = trimmed,
            role = "parent",
            isDeviceOwner = true,
            updatedAt = System.currentTimeMillis()
        )
        if (updated != existing) {
            database.personProfileDao().update(updated)
            syncClustersForProfile(updated)
            refreshSessionsLinkedToPerson(updated.id)
        }
        return updated
    }

    suspend fun saveChildProfile(
        id: Long? = null,
        name: String,
        ageYears: Int?
    ): PersonProfileEntity {
        val trimmedName = name.trim().ifBlank { "Child" }
        val sanitizedAge = sanitizeAge(ageYears)
        val now = System.currentTimeMillis()
        val existing = id?.let { database.personProfileDao().getById(it) }
            ?: findMatchingProfile(trimmedName, role = "child", isDeviceOwner = false)
        val updated = if (existing != null) {
            existing.copy(
                name = trimmedName,
                role = "child",
                ageYears = sanitizedAge,
                isDeviceOwner = false,
                updatedAt = now
            )
        } else {
            PersonProfileEntity(
                name = trimmedName,
                role = "child",
                ageYears = sanitizedAge,
                isDeviceOwner = false,
                createdAt = now,
                updatedAt = now
            )
        }
        val saved = if (existing != null) {
            if (updated != existing) {
                database.personProfileDao().update(updated)
                syncClustersForProfile(updated)
                refreshSessionsLinkedToPerson(updated.id)
            }
            updated
        } else {
            updated.copy(id = database.personProfileDao().insert(updated))
        }
        return saved
    }

    suspend fun deletePersonProfile(profileId: Long): Boolean {
        val profile = database.personProfileDao().getById(profileId) ?: return false
        if (profile.isDeviceOwner) return false
        val linkedClusters = database.identityClusterDao().getForPersonProfile(profileId)
        val linkedClusterIds = linkedClusters.map { it.id }.toSet()
        val affectedSessionIds = database.activitySessionDao().getSince(0L)
            .filter { session ->
                session.assignedPersonProfileId == profileId || session.primaryClusterId in linkedClusterIds
            }
            .map { it.id }
            .distinct()
        val now = System.currentTimeMillis()
        linkedClusters.forEach { cluster ->
            database.identityClusterDao().update(
                cluster.copy(
                    label = null,
                    role = "unknown",
                    personProfileId = null,
                    updatedAt = now
                )
            )
        }
        database.activitySessionDao().getSince(0L)
            .filter { it.assignedPersonProfileId == profileId }
            .forEach { session ->
                database.activitySessionDao().update(
                    session.copy(
                        assignedPersonProfileId = null,
                        assignedPersonName = null,
                        assignedPersonRole = null,
                        assignedPersonAgeYears = null,
                        updatedAt = now
                    )
                )
            }
        database.personProfileDao().deleteById(profileId)
        affectedSessionIds.forEach { refreshSessionDerivedState(it) }
        return true
    }

    suspend fun assignSessionToPerson(sessionId: Long, profileId: Long?) {
        val session = database.activitySessionDao().getById(sessionId) ?: return
        val profile = profileId?.let { database.personProfileDao().getById(it) }
        database.activitySessionDao().update(
            session.copy(
                assignedPersonProfileId = profile?.id,
                assignedPersonName = profile?.name,
                assignedPersonRole = profile?.role,
                assignedPersonAgeYears = profile?.ageYears,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshSessionDerivedState(sessionId)
    }

    suspend fun syncMonitoredAppPolicies(): List<MonitoredAppPolicyEntity> {
        val existing = database.monitoredAppPolicyDao().getAll().associateBy { it.packageName }
        val legacyAllowedPackages = EvidencePreferences.getLegacyAllowedPackages(appContext)
        val migratedLegacy = EvidencePreferences.hasMigratedLegacyAllowlist(appContext)
        val now = System.currentTimeMillis()
        val updatedEntries = mutableListOf<MonitoredAppPolicyEntity>()

        resolveVisibleApps().forEach { app ->
            val current = existing[app.packageName]
            val resolvedCategory = MonitoringPolicyCatalog.inferCategory(app.packageName, app.displayName)
            val isEligible = MonitoringPolicyCatalog.isMonitoringShortlistEligible(
                packageName = app.packageName,
                category = resolvedCategory
            )
            val defaults = when {
                !migratedLegacy && legacyAllowedPackages.contains(app.packageName) && isEligible -> CapturePolicy(
                    trackSessions = true,
                    allowScreenshots = true,
                    allowFaceCapture = true
                )
                isEligible -> defaultsToCapturePolicy(MonitoringPolicyCatalog.defaultsFor(resolvedCategory))
                else -> CapturePolicy(
                    trackSessions = false,
                    allowScreenshots = false,
                    allowFaceCapture = false
                )
            }
            val merged = if (current == null) {
                MonitoredAppPolicyEntity(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    category = resolvedCategory,
                    isRecommended = isEligible,
                    trackSessions = defaults.trackSessions,
                    allowScreenshots = defaults.allowScreenshots,
                    allowFaceCapture = defaults.allowFaceCapture,
                    updatedAt = now
                )
            } else {
                val isRecommended = isEligible
                if (
                    current.displayName != app.displayName ||
                    current.category != resolvedCategory ||
                    current.isRecommended != isRecommended ||
                    current.trackSessions != defaults.trackSessions && !isEligible ||
                    current.allowScreenshots != defaults.allowScreenshots && !isEligible ||
                    current.allowFaceCapture != defaults.allowFaceCapture && !isEligible
                ) {
                    current.copy(
                        displayName = app.displayName,
                        category = resolvedCategory,
                        isRecommended = isRecommended,
                        trackSessions = if (isEligible) current.trackSessions else defaults.trackSessions,
                        allowScreenshots = if (isEligible) current.allowScreenshots else defaults.allowScreenshots,
                        allowFaceCapture = if (isEligible) current.allowFaceCapture else defaults.allowFaceCapture,
                        updatedAt = now
                    )
                } else {
                    current
                }
            }
            if (current == null || merged != current) {
                updatedEntries += merged
            }
        }

        if (updatedEntries.isNotEmpty()) {
            database.monitoredAppPolicyDao().upsertAll(updatedEntries)
        }
        if (!migratedLegacy) {
            EvidencePreferences.markLegacyAllowlistMigrated(appContext)
        }
        return database.monitoredAppPolicyDao().getAll()
    }

    suspend fun getMonitoredAppPolicies(): List<MonitoredAppPolicyEntity> {
        syncMonitoredAppPolicies()
        return database.monitoredAppPolicyDao().getAll()
    }

    suspend fun getEligibleMonitoringPolicies(): List<MonitoredAppPolicyEntity> {
        val installedPackages = resolveVisibleApps()
            .map { it.packageName }
            .toSet()
        return syncMonitoredAppPolicies()
            .filter { it.packageName in installedPackages }
            .filter(::isEligibleMonitoringPolicy)
            .sortedWith(
                compareBy<MonitoredAppPolicyEntity>(
                    { MonitoringPolicyCatalog.monitoringShortlistRank(it.packageName, it.category) },
                    { it.category != MonitoringPolicyCatalog.GAMES },
                    { it.displayName.lowercase() }
                )
            )
    }

    suspend fun getCapturePolicy(packageName: String): CapturePolicy {
        val policy = ensureMonitoredAppPolicy(packageName)
        val isEligible = isEligibleMonitoringPolicy(policy)
        return CapturePolicy(
            trackSessions = isEligible && policy.trackSessions,
            allowScreenshots = isEligible && policy.trackSessions && policy.allowScreenshots,
            allowFaceCapture = isEligible && policy.trackSessions && policy.allowFaceCapture
        )
    }

    suspend fun updateMonitoredAppPolicy(
        packageName: String,
        trackSessions: Boolean,
        allowScreenshots: Boolean,
        allowFaceCapture: Boolean
    ) {
        val existing = ensureMonitoredAppPolicy(packageName)
        val isEligible = isEligibleMonitoringPolicy(existing)
        val sanitizedTrack = isEligible && trackSessions
        val sanitizedScreenshots = sanitizedTrack && allowScreenshots
        val sanitizedFaceCapture = sanitizedTrack && allowFaceCapture
        database.monitoredAppPolicyDao().upsert(
            existing.copy(
                trackSessions = sanitizedTrack,
                allowScreenshots = sanitizedScreenshots,
                allowFaceCapture = sanitizedFaceCapture,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getMonitoringSummary(): MonitoringSummary {
        val policies = getEligibleMonitoringPolicies()
        return MonitoringSummary(
            policies = policies,
            trackedCount = policies.count { it.trackSessions },
            screenshotCount = policies.count { it.trackSessions && it.allowScreenshots },
            faceCaptureCount = policies.count { it.trackSessions && it.allowFaceCapture },
            recommendedCategoryCounts = MonitoringPolicyCatalog.definitions().map { definition ->
                val categoryPolicies = policies.filter { it.category == definition.key }
                MonitoringCategorySummary(
                    category = definition.key,
                    label = definition.label,
                    subtitle = definition.subtitle,
                    installedCount = categoryPolicies.size,
                    trackedCount = categoryPolicies.count { it.trackSessions }
                )
            }
        )
    }

    suspend fun labelIdentityCluster(
        clusterId: Long,
        label: String,
        role: String,
        ageYears: Int? = null,
        useDeviceOwnerProfile: Boolean = false
    ) {
        val existing = database.identityClusterDao().getById(clusterId) ?: return
        val trimmedLabel = label.trim().ifBlank { defaultLabelForRole(role) }
        val linkedProfile = when {
            useDeviceOwnerProfile -> updateDeviceOwnerProfile(trimmedLabel)
            role == "child" -> saveChildProfile(name = trimmedLabel, ageYears = ageYears)
            else -> upsertNamedProfile(
                name = trimmedLabel,
                role = role,
                ageYears = sanitizeAge(ageYears),
                isDeviceOwner = false
            )
        }
        database.identityClusterDao().update(
            existing.copy(
                label = linkedProfile.name,
                role = role,
                personProfileId = linkedProfile.id,
                updatedAt = System.currentTimeMillis()
            )
        )
        database.activitySessionDao().getSince(0L)
            .filter { it.primaryClusterId == clusterId }
            .forEach { refreshSessionDerivedState(it.id) }
    }

    suspend fun refreshAgeSuitabilityForSession(sessionId: Long): Boolean {
        return recomputeSessionInsight(sessionId, preferAi = true)
    }

    suspend fun refreshAgeSuitabilityForSessions(sessionIds: List<Long>): Boolean {
        var changed = false
        sessionIds.distinct().forEach { sessionId ->
            changed = recomputeSessionInsight(sessionId, preferAi = true) || changed
        }
        return changed
    }

    suspend fun clearAllEvidence() {
        evidenceFiles.clearAll()
        database.videoEventsDao().deleteAll()
        database.faceObservationDao().deleteAll()
        database.sessionScreenshotDao().deleteAll()
        database.sessionInsightDao().deleteAll()
        database.identityClusterDao().deleteAll()
        database.activitySessionDao().deleteAll()
    }

    suspend fun clearMonitoringHistory() {
        clearAllEvidence()
        database.appUsageDao().deleteAll()
        database.contentAnalysisDao().deleteAll()
        database.syncQueueDao().deleteAll()
    }

    suspend fun aggregateDailyUsage(startMs: Long, endMs: Long): Map<String, Long> {
        return database.appUsageDao().getInWindow(startMs, endMs)
            .groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.sumOf { it.duration } / 60000L }
    }

    suspend fun aggregateContentSummary(startMs: Long, endMs: Long): Pair<Map<String, Int>, Map<String, Int>> {
        val entries = database.videoEventsDao().getInWindow(startMs, endMs)
        val channels = entries.groupingBy { it.channel }.eachCount()
        val videos = entries.groupingBy { it.title }.eachCount()
        return channels to videos
    }

    suspend fun enqueueDailyUsageSummary(dateKey: String, deviceId: String, appMinutes: Map<String, Long>) {
        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("appMinutes", JSONObject(appMinutes))
        }.toString()
        enqueueSummary("daily_usage", payload)
    }

    suspend fun enqueueContentSummary(dateKey: String, deviceId: String, topChannels: Map<String, Int>, topVideos: Map<String, Int>) {
        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("topChannels", JSONObject(topChannels))
            put("topVideos", JSONObject(topVideos))
        }.toString()
        enqueueSummary("content_summary", payload)
    }

    suspend fun saveContentAnalysis(dateKey: String, deviceId: String, analyses: List<ChannelAssessment>) {
        if (analyses.isEmpty()) return
        val createdAt = System.currentTimeMillis()
        database.contentAnalysisDao().deleteForDateAndDevice(dateKey, deviceId)
        database.contentAnalysisDao().insertAll(
            analyses.map {
                ContentAnalysisEntity(
                    dateKey = dateKey,
                    deviceId = deviceId,
                    channel = it.channel,
                    label = it.label,
                    reason = it.reason,
                    model = it.model,
                    createdAt = createdAt
                )
            }
        )
    }

    suspend fun enqueueContentAnalysis(
        dateKey: String,
        deviceId: String,
        analyses: List<ChannelAssessment>
    ) {
        if (analyses.isEmpty()) return
        val assessments = JSONArray()
        analyses.forEach { assessment ->
            assessments.put(
                JSONObject().apply {
                    put("channel", assessment.channel)
                    put("label", assessment.label)
                    put("reason", assessment.reason)
                    put("model", assessment.model)
                }
            )
        }

        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("assessments", assessments)
        }.toString()
        enqueueSummary("content_analysis", payload)
    }

    suspend fun enqueueContentEvent(event: VideoEventEntity) {
        val deviceId = DeviceInfoProvider(appContext).getDeviceInfo().deviceId
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("localEventId", event.id)
            put("sessionId", event.sessionId ?: JSONObject.NULL)
            put("packageName", event.packageName)
            put("title", event.title)
            put("channel", event.channel)
            put("timestamp", event.timestamp)
            put("canonicalUrl", event.canonicalUrl ?: JSONObject.NULL)
            put("linkKind", event.linkKind)
            put("linkSource", event.linkSource)
        }.toString()
        enqueueSummary("content_event", payload)
    }

    suspend fun scrubInferredVideoLinks(): Int {
        return database.videoEventsDao().scrubNonExactLinks()
    }

    suspend fun getContentAnalysisForDate(dateKey: String): List<ContentAnalysisEntity> {
        return database.contentAnalysisDao().getForDate(dateKey)
    }

    suspend fun getContentAnalysisForDate(dateKey: String, deviceId: String): List<ContentAnalysisEntity> {
        return database.contentAnalysisDao().getForDateAndDevice(dateKey, deviceId)
    }

    suspend fun getRecentVideoEvents(limit: Int = 20): List<VideoEventEntity> {
        val end = System.currentTimeMillis()
        val start = end - 24 * 60 * 60 * 1000L
        return database.videoEventsDao().getInWindow(start, end).take(limit)
    }

    suspend fun getPendingSync() = database.syncQueueDao().getPending()

    suspend fun markSyncDone(id: Long) {
        database.syncQueueDao().markSynced(id)
    }

    suspend fun pruneOldTelemetry(nowMillis: Long = System.currentTimeMillis()) {
        val telemetryCutoff = nowMillis - TELEMETRY_RETENTION_MS
        val evidenceCutoff = nowMillis - EvidencePreferences.RETENTION_DAYS * ONE_DAY_MS

        database.appUsageDao().deleteOlderThan(telemetryCutoff)
        database.videoEventsDao().deleteOlderThan(evidenceCutoff)
        database.sessionInsightDao().deleteOlderThan(evidenceCutoff)
        database.faceObservationDao().getOlderThan(evidenceCutoff).forEach {
            evidenceFiles.deletePath(it.cropPath)
        }
        database.faceObservationDao().deleteOlderThan(evidenceCutoff)

        database.sessionScreenshotDao().getOlderThan(evidenceCutoff).forEach {
            evidenceFiles.deletePath(it.filePath)
        }
        database.sessionScreenshotDao().deleteOlderThan(evidenceCutoff)
        database.identityClusterDao().getAll().filter { it.updatedAt < evidenceCutoff }.forEach {
            evidenceFiles.deletePath(it.representativeCropPath)
        }
        database.identityClusterDao().deleteOlderThan(evidenceCutoff)
        database.activitySessionDao().deleteOlderThan(evidenceCutoff)
    }

    data class ChannelAssessment(
        val channel: String,
        val label: String,
        val reason: String,
        val model: String
    )

    data class ActivitySessionDetail(
        val session: ActivitySessionEntity,
        val screenshots: List<SessionScreenshotEntity>,
        val videos: List<VideoEventEntity>,
        val faceObservations: List<FaceObservationWithCluster>,
        val insight: SessionInsightEntity?
    )

    data class ContentEventSyncPayload(
        val deviceId: String,
        val localEventId: Long,
        val sessionId: Long?,
        val packageName: String,
        val title: String,
        val channel: String,
        val timestamp: Long,
        val canonicalUrl: String?,
        val fallbackUrl: String?,
        val linkKind: String,
        val linkSource: String
    )

    data class ActivityFeedPage(
        val items: List<ActivitySessionEntity>,
        val nextCursorStartTime: Long?,
        val hasMore: Boolean
    )

    data class ActivityFeedCounts(
        val totalSessions: Int,
        val reviewCount: Int,
        val unknownCount: Int
    )

    data class FaceObservationWithCluster(
        val observation: FaceObservationEntity,
        val cluster: IdentityClusterEntity?
    )

    data class CapturePolicy(
        val trackSessions: Boolean,
        val allowScreenshots: Boolean,
        val allowFaceCapture: Boolean
    )

    data class InvalidEvidenceCleanupSummary(
        val removedScreenshots: Int,
        val removedFaces: Int
    ) {
        val totalRemoved: Int
            get() = removedScreenshots + removedFaces
    }

    data class MonitoringCategorySummary(
        val category: String,
        val label: String,
        val subtitle: String,
        val installedCount: Int,
        val trackedCount: Int
    )

    data class MonitoringSummary(
        val policies: List<MonitoredAppPolicyEntity>,
        val trackedCount: Int,
        val screenshotCount: Int,
        val faceCaptureCount: Int,
        val recommendedCategoryCounts: List<MonitoringCategorySummary>
    )

    private data class LaunchableApp(
        val packageName: String,
        val displayName: String
    )

    private suspend fun refreshSessionDerivedState(sessionId: Long) {
        recomputeSessionInsight(sessionId, preferAi = false)
    }

    private suspend fun recomputeSessionInsight(
        sessionId: Long,
        preferAi: Boolean
    ): Boolean {
        val session = database.activitySessionDao().getById(sessionId) ?: return false
        val screenshots = database.sessionScreenshotDao().getForSession(sessionId)
        val faceObservations = database.faceObservationDao().getForSession(sessionId)
        val videos = database.videoEventsDao().getForSession(sessionId)
        val primaryClusterId = faceObservations.groupingBy { it.clusterId }.eachCount()
            .filterKeys { it != null }
            .maxByOrNull { it.value }
            ?.key
        val primaryCluster = primaryClusterId?.let { database.identityClusterDao().getById(it) }
        val assignedProfile = session.assignedPersonProfileId?.let { database.personProfileDao().getById(it) }
        val clusterProfile = primaryCluster?.personProfileId?.let { database.personProfileDao().getById(it) }
        val resolvedProfile = assignedProfile ?: clusterProfile
        val identityLabel = when {
            assignedProfile != null -> assignedProfile.name
            !session.assignedPersonName.isNullOrBlank() && session.assignedPersonProfileId == null -> session.assignedPersonName
            clusterProfile != null -> clusterProfile.name
            faceObservations.isEmpty() -> null
            !primaryCluster?.label.isNullOrBlank() -> primaryCluster?.label
            else -> "Unknown viewer"
        }
        val childAges = database.personProfileDao().getChildren()
            .mapNotNull { it.ageYears }
            .distinct()
            .sorted()
        val derivedSession = session.copy(
            screenshotCount = screenshots.size,
            faceObservationCount = faceObservations.size,
            videoEventCount = videos.size,
            assignedPersonProfileId = assignedProfile?.id,
            assignedPersonName = assignedProfile?.name,
            assignedPersonRole = assignedProfile?.role,
            assignedPersonAgeYears = assignedProfile?.ageYears,
            primaryClusterId = primaryClusterId,
            primaryIdentityLabel = identityLabel
        )
        val analyses = getSessionAnalyses(derivedSession, videos)
        val heuristicInsight = SessionInsightGenerator.generate(
            session = derivedSession,
            videos = videos,
            analyses = analyses,
            identityLabel = identityLabel,
            resolvedProfile = resolvedProfile,
            childAges = childAges
        )
        val inputHash = buildSessionSuitabilityInputHash(
            session = derivedSession,
            videos = videos,
            analyses = analyses,
            identityLabel = identityLabel,
            resolvedProfile = resolvedProfile,
            childAges = childAges
        )
        val now = System.currentTimeMillis()
        val keepExistingAi = session.kidFriendlyModel == OpenAiSessionSuitabilityAnalyzer.ANALYSIS_MODEL &&
            session.kidFriendlyInputHash == inputHash &&
            session.kidFriendlyScore in 1..10 &&
            session.summary.isNotBlank() &&
            session.recommendation.isNotBlank()

        val currentInsight = database.sessionInsightDao().getForSession(sessionId)
        val baseSession = derivedSession.copy(
            representativeScreenshotPath = screenshots.firstOrNull()?.filePath,
            attentionLevel = if (keepExistingAi) session.attentionLevel else heuristicInsight.attentionLevel,
            summary = if (keepExistingAi) session.summary else heuristicInsight.headline,
            recommendation = if (keepExistingAi) session.recommendation else heuristicInsight.recommendedAction,
            kidFriendlyScore = if (keepExistingAi) session.kidFriendlyScore else heuristicInsight.kidFriendlyScore,
            kidFriendlyModel = if (keepExistingAi) session.kidFriendlyModel else SessionInsightGenerator.MODEL_NAME,
            kidFriendlyInputHash = inputHash,
            kidFriendlyScoredAt = if (keepExistingAi) {
                session.kidFriendlyScoredAt ?: now
            } else {
                now
            },
            updatedAt = now
        )
        var anyChanged = false
        if (baseSession != session) {
            database.activitySessionDao().update(baseSession)
            anyChanged = true
        }

        if (!keepExistingAi || currentInsight == null || currentInsight.model != OpenAiSessionSuitabilityAnalyzer.ANALYSIS_MODEL) {
            database.sessionInsightDao().insert(
                SessionInsightEntity(
                    sessionId = sessionId,
                    attentionLevel = heuristicInsight.attentionLevel,
                    headline = heuristicInsight.headline,
                    explanation = heuristicInsight.explanation,
                    recommendedAction = heuristicInsight.recommendedAction,
                    model = SessionInsightGenerator.MODEL_NAME,
                    createdAt = now
                )
            )
        }

        if (!preferAi || !OpenAiSessionSuitabilityAnalyzer.isConfigured() || keepExistingAi) {
            return anyChanged
        }

        val aiResult = openAiSessionSuitabilityAnalyzer.assessSession(
            OpenAiSessionSuitabilityAnalyzer.SessionInput(
                appName = baseSession.appName,
                packageName = baseSession.packageName,
                durationMs = baseSession.duration,
                attentionLevel = baseSession.attentionLevel,
                screenshotCount = baseSession.screenshotCount,
                faceObservationCount = baseSession.faceObservationCount,
                identityLabel = identityLabel,
                targetAges = when {
                    resolvedProfile?.role == "child" && resolvedProfile.ageYears != null -> listOf(resolvedProfile.ageYears)
                    else -> childAges
                },
                assignedPersonName = resolvedProfile?.name ?: baseSession.assignedPersonName,
                assignedPersonRole = resolvedProfile?.role ?: baseSession.assignedPersonRole,
                videos = videos,
                analyses = analyses
            )
        ) ?: return anyChanged

        val latestSession = database.activitySessionDao().getById(sessionId) ?: return anyChanged
        if (latestSession.kidFriendlyInputHash != inputHash && latestSession.updatedAt > now) {
            return anyChanged
        }
        val aiSession = latestSession.copy(
            attentionLevel = aiResult.attentionLevel,
            summary = aiResult.headline,
            recommendation = aiResult.recommendedAction,
            kidFriendlyScore = aiResult.kidFriendlyScore,
            kidFriendlyModel = OpenAiSessionSuitabilityAnalyzer.ANALYSIS_MODEL,
            kidFriendlyInputHash = inputHash,
            kidFriendlyScoredAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        if (aiSession != latestSession) {
            database.activitySessionDao().update(aiSession)
            anyChanged = true
        }
        database.sessionInsightDao().insert(
            SessionInsightEntity(
                sessionId = sessionId,
                attentionLevel = aiResult.attentionLevel,
                headline = aiResult.headline,
                explanation = aiResult.explanation,
                recommendedAction = aiResult.recommendedAction,
                model = OpenAiSessionSuitabilityAnalyzer.ANALYSIS_MODEL,
                createdAt = System.currentTimeMillis()
            )
        )
        return true
    }

    private suspend fun getSessionAnalyses(
        session: ActivitySessionEntity,
        videos: List<VideoEventEntity>
    ): List<ContentAnalysisEntity> {
        if (videos.isEmpty()) return emptyList()
        val dateKey = Instant.ofEpochMilli(session.startTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val analyses = database.contentAnalysisDao().getForDate(dateKey)
        val channels = videos.map { it.channel.lowercase() }.toSet()
        return analyses.filter { it.channel.lowercase() in channels }
    }

    private suspend fun findMatchingProfile(
        name: String,
        role: String,
        isDeviceOwner: Boolean
    ): PersonProfileEntity? {
        val normalizedName = name.trim().lowercase()
        return database.personProfileDao().getAll().firstOrNull { profile ->
            profile.isDeviceOwner == isDeviceOwner &&
                profile.role.equals(role, ignoreCase = true) &&
                profile.name.trim().lowercase() == normalizedName
        }
    }

    private fun dedupeProfiles(profiles: List<PersonProfileEntity>): List<PersonProfileEntity> {
        return profiles
            .sortedByDescending { it.updatedAt }
            .distinctBy { profile ->
                listOf(
                    profile.isDeviceOwner.toString(),
                    profile.role.lowercase(),
                    profile.name.trim().lowercase(),
                    profile.ageYears?.toString().orEmpty()
                ).joinToString("|")
            }
            .sortedWith(
                compareByDescending<PersonProfileEntity> { it.isDeviceOwner }
                    .thenBy { it.role.lowercase() }
                    .thenBy { it.name.lowercase() }
            )
    }

    private suspend fun normalizeDuplicateOwners(
        primaryOwner: PersonProfileEntity,
        duplicates: List<PersonProfileEntity>
    ) {
        if (duplicates.isEmpty()) return
        val now = System.currentTimeMillis()
        duplicates.forEach { duplicate ->
            database.identityClusterDao().getForPersonProfile(duplicate.id).forEach { cluster ->
                database.identityClusterDao().update(
                    cluster.copy(
                        label = primaryOwner.name,
                        role = primaryOwner.role,
                        personProfileId = primaryOwner.id,
                        updatedAt = now
                    )
                )
            }
            database.activitySessionDao().getSince(0L)
                .filter { it.assignedPersonProfileId == duplicate.id }
                .forEach { session ->
                    database.activitySessionDao().update(
                        session.copy(
                            assignedPersonProfileId = primaryOwner.id,
                            assignedPersonName = primaryOwner.name,
                            assignedPersonRole = primaryOwner.role,
                            assignedPersonAgeYears = primaryOwner.ageYears,
                            updatedAt = now
                        )
                    )
                }
            database.personProfileDao().deleteById(duplicate.id)
        }
        refreshSessionsLinkedToPerson(primaryOwner.id)
    }

    private suspend fun upsertNamedProfile(
        name: String,
        role: String,
        ageYears: Int?,
        isDeviceOwner: Boolean
    ): PersonProfileEntity {
        if (isDeviceOwner) {
            return updateDeviceOwnerProfile(name)
        }
        val trimmedName = name.trim().ifBlank { defaultLabelForRole(role) }
        val sanitizedAge = sanitizeAge(ageYears)
        val now = System.currentTimeMillis()
        val existing = findMatchingProfile(trimmedName, role, isDeviceOwner)
        val updated = if (existing != null) {
            existing.copy(
                name = trimmedName,
                role = role,
                ageYears = if (role == "child") sanitizedAge else null,
                isDeviceOwner = false,
                updatedAt = now
            )
        } else {
            PersonProfileEntity(
                name = trimmedName,
                role = role,
                ageYears = if (role == "child") sanitizedAge else null,
                isDeviceOwner = false,
                createdAt = now,
                updatedAt = now
            )
        }
        val saved = if (existing != null) {
            if (updated != existing) {
                database.personProfileDao().update(updated)
                syncClustersForProfile(updated)
                refreshSessionsLinkedToPerson(updated.id)
            }
            updated
        } else {
            updated.copy(id = database.personProfileDao().insert(updated))
        }
        return saved
    }

    private suspend fun syncClustersForProfile(profile: PersonProfileEntity) {
        database.identityClusterDao().getForPersonProfile(profile.id).forEach { cluster ->
            database.identityClusterDao().update(
                cluster.copy(
                    label = profile.name,
                    role = profile.role,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun refreshSessionsLinkedToPerson(profileId: Long) {
        val linkedClusterIds = database.identityClusterDao().getForPersonProfile(profileId).map { it.id }.toSet()
        database.activitySessionDao().getSince(0L)
            .filter { session ->
                session.assignedPersonProfileId == profileId || session.primaryClusterId in linkedClusterIds
            }
            .forEach { refreshSessionDerivedState(it.id) }
    }

    private fun sanitizeAge(ageYears: Int?): Int? {
        return ageYears?.coerceIn(1, 17)
    }

    private fun defaultLabelForRole(role: String): String {
        return when (role.lowercase()) {
            "parent" -> "Parent"
            "caregiver" -> "Caregiver"
            "other" -> "Other viewer"
            else -> "Child"
        }
    }

    private fun buildSessionSuitabilityInputHash(
        session: ActivitySessionEntity,
        videos: List<VideoEventEntity>,
        analyses: List<ContentAnalysisEntity>,
        identityLabel: String?,
        resolvedProfile: PersonProfileEntity?,
        childAges: List<Int>
    ): String {
        val videosJson = JSONArray().apply {
            videos.sortedBy { it.timestamp }.forEach { video ->
                put(
                    JSONObject()
                        .put("title", video.title)
                        .put("channel", video.channel)
                        .put("timestamp", video.timestamp)
                )
            }
        }
        val analysesJson = JSONArray().apply {
            analyses.sortedBy { it.channel.lowercase() }.forEach { analysis ->
                put(
                    JSONObject()
                        .put("channel", analysis.channel)
                        .put("label", analysis.label)
                        .put("reason", analysis.reason)
                )
            }
        }
        val raw = JSONObject()
            .put("appName", session.appName)
            .put("packageName", session.packageName)
            .put("duration", session.duration)
            .put("screenshotCount", session.screenshotCount)
            .put("faceObservationCount", session.faceObservationCount)
            .put("videoEventCount", session.videoEventCount)
            .put("identityLabel", identityLabel ?: "")
            .put("assignedRole", resolvedProfile?.role ?: session.assignedPersonRole ?: "")
            .put("assignedAge", resolvedProfile?.ageYears ?: session.assignedPersonAgeYears ?: JSONObject.NULL)
            .put("childAges", JSONArray(childAges))
            .put("videos", videosJson)
            .put("analyses", analysesJson)
            .toString()
        return sha256(raw)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun attachUnassignedVideoEvents(
        sessionId: Long,
        packageName: String,
        startTime: Long,
        endTime: Long
    ) {
        if (!AccessibilityCaptureCatalog.isSupportedPackage(packageName)) return
        database.videoEventsDao().getUnassignedInWindow(startTime - VIDEO_ATTACH_GRACE_MS, endTime + VIDEO_ATTACH_GRACE_MS)
            .forEach { event ->
                database.videoEventsDao().attachToSession(event.id, sessionId)
            }
    }

    private suspend fun assignCluster(
        embedding: FloatArray,
        representativeCropPath: String
    ): IdentityClusterEntity {
        val existingClusters = database.identityClusterDao().getAll()
        val bestCluster = existingClusters.maxByOrNull { cluster ->
            cosineSimilarity(embedding, decodeEmbedding(cluster.centroid))
        }
        val similarity = bestCluster?.let { cosineSimilarity(embedding, decodeEmbedding(it.centroid)) } ?: 0f
        if (bestCluster != null && similarity >= FACE_CLUSTER_MATCH_THRESHOLD) {
            val mergedCentroid = averageEmbeddings(
                decodeEmbedding(bestCluster.centroid),
                embedding,
                bestCluster.sampleCount
            )
            val updated = bestCluster.copy(
                centroid = encodeEmbedding(mergedCentroid),
                sampleCount = bestCluster.sampleCount + 1,
                representativeCropPath = bestCluster.representativeCropPath ?: representativeCropPath,
                updatedAt = System.currentTimeMillis()
            )
            database.identityClusterDao().update(updated)
            return updated
        }
        val cluster = IdentityClusterEntity(
            label = null,
            role = "unknown",
            representativeCropPath = representativeCropPath,
            centroid = encodeEmbedding(embedding),
            sampleCount = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val insertedId = database.identityClusterDao().insert(cluster)
        return cluster.copy(id = insertedId)
    }

    private suspend fun repairIdentityClusters() {
        val sessions = database.activitySessionDao().getSince(0L)
        val observationsByCluster = mutableMapOf<Long, MutableList<FaceObservationEntity>>()
        sessions.forEach { session ->
            database.faceObservationDao().getForSession(session.id).forEach { observation ->
                observation.clusterId?.let { clusterId ->
                    observationsByCluster.getOrPut(clusterId) { mutableListOf() }.add(observation)
                }
            }
        }

        val now = System.currentTimeMillis()
        val staleClusterIds = mutableListOf<Long>()
        database.identityClusterDao().getAll().forEach { cluster ->
            val relatedObservations = observationsByCluster[cluster.id]
                .orEmpty()
                .sortedByDescending { it.observedAt }

            if (relatedObservations.isEmpty()) {
                if (!cluster.representativeCropPath.isNullOrBlank() && !File(cluster.representativeCropPath).exists()) {
                    evidenceFiles.deletePath(cluster.representativeCropPath)
                }
                staleClusterIds += cluster.id
                return@forEach
            }

            val representativePath = relatedObservations
                .mapNotNull { it.cropPath }
                .firstOrNull { path -> File(path).exists() }

            if (cluster.sampleCount != relatedObservations.size || cluster.representativeCropPath != representativePath) {
                database.identityClusterDao().update(
                    cluster.copy(
                        representativeCropPath = representativePath,
                        sampleCount = relatedObservations.size,
                        updatedAt = now
                    )
                )
            }
        }

        if (staleClusterIds.isNotEmpty()) {
            database.identityClusterDao().deleteByIds(staleClusterIds)
        }
    }

    private suspend fun deletePendingContentEventQueueItems(
        localVideoEventIds: Set<Long>,
        sessionId: Long
    ) {
        if (localVideoEventIds.isEmpty()) return
        val queueIdsToDelete = database.syncQueueDao().getPending()
            .filter { it.entityType == "content_event" }
            .mapNotNull { entry ->
                val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull() ?: return@mapNotNull null
                val payloadSessionId = payload.optLong("sessionId", Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE }
                val localEventId = payload.optLong("localEventId", Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE }
                if (payloadSessionId == sessionId || (localEventId != null && localEventId in localVideoEventIds)) {
                    entry.id
                } else {
                    null
                }
            }
        if (queueIdsToDelete.isNotEmpty()) {
            database.syncQueueDao().deleteByIds(queueIdsToDelete)
        }
    }

    private fun resolveAppName(packageName: String): String {
        return runCatching {
            val appInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private suspend fun ensureMonitoredAppPolicy(packageName: String): MonitoredAppPolicyEntity {
        database.monitoredAppPolicyDao().getByPackage(packageName)?.let { return it }
        syncMonitoredAppPolicies()
        database.monitoredAppPolicyDao().getByPackage(packageName)?.let { return it }

        val displayName = resolveAppName(packageName)
        val category = MonitoringPolicyCatalog.inferCategory(packageName, displayName)
        val isEligible = MonitoringPolicyCatalog.isMonitoringShortlistEligible(packageName, category)
        val defaults = if (
            !EvidencePreferences.hasMigratedLegacyAllowlist(appContext) &&
            EvidencePreferences.isLegacyPackageAllowed(appContext, packageName) &&
            isEligible
        ) {
            MonitoringPolicyCatalog.PolicyDefaults(
                trackSessions = true,
                allowScreenshots = true,
                allowFaceCapture = true
            )
        } else if (!isEligible) {
            MonitoringPolicyCatalog.PolicyDefaults(
                trackSessions = false,
                allowScreenshots = false,
                allowFaceCapture = false
            )
        } else {
            MonitoringPolicyCatalog.defaultsFor(category)
        }
        val entry = MonitoredAppPolicyEntity(
            packageName = packageName,
            displayName = displayName,
            category = category,
            isRecommended = isEligible,
            trackSessions = defaults.trackSessions,
            allowScreenshots = defaults.allowScreenshots,
            allowFaceCapture = defaults.allowFaceCapture,
            updatedAt = System.currentTimeMillis()
        )
        database.monitoredAppPolicyDao().upsert(entry)
        return entry
    }

    private fun resolveLaunchableApps(): List<LaunchableApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentActivities(launchIntent, 0)
        }
        return resolved
            .map {
                LaunchableApp(
                    packageName = it.activityInfo.packageName,
                    displayName = it.loadLabel(appContext.packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .filterNot { it.packageName == appContext.packageName }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun resolveVisibleApps(): List<LaunchableApp> {
        val visibleApps = resolveLaunchableApps()
            .associateBy { it.packageName }
            .toMutableMap()
        MonitoringPolicyCatalog.monitoringShortlistPackages().forEach { packageName ->
            if (visibleApps.containsKey(packageName)) return@forEach
            resolveInstalledApp(packageName)?.let { visibleApps[packageName] = it }
        }
        return visibleApps.values
            .filterNot { it.packageName == appContext.packageName }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun resolveInstalledApp(packageName: String): LaunchableApp? {
        return runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationInfo(packageName, 0)
            }
            LaunchableApp(
                packageName = packageName,
                displayName = appContext.packageManager.getApplicationLabel(appInfo).toString()
            )
        }.getOrNull()
    }

    private fun defaultsToCapturePolicy(defaults: MonitoringPolicyCatalog.PolicyDefaults): CapturePolicy {
        return CapturePolicy(
            trackSessions = defaults.trackSessions,
            allowScreenshots = defaults.allowScreenshots,
            allowFaceCapture = defaults.allowFaceCapture
        )
    }

    private fun isEligibleMonitoringPolicy(policy: MonitoredAppPolicyEntity): Boolean {
        return MonitoringPolicyCatalog.isMonitoringShortlistEligible(
            packageName = policy.packageName,
            category = policy.category
        )
    }

    private fun resolvePreferredLinkKind(currentKind: String, incomingKind: String): String {
        return when {
            linkKindScore(incomingKind) > linkKindScore(currentKind) -> incomingKind
            currentKind.isBlank() -> incomingKind
            else -> currentKind
        }
    }

    private fun resolvePreferredLinkSource(
        currentKind: String,
        currentSource: String,
        incomingKind: String,
        incomingSource: String
    ): String {
        return when {
            linkKindScore(incomingKind) > linkKindScore(currentKind) -> incomingSource
            currentSource.isBlank() || currentSource == "none" -> incomingSource
            else -> currentSource
        }
    }

    private fun linkKindScore(kind: String): Int {
        return when (kind.lowercase()) {
            "exact" -> 1
            else -> 0
        }
    }

    private fun averageEmbeddings(base: FloatArray, next: FloatArray, baseSamples: Int): FloatArray {
        val merged = FloatArray(base.size)
        val weight = baseSamples.coerceAtLeast(1).toFloat()
        for (index in merged.indices) {
            merged[index] = ((base[index] * weight) + next[index]) / (weight + 1f)
        }
        return normalize(merged)
    }

    private fun normalize(values: FloatArray): FloatArray {
        var sum = 0f
        values.forEach { sum += it * it }
        val factor = if (sum > 0f) 1f / sqrt(sum) else 1f
        return values.map { it * factor }.toFloatArray()
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        val size = minOf(left.size, right.size)
        if (size == 0) return 0f
        var dot = 0f
        for (index in 0 until size) {
            dot += left[index] * right[index]
        }
        return dot
    }

    private fun encodeEmbedding(embedding: FloatArray): String {
        return embedding.joinToString(",")
    }

    private fun decodeEmbedding(encoded: String): FloatArray {
        return encoded.split(',')
            .mapNotNull { it.toFloatOrNull() }
            .toFloatArray()
    }

    private companion object {
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val SESSION_MERGE_WINDOW_MS = 2 * 60 * 1000L
        private const val VIDEO_EVENT_MIN_INTERVAL_MS = 20_000L
        private const val TELEMETRY_RETENTION_MS = 7L * ONE_DAY_MS
        private const val VIDEO_ATTACH_GRACE_MS = 2 * 60 * 1000L
        private const val FACE_CLUSTER_MATCH_THRESHOLD = 0.92f
        private const val MAX_SCREENSHOTS_PER_SESSION = 8
        private const val ACTIVE_SESSION_FRESHNESS_MS = 12_000L
        private const val NO_PACKAGE_PLACEHOLDER = "__none__"
    }
}
