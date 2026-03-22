package com.kidwatch.app.services

object MonitoringPolicyCatalog {

    const val SHORTLIST_YOUTUBE = "com.google.android.youtube"
    const val SHORTLIST_YOUTUBE_KIDS = "com.google.android.apps.youtube.kids"
    const val SHORTLIST_NETFLIX = "com.netflix.mediaclient"
    const val SHORTLIST_PRIME_VIDEO = "com.amazon.avod.thirdpartyclient"
    const val SHORTLIST_HOTSTAR = "in.startv.hotstar"
    const val SHORTLIST_JIOCINEMA = "com.jio.media.ondemand"
    const val SHORTLIST_MX_PLAYER = "com.mxtech.videoplayer.ad"
    const val SHORTLIST_INSTAGRAM = "com.instagram.android"
    const val SHORTLIST_WHATSAPP = "com.whatsapp"
    const val SHORTLIST_SNAPCHAT = "com.snapchat.android"
    const val SHORTLIST_FACEBOOK = "com.facebook.katana"
    const val SHORTLIST_MESSENGER = "com.facebook.orca"
    const val SHORTLIST_THREADS = "com.instagram.barcelona"
    const val SHORTLIST_TELEGRAM = "org.telegram.messenger"
    const val SHORTLIST_X = "com.twitter.android"
    const val SHORTLIST_REDDIT = "com.reddit.frontpage"
    const val SHORTLIST_DISCORD = "com.discord"

    data class PolicyDefaults(
        val trackSessions: Boolean,
        val allowScreenshots: Boolean,
        val allowFaceCapture: Boolean
    )

    data class CategoryDefinition(
        val key: String,
        val label: String,
        val subtitle: String,
        val defaults: PolicyDefaults
    )

    const val VIDEO_SHORTS = "video_shorts"
    const val SOCIAL_CHAT = "social_chat"
    const val GAMES = "games"
    const val BROWSERS_SEARCH = "browsers_search"
    const val LEARNING = "learning"
    const val OTHER = "other"

    private val definitions = listOf(
        CategoryDefinition(
            key = VIDEO_SHORTS,
            label = "Video & Shorts",
            subtitle = "YouTube, OTT apps, reels, and short-form video",
            defaults = PolicyDefaults(trackSessions = true, allowScreenshots = true, allowFaceCapture = true)
        ),
        CategoryDefinition(
            key = SOCIAL_CHAT,
            label = "Social & Chat",
            subtitle = "Messaging, social feeds, and community apps",
            defaults = PolicyDefaults(trackSessions = true, allowScreenshots = true, allowFaceCapture = true)
        ),
        CategoryDefinition(
            key = GAMES,
            label = "Games",
            subtitle = "Arcade, multiplayer, and entertainment gaming apps",
            defaults = PolicyDefaults(trackSessions = true, allowScreenshots = true, allowFaceCapture = true)
        ),
        CategoryDefinition(
            key = BROWSERS_SEARCH,
            label = "Browsers & Search",
            subtitle = "Web browsers and search-heavy discovery apps",
            defaults = PolicyDefaults(trackSessions = true, allowScreenshots = true, allowFaceCapture = true)
        ),
        CategoryDefinition(
            key = LEARNING,
            label = "Learning",
            subtitle = "School, reading, and age-positive learning apps",
            defaults = PolicyDefaults(trackSessions = true, allowScreenshots = false, allowFaceCapture = false)
        )
    )

    private val packageCategoryOverrides = mapOf(
        SHORTLIST_YOUTUBE to VIDEO_SHORTS,
        SHORTLIST_YOUTUBE_KIDS to VIDEO_SHORTS,
        "com.google.android.apps.youtube.music" to VIDEO_SHORTS,
        SHORTLIST_NETFLIX to VIDEO_SHORTS,
        SHORTLIST_PRIME_VIDEO to VIDEO_SHORTS,
        SHORTLIST_HOTSTAR to VIDEO_SHORTS,
        SHORTLIST_JIOCINEMA to VIDEO_SHORTS,
        SHORTLIST_MX_PLAYER to VIDEO_SHORTS,
        "com.zhiliaoapp.musically" to SOCIAL_CHAT,
        SHORTLIST_INSTAGRAM to SOCIAL_CHAT,
        SHORTLIST_WHATSAPP to SOCIAL_CHAT,
        SHORTLIST_SNAPCHAT to SOCIAL_CHAT,
        SHORTLIST_FACEBOOK to SOCIAL_CHAT,
        SHORTLIST_MESSENGER to SOCIAL_CHAT,
        SHORTLIST_THREADS to SOCIAL_CHAT,
        SHORTLIST_TELEGRAM to SOCIAL_CHAT,
        SHORTLIST_X to SOCIAL_CHAT,
        SHORTLIST_REDDIT to SOCIAL_CHAT,
        SHORTLIST_DISCORD to SOCIAL_CHAT,
        "com.roblox.client" to GAMES,
        "com.king.candycrushsaga" to GAMES,
        "com.supercell.clashofclans" to GAMES,
        "com.dts.freefireth" to GAMES,
        "com.pubg.imobile" to GAMES,
        "com.mojang.minecraftpe" to GAMES,
        "com.android.chrome" to BROWSERS_SEARCH,
        "org.mozilla.firefox" to BROWSERS_SEARCH,
        "com.microsoft.emmx" to BROWSERS_SEARCH,
        "com.google.android.googlequicksearchbox" to BROWSERS_SEARCH,
        "com.google.android.apps.classroom" to LEARNING,
        "com.google.android.apps.kids.familylink" to LEARNING,
        "org.khanacademy.android" to LEARNING,
        "com.byjus.thelearningapp" to LEARNING,
        "com.duolingo" to LEARNING,
        "com.microsoft.teams" to LEARNING,
        "us.zoom.videomeetings" to LEARNING
    )

