package com.kidwatch.app.services

import java.util.Locale

object AccessibilityCaptureCatalog {

    data class CapturedContent(
        val packageName: String,
        val title: String,
        val channel: String,
        val canonicalUrl: String? = null,
        val fallbackUrl: String? = null,
        val linkKind: String = LINK_KIND_NONE,
        val linkSource: String = LINK_SOURCE_NONE
    )

    private data class SupportedApp(
        val packageName: String,
        val displayName: String,
        val parser: (SupportedApp, List<String>) -> CapturedContent?
    )

    private val supportedApps = MonitoringPolicyCatalog.contentCapturePackages()
        .map { packageName ->
            SupportedApp(
                packageName = packageName,
                displayName = MonitoringPolicyCatalog.shortlistDisplayName(packageName) ?: packageName,
                parser = when {
                    MonitoringPolicyCatalog.isVideoShortlistPackage(packageName) -> ::parseVideoStyleContent
                    else -> ::parseSocialStyleContent
                }
            )
        }
        .associateBy { it.packageName }

    fun supportedPackages(): Set<String> = supportedApps.keys

    fun isSupportedPackage(packageName: String): Boolean = supportedApps.containsKey(packageName)

    fun displayNameFor(packageName: String): String =
        supportedApps[packageName]?.displayName ?: packageName

    fun parse(
        packageName: String,
        eventTextParts: List<String>,
        windowTextParts: List<String>
    ): CapturedContent? {
        val supportedApp = supportedApps[packageName] ?: return null
        val candidates = sanitizeCandidates(
            packageName = packageName,
            displayName = supportedApp.displayName,
            rawValues = eventTextParts + windowTextParts
        )
        return supportedApp.parser(supportedApp, candidates)
    }

    private fun sanitizeCandidates(
        packageName: String,
        displayName: String,
        rawValues: List<String>
    ): List<String> {
        val normalizedDisplayName = displayName.lowercase(Locale.getDefault())
        return rawValues.asSequence()
            .flatMap { splitCompositeValue(it).asSequence() }
            .map { it.replace(WHITESPACE_REGEX, " ").trim() }
            .filter { it.length >= 3 }
            .filterNot { isNoise(it, packageName, normalizedDisplayName) }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .take(MAX_CANDIDATES)
            .toList()
    }

    private fun isNoise(
        value: String,
        packageName: String,
        normalizedDisplayName: String
    ): Boolean {
        val normalized = value.lowercase(Locale.getDefault())
        if (normalized == normalizedDisplayName) return true
        if (normalized == packageName) return true
        if (normalized in GENERIC_CHROME_LABELS) return true
        if (NOISE_SUBSTRINGS.any { normalized.contains(it) }) return true
        if (normalized.matches(ENGAGEMENT_ONLY_REGEX)) return true
        if (normalized.matches(TIME_ONLY_REGEX)) return true
        if (normalized.matches(STORY_STATUS_REGEX)) return true
        return false
    }

    private fun parseVideoStyleContent(
        app: SupportedApp,
        candidates: List<String>
    ): CapturedContent? {
        val title = candidates
            .maxByOrNull(::scoreTitleCandidate)
            ?.takeIf { scoreTitleCandidate(it) > 0 }
            ?: return null
        val channel = candidates
            .filterNot { it.equals(title, ignoreCase = true) }
            .maxByOrNull(::scoreAccountCandidate)
            ?.takeIf { scoreAccountCandidate(it) > 0 }
            ?: app.displayName
        return buildCapturedContent(
            app = app,
            title = title,
            channel = channel,
            candidates = candidates
        )
    }

    private fun parseSocialStyleContent(
        app: SupportedApp,
        candidates: List<String>
    ): CapturedContent? {
        val initialTitle = candidates
            .filterNot(::looksLikeAccountCandidate)
            .maxByOrNull(::scoreTitleCandidate)
            ?.takeIf { scoreTitleCandidate(it) > 0 }
            ?: candidates.maxByOrNull(::scoreTitleCandidate)
                ?.takeIf { scoreTitleCandidate(it) > 0 }
            ?: return null
        val accountOrContext = candidates
            .filterNot { it.equals(initialTitle, ignoreCase = true) }
            .maxByOrNull(::scoreAccountCandidate)
            ?.takeIf { scoreAccountCandidate(it) > 0 }
            ?: app.displayName
        val title = if (looksGenericSocialTitle(initialTitle) && accountOrContext != app.displayName) {
            accountOrContext
        } else {
            initialTitle
        }
        val channel = extractCreatorName(accountOrContext).ifBlank { accountOrContext }
        return buildCapturedContent(
            app = app,
            title = title,
            channel = channel,
            candidates = candidates
        )
    }

    private fun buildCapturedContent(
        app: SupportedApp,
        title: String,
        channel: String,
        candidates: List<String>
    ): CapturedContent {
        val exactUrl = extractExactUrl(app.packageName, candidates)
        return if (exactUrl != null) {
            CapturedContent(
                packageName = app.packageName,
                title = title,
                channel = channel,
                canonicalUrl = exactUrl,
                linkKind = LINK_KIND_EXACT,
                linkSource = LINK_SOURCE_ACCESSIBILITY_TEXT
            )
        } else {
            CapturedContent(
                packageName = app.packageName,
                title = title,
                channel = channel,
                linkKind = LINK_KIND_NONE,
                linkSource = LINK_SOURCE_NONE
            )
        }
    }

    private fun extractExactUrl(packageName: String, candidates: List<String>): String? {
        return candidates
            .asSequence()
            .mapNotNull { candidate -> normalizePlatformUrl(packageName, candidate) }
            .firstOrNull()
    }

