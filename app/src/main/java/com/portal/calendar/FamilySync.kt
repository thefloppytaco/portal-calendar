package com.portal.calendar

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hub-and-spoke family-data sync over the LAN — no server, no cloud, no GMS.
 *
 * One Portal is the **hub** (source of truth). It advertises itself on the
 * local network via mDNS ([android.net.nsd]) and serves the shared data through
 * the existing [ConfigServer]. Other Portals are **spokes**: they auto-discover
 * the hub (or you type its IP), poll it for the shared family data, and route
 * every local edit to it — so chores, stars, lists, meals and members stay in
 * step across all the family's screens.
 *
 * Conflict handling is deliberately simple: the hub is authoritative. A spoke
 * applies an edit locally for instant feedback, pushes the same action to the
 * hub, then immediately re-pulls the hub's snapshot — which overwrites any local
 * divergence (e.g. an optimistic add gets the hub's id). Good enough for a
 * handful of household devices; no CRDT needed.
 */
object FamilySync {
    const val ROLE_SOLO = "solo"
    const val ROLE_HUB = "hub"
    const val ROLE_SPOKE = "spoke"

    private const val SERVICE_TYPE = "_portalhub._tcp."
    private const val SERVICE_NAME = "PortalHub"
    private const val POLL_MS = 4_000L
    private const val FULL_EVERY = 8        // mirror-all config pull cadence (×POLL_MS ≈ 32s)

    /**
     * Family data mirrored hub⇄spoke. magic_done.json stays LOCAL (per-device
     * command-dedup); calendar feeds + credentials are NOT here — those ride the
     * optional full-mirror path ([mirrorAll]) instead.
     */
    private val SHARED_FILES = listOf(
        "members.json", "lists.json", "chores.json", "chore_done.json",
        "star_goals.json", "chore_history.json", "mealplan.json", "recipes.json",
        "routines.json", "routine_done.json")

    /** Prefs that must survive a full-mirror import — sync identity + this device's physical traits. */
    private val LOCAL_PREFS = setOf(
        "sync_role", "sync_hub_manual", "sync_mirror_all", "sync_full_hash",
        "idle_mode", "idle_yield_min", "idle_takeover", "ui_scale", "orientation")

    private val pushExec = Executors.newSingleThreadExecutor()
    private val pullExec = Executors.newSingleThreadExecutor()
    private val polling = AtomicBoolean(false)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun role(ctx: Context): String = prefs(ctx).getString("sync_role", ROLE_SOLO) ?: ROLE_SOLO
    fun isSpoke(ctx: Context) = role(ctx) == ROLE_SPOKE
    fun isHub(ctx: Context) = role(ctx) == ROLE_HUB
    fun mirrorAll(ctx: Context) = prefs(ctx).getBoolean("sync_mirror_all", false)
    fun manualHub(ctx: Context): String = (prefs(ctx).getString("sync_hub_manual", "") ?: "").trim()

    private val discovered = Collections.synchronizedSet(LinkedHashSet<String>()) // "ip:port"

    /** Effective hub base URL: a typed-in address wins, else the auto-discovered one. */
    fun hubUrl(ctx: Context): String? {
        val m = manualHub(ctx)
        val hostPort = if (m.isNotEmpty()) normalizeHostPort(m)
                       else synchronized(discovered) { discovered.firstOrNull() }
        return hostPort?.let { "http://$it" }
    }

    private fun normalizeHostPort(raw: String): String {
        var s = raw.removePrefix("http://").removePrefix("https://").trim('/')
        if (!s.contains(":")) s += ":" + ConfigServer.PORT
        return s
    }

    fun setConfig(ctx: Context, role: String?, manualHub: String?, mirror: Boolean?) {
        val e = prefs(ctx).edit()
        role?.let { e.putString("sync_role", if (it in listOf(ROLE_SOLO, ROLE_HUB, ROLE_SPOKE)) it else ROLE_SOLO) }
        manualHub?.let { e.putString("sync_hub_manual", it.trim()) }
        mirror?.let { e.putBoolean("sync_mirror_all", it) }
        e.commit()
        applyRole(ctx)
    }

    // ----------------------------------------------------------- lifecycle

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Called from [App.onCreate] and whenever the role/hub config changes. */
    fun applyRole(ctx: Context) {
        teardown(ctx)
        when (role(ctx)) {
            ROLE_HUB -> { acquireMulticast(ctx); registerService(ctx) }
            ROLE_SPOKE -> { acquireMulticast(ctx); startDiscovery(ctx); startPolling(ctx) }
            else -> { /* solo: nothing to run */ }
        }
    }

    private fun teardown(ctx: Context) {
        polling.set(false)
        val m = nsd ?: ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        regListener?.let { runCatching { m?.unregisterService(it) } }; regListener = null
        discListener?.let { runCatching { m?.stopServiceDiscovery(it) } }; discListener = null
        synchronized(discovered) { discovered.clear() }
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        multicastLock = null
    }

    private fun acquireMulticast(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wm.createMulticastLock("portalhub-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun nsd(ctx: Context): NsdManager =
        (nsd ?: (ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).also { nsd = it })

    private fun registerService(ctx: Context) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = ConfigServer.PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        runCatching { nsd(ctx).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun startDiscovery(ctx: Context) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceType.contains("portalhub")) resolve(ctx, s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                // Name only here; we can't map it back to an ip:port, so leave the
                // cached entry — a stale one just fails a fetch and gets retried.
            }
        }
        discListener = l
        runCatching { nsd(ctx).discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    // A fresh resolve listener per call — NsdManager forbids reusing one concurrently.
    private fun resolve(ctx: Context, service: NsdServiceInfo) {
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceResolved(s: NsdServiceInfo) {
                val host = s.host?.hostAddress ?: return
                val entry = "$host:${s.port}"
                synchronized(discovered) {
                    // Newest resolution wins position-0 so hubUrl() prefers it.
                    discovered.remove(entry); val rest = discovered.toList()
                    discovered.clear(); discovered.add(entry); discovered.addAll(rest)
                }
            }
        }
        runCatching { nsd(ctx).resolveService(service, l) }
    }

    // ----------------------------------------------------------- spoke polling

    private fun startPolling(ctx: Context) {
        if (!polling.compareAndSet(false, true)) return
        pullExec.execute {
            var tick = 0
            while (polling.get() && isSpoke(ctx)) {
                runCatching { pullSnapshot(ctx) }
                if (mirrorAll(ctx) && tick % FULL_EVERY == 0) runCatching { pullFull(ctx) }
                tick++
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
            polling.set(false)
        }
    }

    /** Pull the hub's shared family files and overwrite local copies that differ. */
    private fun pullSnapshot(ctx: Context) {
        val base = hubUrl(ctx) ?: return
        val txt = httpGet("$base/api/family") ?: return
        val obj = JSONObject(txt)
        var changed = false
        for (name in SHARED_FILES) {
            if (!obj.has(name)) continue
            val incoming = obj.getString(name)
            val cur = File(ctx.filesDir, name).let { if (it.exists()) it.readText() else "" }
            if (incoming != cur) { Data.writeRaw(ctx, name, incoming); changed = true }
        }
        if (changed) App.instance.onMain { App.instance.notifyDataChanged() }
    }

    /** Mirror-all: pull the hub's whole config bundle (feeds, theme, creds…), keeping local identity. */
    private fun pullFull(ctx: Context) {
        val base = hubUrl(ctx) ?: return
        val code = httpGet("$base/api/family/full") ?: return
        val h = code.hashCode().toString()
        if (h == prefs(ctx).getString("sync_full_hash", "")) return // unchanged since last import
        importPreservingLocal(ctx, code)
        prefs(ctx).edit().putString("sync_full_hash", h).commit()
        App.instance.onMain { App.instance.notifyConfigChanged(); App.instance.notifyDataChanged() }
    }

    private fun importPreservingLocal(ctx: Context, code: String) {
        val p = prefs(ctx)
        // Snapshot the keep-local prefs with their types, restore after the wholesale import.
        val saved = LOCAL_PREFS.mapNotNull { k -> p.all[k]?.let { k to it } }
        ConfigBundle.import(ctx, code) // clears + replaces all prefs and shared files
        val e = p.edit()
        for ((k, v) in saved) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.commit()
    }

    // ----------------------------------------------------------- spoke push

    /**
     * On a spoke, forward a just-applied local edit to the hub, then re-pull so
     * the hub's authoritative result lands. No-op on a hub/solo device. Ordered
     * (single executor) so rapid taps reach the hub in sequence.
     */
    fun pushIfSpoke(ctx: Context, domain: String, payload: String) {
        if (!isSpoke(ctx)) return
        pushExec.execute {
            val base = hubUrl(ctx) ?: return@execute
            runCatching { httpPost("$base/api/$domain", payload) }
            runCatching { pullSnapshot(ctx) }
        }
    }

    // ----------------------------------------------------------- hub-served data

    /** Raw shared-file snapshot the hub hands to spokes (no secrets, no prefs). */
    fun snapshotJson(ctx: Context): String {
        val o = JSONObject()
        for (name in SHARED_FILES) {
            val f = File(ctx.filesDir, name)
            if (f.exists()) o.put(name, f.readText())
        }
        return o.toString()
    }

    fun statusJson(ctx: Context): String = JSONObject()
        .put("role", role(ctx))
        .put("mirrorAll", mirrorAll(ctx))
        .put("manualHub", manualHub(ctx))
        .put("hubUrl", hubUrl(ctx) ?: "")
        .put("myAddress", (myIp(ctx) ?: "?") + ":" + ConfigServer.PORT)
        .put("discovered", JSONArray(synchronized(discovered) { discovered.toList() }))
        .toString()

    private fun myIp(ctx: Context): String? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    // ----------------------------------------------------------- http

    private fun httpGet(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000; conn.readTimeout = 6_000
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }

    private fun httpPost(urlStr: String, body: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000; conn.readTimeout = 6_000
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }
}