    private val categoryKeywords = mapOf(
        VIDEO_SHORTS to listOf("youtube", "shorts", "video", "reels", "netflix", "prime video", "hotstar", "ott", "mx player"),
        SOCIAL_CHAT to listOf("instagram", "whatsapp", "snapchat", "facebook", "messenger", "discord", "reddit", "chat", "social", "tiktok"),
        GAMES to listOf("game", "roblox", "minecraft", "free fire", "pubg", "candy crush", "clash", "arcade", "ludo"),
        BROWSERS_SEARCH to listOf("chrome", "browser", "search", "firefox", "edge", "google go", "internet"),
        LEARNING to listOf("learn", "study", "classroom", "school", "kids", "math", "phonics", "reading", "khan", "byju", "duolingo", "zoom", "teams")
    )

    fun definitions(): List<CategoryDefinition> = definitions

    fun definitionFor(category: String): CategoryDefinition? =
        definitions.firstOrNull { it.key == category }

    fun displayLabel(category: String): String =
        definitionFor(category)?.label ?: "Other apps"

    fun subtitle(category: String): String =
        definitionFor(category)?.subtitle ?: "Apps not in the current curated list"

    fun isRecommended(category: String): Boolean = category != OTHER

    fun defaultsFor(category: String): PolicyDefaults =
        definitionFor(category)?.defaults ?: PolicyDefaults(
            trackSessions = false,
            allowScreenshots = false,
            allowFaceCapture = false
        )

    fun isMonitoringShortlistEligible(packageName: String, category: String): Boolean {
        return packageName in shortlistPackages() || category == GAMES
    }

    fun monitoringShortlistPackages(): List<String> = shortlistPackages().toList()

    fun contentCapturePackages(): Set<String> = videoShortlistPackages() + socialShortlistPackages()

    fun isVideoShortlistPackage(packageName: String): Boolean = packageName in videoShortlistPackages()

    fun isSocialShortlistPackage(packageName: String): Boolean = packageName in socialShortlistPackages()

    fun shortlistDisplayName(packageName: String): String? = shortlistDisplayNames[packageName]

    fun monitoringShortlistRank(packageName: String, category: String): Int {
        val shortlistRank = monitoringShortlistPackages().indexOf(packageName)
        return when {
            shortlistRank >= 0 -> shortlistRank
            category == GAMES -> 100
            else -> 200
        }
    }

    fun inferCategory(packageName: String, displayName: String): String {
        packageCategoryOverrides[packageName]?.let { return it }
        val normalized = "$packageName ${displayName.lowercase()}"
        return definitions.firstNotNullOfOrNull { definition ->
            val keywords = categoryKeywords[definition.key].orEmpty()
            definition.key.takeIf { keywords.any { normalized.contains(it) } }
        } ?: OTHER
    }

    private fun videoShortlistPackages(): Set<String> = linkedSetOf(
        SHORTLIST_YOUTUBE,
        SHORTLIST_YOUTUBE_KIDS,
        SHORTLIST_NETFLIX,
        SHORTLIST_PRIME_VIDEO,
        SHORTLIST_HOTSTAR,
        SHORTLIST_JIOCINEMA,
        SHORTLIST_MX_PLAYER
    )

    private fun socialShortlistPackages(): Set<String> = linkedSetOf(
        SHORTLIST_INSTAGRAM,
        SHORTLIST_WHATSAPP,
        SHORTLIST_SNAPCHAT,
        SHORTLIST_FACEBOOK,
        SHORTLIST_MESSENGER,
        SHORTLIST_THREADS,
        SHORTLIST_TELEGRAM,
        SHORTLIST_X,
        SHORTLIST_REDDIT,
        SHORTLIST_DISCORD
    )

    private fun shortlistPackages(): Set<String> = linkedSetOf<String>().apply {
        addAll(videoShortlistPackages())
        addAll(socialShortlistPackages())
    }

    private val shortlistDisplayNames = mapOf(
        SHORTLIST_YOUTUBE to "YouTube",
        SHORTLIST_YOUTUBE_KIDS to "YouTube Kids",
        SHORTLIST_NETFLIX to "Netflix",
        SHORTLIST_PRIME_VIDEO to "Prime Video",
        SHORTLIST_HOTSTAR to "Disney+ Hotstar",
        SHORTLIST_JIOCINEMA to "JioCinema",
        SHORTLIST_MX_PLAYER to "MX Player",
        SHORTLIST_INSTAGRAM to "Instagram",
        SHORTLIST_WHATSAPP to "WhatsApp",
        SHORTLIST_SNAPCHAT to "Snapchat",
        SHORTLIST_FACEBOOK to "Facebook",
        SHORTLIST_MESSENGER to "Messenger",
        SHORTLIST_THREADS to "Threads",
        SHORTLIST_TELEGRAM to "Telegram",
        SHORTLIST_X to "X",
        SHORTLIST_REDDIT to "Reddit",
        SHORTLIST_DISCORD to "Discord"
    )
}