    private fun normalizePlatformUrl(packageName: String, candidate: String): String? {
        val normalized = candidate
            .trim()
            .trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'')
            .trimStart('(', '[', '{', '"', '\'')
        val lowered = normalized.lowercase(Locale.getDefault())
        return when (packageName) {
            MonitoringPolicyCatalog.SHORTLIST_YOUTUBE,
            MonitoringPolicyCatalog.SHORTLIST_YOUTUBE_KIDS -> when {
                lowered.contains("youtube.com/watch") || lowered.contains("youtube.com/shorts/") || lowered.contains("youtu.be/") ->
                    ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_INSTAGRAM -> when {
                lowered.contains("instagram.com/reel/") ||
                    lowered.contains("instagram.com/p/") ||
                    lowered.contains("instagram.com/tv/") ||
                    lowered.contains("instagram.com/stories/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_THREADS -> when {
                lowered.contains("threads.net/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_FACEBOOK,
            MonitoringPolicyCatalog.SHORTLIST_MESSENGER -> when {
                lowered.contains("facebook.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_WHATSAPP -> when {
                lowered.contains("wa.me/") ||
                    lowered.contains("whatsapp.com/channel/") ||
                    lowered.contains("chat.whatsapp.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_SNAPCHAT -> when {
                lowered.contains("snapchat.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_TELEGRAM -> when {
                lowered.contains("t.me/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_X -> when {
                lowered.contains("x.com/") || lowered.contains("twitter.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_REDDIT -> when {
                lowered.contains("reddit.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_DISCORD -> when {
                lowered.contains("discord.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_NETFLIX -> when {
                lowered.contains("netflix.com/title/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_PRIME_VIDEO -> when {
                lowered.contains("primevideo.com/") || lowered.contains("amazon.com/gp/video/detail/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_HOTSTAR -> when {
                lowered.contains("hotstar.com/") || lowered.contains("jiocinema.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_JIOCINEMA -> when {
                lowered.contains("jiocinema.com/") -> ensureHttps(normalized)
                else -> null
            }
            MonitoringPolicyCatalog.SHORTLIST_MX_PLAYER -> when {
                lowered.contains("mxplayer.in/") -> ensureHttps(normalized)
                else -> null
            }
            else -> null
        }
    }

    private fun ensureHttps(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    private fun splitCompositeValue(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(COMPOSITE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun scoreTitleCandidate(value: String): Int {
        val normalized = value.lowercase(Locale.getDefault())
        var score = 0
        if (value.length in 8..90) score += 20
        if (value.contains(' ')) score += 14
        if (value.any { it.isUpperCase() }) score += 6
        if (value.any { it == '(' || it == ')' || it == '-' || it == ':' }) score += 6
        if (looksLikeAccountCandidate(value)) score -= 20
        if (normalized.contains("original audio")) score -= 25
        if (normalized.contains("story")) score -= 20
        if (normalized.contains("watch")) score -= 18
        if (normalized.contains("channel")) score -= 18
        return score
    }

    private fun scoreAccountCandidate(value: String): Int {
        val normalized = value.lowercase(Locale.getDefault())
        var score = 0
        if (looksLikeAccountCandidate(value)) score += 28
        if (normalized.contains("original audio")) score += 12
        if (value.length in 3..50) score += 10
        if (value.contains('_') || value.contains('.') || value.startsWith("@")) score += 10
        if (value.contains(' ')) score -= 4
        return score
    }

    private fun looksLikeAccountCandidate(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.matches(USERNAME_REGEX) ||
            trimmed.lowercase(Locale.getDefault()).contains("original audio")
    }

    private fun looksGenericSocialTitle(value: String): Boolean {
        val normalized = value.lowercase(Locale.getDefault())
        return normalized == "user avatars" || normalized == "action menu"
    }

    private fun extractCreatorName(value: String): String {
        return value
            .substringBefore(" posted ", value)
            .substringBefore(" shared ", value)
            .substringBefore(" liked ", value)
            .substringBefore(" commented ", value)
            .trim()
    }

    private val GENERIC_CHROME_LABELS = setOf(
        "home",
        "reels",
        "shorts",
        "watch",
        "explore",
        "search",
        "notifications",
        "profile",
        "share",
        "comment",
        "comments",
        "like",
        "likes",
        "liked",
        "follow",
        "following",
        "message",
        "messages",
        "shop",
        "menu",
        "create",
        "stories",
        "story",
        "posts",
        "post",
        "friends",
        "video"
    )

    private val NOISE_SUBSTRINGS = setOf(
        "tap to watch more reels",
        "watch more reels",
        "watch again",
        "reels tray container",
        "application icon",
        "go to channel",
        "close all",
        "user avatars",
        "action menu",
        "your story",
        "suggested for you"
    )

    private val COMPOSITE_SPLIT_REGEX = "[•·\\n]+".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val ENGAGEMENT_ONLY_REGEX = "^[0-9., ]+(likes?|views?|comments?|shares?)$".toRegex()
    private val TIME_ONLY_REGEX = "^[0-9]+\\s*(s|sec|secs|m|min|mins|h|hr|hrs|d|day|days|w|week|weeks)$".toRegex()
    private val STORY_STATUS_REGEX = ".*story,\\s*[0-9]+\\s*of\\s*[0-9]+.*".toRegex()
    private val USERNAME_REGEX = "^@?[A-Za-z0-9._]{3,40}$".toRegex()

    private const val MAX_CANDIDATES = 12
    private const val LINK_KIND_EXACT = "exact"
    private const val LINK_KIND_NONE = "none"
    private const val LINK_SOURCE_ACCESSIBILITY_TEXT = "accessibility_text"
    private const val LINK_SOURCE_NONE = "none"
}
