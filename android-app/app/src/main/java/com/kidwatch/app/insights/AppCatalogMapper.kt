package com.kidwatch.app.insights

object AppCatalogMapper {

    data class KnownApp(
        val displayName: String,
        val iconKey: String
    )

    private const val MISC_LABEL = "Misc apps"

    // Curated app catalog for common social, streaming, gaming, and utility apps.
    private val knownAppsByPackage: Map<String, KnownApp> = mapOf(
        "com.google.android.youtube" to KnownApp("YouTube", "youtube"),
        "com.google.android.apps.youtube.kids" to KnownApp("YouTube Kids", "youtube"),
        "com.instagram.android" to KnownApp("Instagram", "instagram"),
        "com.whatsapp" to KnownApp("WhatsApp", "whatsapp"),
        "com.snapchat.android" to KnownApp("Snapchat", "snapchat"),
        "com.zhiliaoapp.musically" to KnownApp("TikTok", "tiktok"),
        "com.facebook.katana" to KnownApp("Facebook", "facebook"),
        "com.facebook.orca" to KnownApp("Messenger", "messenger"),
        "com.instagram.barcelona" to KnownApp("Threads", "instagram"),
        "org.telegram.messenger" to KnownApp("Telegram", "messages"),
        "com.twitter.android" to KnownApp("X", "x"),
        "com.reddit.frontpage" to KnownApp("Reddit", "reddit"),
        "com.discord" to KnownApp("Discord", "discord"),
        "com.spotify.music" to KnownApp("Spotify", "spotify"),
        "com.netflix.mediaclient" to KnownApp("Netflix", "netflix"),
        "com.amazon.avod.thirdpartyclient" to KnownApp("Prime Video", "prime_video"),
        "in.startv.hotstar" to KnownApp("Disney+ Hotstar", "prime_video"),
        "com.jio.media.ondemand" to KnownApp("JioCinema", "prime_video"),
        "com.mxtech.videoplayer.ad" to KnownApp("MX Player", "youtube"),
        "com.google.android.apps.youtube.music" to KnownApp("YouTube Music", "youtube_music"),
        "com.google.android.apps.messaging" to KnownApp("Messages", "messages"),
        "com.google.android.gm" to KnownApp("Gmail", "gmail"),
        "com.google.android.apps.photos" to KnownApp("Google Photos", "google_photos"),
        "com.google.android.apps.maps" to KnownApp("Google Maps", "google_maps"),
        "com.android.chrome" to KnownApp("Chrome", "chrome"),
        "org.mozilla.firefox" to KnownApp("Firefox", "firefox"),
        "com.roblox.client" to KnownApp("Roblox", "roblox"),
        "com.king.candycrushsaga" to KnownApp("Candy Crush", "candy_crush"),
        "com.supercell.clashofclans" to KnownApp("Clash of Clans", "clash_of_clans"),
        "com.dts.freefireth" to KnownApp("Free Fire", "free_fire"),
        "com.pubg.imobile" to KnownApp("PUBG Mobile", "pubg_mobile"),
        "com.mojang.minecraftpe" to KnownApp("Minecraft", "minecraft"),
        "com.google.android.apps.kids.familylink" to KnownApp("Family Link", "family_link"),
        "com.google.android.apps.classroom" to KnownApp("Google Classroom", "classroom"),
        "com.microsoft.teams" to KnownApp("Microsoft Teams", "teams"),
        "us.zoom.videomeetings" to KnownApp("Zoom", "zoom")
    )
    private val packageByDisplayName: Map<String, String> =
        knownAppsByPackage.entries.associate { (pkg, app) -> app.displayName.lowercase() to pkg }
    private val aliasesByDisplayName: Map<String, String> = mapOf(
        "youtube" to "com.google.android.youtube",
        "youtube shorts" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "whatsapp messenger" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "threads" to "com.instagram.barcelona",
        "telegram" to "org.telegram.messenger",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "snapchat" to "com.snapchat.android",
        "x" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "netflix" to "com.netflix.mediaclient",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "hotstar" to "in.startv.hotstar",
        "jiocinema" to "com.jio.media.ondemand"
    )

    fun toDisplaySummary(appMinutesRaw: Map<String, Long>, topLimit: Int = 3): String {
        if (appMinutesRaw.isEmpty()) return "N/A"

        val knownMinutesByName = mutableMapOf<String, Long>()
        var miscMinutes = 0L

        appMinutesRaw.forEach { (rawKey, minutes) ->
            if (minutes <= 0L) return@forEach
            val app = resolveKnownApp(rawKey)
            if (app == null) {
                miscMinutes += minutes
            } else {
                knownMinutesByName[app.displayName] = (knownMinutesByName[app.displayName] ?: 0L) + minutes
            }
        }

        val ranked = knownMinutesByName.entries
            .sortedByDescending { it.value }
            .take(topLimit)
            .map { "${it.key}:${it.value}m" }
            .toMutableList()

        if (miscMinutes > 0L) {
            ranked += "$MISC_LABEL:${miscMinutes}m"
        }

        return ranked.joinToString(", ").ifBlank { "N/A" }
    }

    fun resolvePackageForDisplayName(displayName: String): String? {
        val normalized = displayName.trim().lowercase()
        return packageByDisplayName[normalized]
            ?: aliasesByDisplayName[normalized]
            ?: aliasesByDisplayName.entries.firstOrNull { (alias, _) ->
                normalized.contains(alias) || alias.contains(normalized)
            }?.value
    }

    private fun resolveKnownApp(rawKey: String): KnownApp? {
        val normalizedKey = rawKey.trim()
        if (normalizedKey.isBlank()) return null

        // Firestore keys may be sanitized by replacing dots with underscores.
        val direct = knownAppsByPackage[normalizedKey]
        if (direct != null) return direct

        val deSanitized = normalizedKey.replace('_', '.')
        return knownAppsByPackage[deSanitized]
    }
}
