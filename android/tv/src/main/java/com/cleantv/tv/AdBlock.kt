package com.cleantv.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Built-in ad / tracker blocker: links to known ad and tracking hosts are refused before anything is played.
 * (Direct video links and HLS/DASH streams have no ads of their own; this stops ad
 * and tracking beacons that some streams and pages embed.)
 */
object AdBlock {
    private val BLOCKED = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "pagead2.googlesyndication.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adnxs.com", "adsrvr.org", "advertising.com", "amazon-adsystem.com",
        "moatads.com", "scorecardresearch.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com", "adsterra.com",
        "trafficjunky.net", "hilltopads.net", "onclickads.net", "mgid.com", "revcontent.com", "zedo.com",
        "yieldmo.com", "spotxchange.com", "springserve.com", "teads.tv", "inmobi.com", "applovin.com",
        "unityads.unity3d.com", "chartboost.com", "vungle.com", "facebook.net", "connect.facebook.net",
        "adcolony.com", "mopub.com", "branch.io", "app-measurement.com", "crashlytics.com", "hotjar.com",
    )

    @Volatile var blockedCount = 0
        private set

    fun isBlocked(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return BLOCKED.any { h == it || h.endsWith(".$it") }
    }

    /** True when a VPN app on the TV (e.g. Proton VPN) is active; all playback traffic then goes through it. */
    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
