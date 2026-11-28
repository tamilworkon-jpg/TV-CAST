package com.cleantv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var tvLabel: TextView
    private lateinit var chipSort: com.google.android.material.chip.Chip
    private lateinit var chipFolder: com.google.android.material.chip.Chip
    private lateinit var chipShow: com.google.android.material.chip.Chip
    private lateinit var chipReset: com.google.android.material.chip.Chip
    private lateinit var search: EditText
    private val adapter = MediaAdapter { pos -> onItemClick(pos) }
    private var videos = emptyList<Media>()
    private var images = emptyList<Media>()
    private var recent = emptyList<Media>()
    private var history: Map<String, Progress> = emptyMap()
    private var tab = TAB_VIDEOS
    private lateinit var videoFilter: Filter
    private lateinit var imageFilter: Filter
    private var shown = emptyList<Media>()
    /** null = home (tiles + 3 recent items); "videos" / "pictures" / "recent" = a full page. */
    private var page: String? = null

    private var filter: Filter
        get() = if (tab == TAB_PICTURES) imageFilter else videoFilter
        set(f) {
            if (tab == TAB_PICTURES) { imageFilter = f; f.save(this, "images") }
            else { videoFilter = f; f.save(this, "videos") }
            render()
        }

    /** What the grid shows: the tab's items after sort / category / filter / search. */
    private val currentItems: List<Media>
        get() = when (tab) {
            TAB_RECENT -> videoFilter.apply(recent, history, keepOrder = true)
            TAB_VIDEOS -> videoFilter.apply(videos, history)
            else -> imageFilter.apply(images, history)
        }

    private val mediaPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadMedia()
        maybeAskAllFilesAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        tvLabel = findViewById(R.id.tvLabel)

        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter

        tabs.addTab(tabs.newTab().setText("Recent"))
        tabs.addTab(tabs.newTab().setText("Videos"), true)
        tabs.addTab(tabs.newTab().setText("Pictures"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                render()
                if (tab == TAB_RECENT) refreshHistory()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<Button>(R.id.btnTv).setOnClickListener {
            lifecycleScope.launch { connectTv(forcePicker = true)?.let { toast("Using ${it.name}") } }
        }
        findViewById<Button>(R.id.btnDisconnect).apply {
            setOnClickListener { disconnect() }
            setOnLongClickListener { toast("Disconnect from TV"); true }
        }
        findViewById<Button>(R.id.btnRemote).setOnClickListener {
            if (Prefs.lastTv(this)?.paired != true) toast("Select a TV first")
            else startActivity(Intent(this, RemoteActivity::class.java))
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.actVideos).setOnClickListener { openPage("videos") }
        findViewById<Button>(R.id.actPictures).setOnClickListener { openPage("pictures") }
        findViewById<Button>(R.id.actRecent).setOnClickListener { openPage("recent") }
        findViewById<Button>(R.id.btnSeeAll).setOnClickListener { openPage("recent") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.actLink).setOnClickListener { askLink() }
        findViewById<Button>(R.id.actRadio).setOnClickListener { startActivity(Intent(this, RadioActivity::class.java)) }
        findViewById<Button>(R.id.actShowOnTv).setOnClickListener { showOnTv() }
        findViewById<Button>(R.id.actApks).setOnClickListener { showApks() }
        findViewById<Button>(R.id.actVpn).setOnClickListener { vpnPanel() }
        findViewById<Button>(R.id.actRemote).setOnClickListener {
            if (Prefs.lastTv(this)?.paired != true) toast("Select a TV first")
            else startActivity(Intent(this, RemoteActivity::class.java))
        }

        videoFilter = Filter.load(this, "videos")
        imageFilter = Filter.load(this, "images")
        chipSort = findViewById(R.id.chipSort)
        chipFolder = findViewById(R.id.chipFolder)
        chipShow = findViewById(R.id.chipShow)
        chipReset = findViewById(R.id.chipReset)
        search = findViewById(R.id.search)
        chipSort.setOnClickListener { pickSort() }
        chipFolder.setOnClickListener { pickFolder() }
        chipShow.setOnClickListener { pickShow() }
        chipReset.setOnClickListener { search.setText(""); filter = Filter() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q != filter.query) filter = filter.copy(query = q)
            }
        })

        page = intent.getStringExtra(EXTRA_PAGE)
        tab = when (page) { "videos" -> TAB_VIDEOS; "pictures" -> TAB_PICTURES; else -> TAB_RECENT }
        val home = page == null
        tabs.visibility = View.GONE
        findViewById<View>(R.id.tiles).visibility = if (home) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageBar).visibility = if (home) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.pageTitle).text = when (page) { "videos" -> "Videos"; "pictures" -> "Pictures"; else -> "History" }
        search.visibility = if (home) View.GONE else View.VISIBLE
        findViewById<View>(R.id.chips).visibility = if (home) View.GONE else View.VISIBLE

        if (hasMediaPermission()) maybeAskAllFilesAccess() else permLauncher.launch(mediaPermissions)
    }

    private fun openPage(p: String) = startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_PAGE, p))

    override fun onStart() {
        super.onStart()
        ServerService.uiVisible++
    }

    override fun onStop() {
        ServerService.uiVisible = (ServerService.uiVisible - 1).coerceAtLeast(0)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateTvLabel()
        if (hasMediaPermission()) loadMedia()
        refreshHistory()
        refreshVpnTile()
        startHistoryPoll()
    }

    override fun onPause() {
        historyPoll?.cancel()
        historyPoll = null
        super.onPause()
    }

    private var batteryAskedThisRun = false
    private var historyPoll: Job? = null

    /** While this screen is open, keep resume positions in step with the TV (videos watched from the TV remote too). */
    private fun startHistoryPoll() {
        historyPoll?.cancel()
        historyPoll = lifecycleScope.launch {
            while (true) {
                delay(15_000)
                val tv = Prefs.lastTv(this@MainActivity)?.takeIf { it.paired } ?: continue
                val json = withContext(Dispatchers.IO) { TvClient.history(tv, 2500) } ?: continue
                if (json != Prefs.historyJson(this@MainActivity)) {
                    Prefs.saveHistory(this@MainActivity, json)
                    applyHistory()
                }
            }
        }
    }

    /** Reconnects to the TV (following IP changes) and pulls resume positions. No sharing is started. */
    private fun refreshHistory() {
        if (Prefs.lastTv(this)?.paired != true) return
        lifecycleScope.launch {
            // Phone app open = its videos can be browsed from the TV remote too: share while open
            // (stops by itself 2 min after the app is closed and the TV isn't playing anything).
            if (!ServerService.running && withContext(Dispatchers.IO) { Net.localIp() } != null) {
                ServerService.start(this@MainActivity)
                repeat(20) { if (!ServerService.running) delay(100) }
            }
            val tv = TvClient.ensure(this@MainActivity, announce = ServerService.running)
            updateTvLabel(connected = tv != null)
            if (tv == null || !tv.paired) return@launch
            val json = withContext(Dispatchers.IO) { TvClient.history(tv) } ?: return@launch
            Prefs.saveHistory(this@MainActivity, json)
            applyHistory()
        }
    }

    // ------------------------------------------------------------ home actions

    /** VPN tile: shows whether the TV's built-in VPN is on, with Start / Stop / server choice. */
    private fun vpnPanel() {
        lifecycleScope.launch {
            val tv = connectTv() ?: return@launch
            val s = withContext(Dispatchers.IO) { TvClient.vpnState(tv) }
            if (s == null) { toast("Could not reach ${tv.name}"); return@launch }
            showVpnState(s)
            val on = s.optBoolean("up")
            val msg = (if (on) "Connected via ${s.optString("provider")}." else "Not connected.") +
                "\n\nUsed for links and radio (\"Use built-in VPN\" in Play link, now ${if (Prefs.vpn(this@MainActivity)) "on" else "off"}). " +
                "Started here, it stays on until you stop it; otherwise it closes with the player." +
                s.optString("error").takeIf { it.isNotBlank() }?.let { "\n\nLast error: $it" }.orEmpty()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (on) "TV VPN: connected" else "TV VPN: off")
                .setMessage(msg)
                .setPositiveButton(if (on) "Stop VPN" else "Start VPN") { _, _ -> vpnSwitch(tv, !on) }
                .setNeutralButton("Server") { _, _ -> vpnSettings() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun vpnSwitch(tv: Tv, start: Boolean) {
        lifecycleScope.launch {
            val first = withContext(Dispatchers.IO) { if (start) TvClient.vpnStart(tv) else TvClient.vpnStop(tv) }
            if (first == null) { toast("Could not reach ${tv.name}"); return@launch }
            if (start && !first.optBoolean("permitted")) toast("On the TV, choose OK on the VPN prompt (one time)")
            else toast(if (start) "Connecting VPN…" else "VPN stopped")
            // Poll until the tunnel changes state (connecting can take a few seconds).
            repeat(20) {
                kotlinx.coroutines.delay(1500)
                val s = withContext(Dispatchers.IO) { TvClient.vpnState(tv) } ?: return@repeat
                showVpnState(s)
                if (s.optBoolean("up") == start) {
                    toast(if (start) "TV VPN connected (${s.optString("provider")})" else "TV VPN off")
                    return@launch
                }
                if (start && s.optString("error").isNotBlank() && s.optBoolean("permitted")) {
                    toast("VPN couldn't connect: ${s.optString("error")}")
                    return@launch
                }
            }
        }
    }

    private var vpnTileTint: android.content.res.ColorStateList? = null

    private fun showVpnState(s: JSONObject?) {
        val on = s?.optBoolean("up") == true
        findViewById<com.google.android.material.button.MaterialButton>(R.id.actVpn).apply {
            if (vpnTileTint == null) vpnTileTint = backgroundTintList
            text = when { s == null -> "VPN"; on -> "VPN ON"; else -> "VPN off" }
            // Highlighted green while the TV's VPN is connected.
            backgroundTintList = if (on) android.content.res.ColorStateList.valueOf(0xFF1B8E3E.toInt()) else vpnTileTint
            setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text))
            iconTint = android.content.res.ColorStateList.valueOf(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text))
        }
    }

    private fun refreshVpnTile() {
        val tv = Prefs.lastTv(this)?.takeIf { it.paired } ?: return
        lifecycleScope.launch { showVpnState(withContext(Dispatchers.IO) { TvClient.vpnState(tv) }) }
    }

    private fun showOnTv() {
        lifecycleScope.launch {
            val tv = connectTv() ?: return@launch
            if (!startSharing()) return@launch
            val ok = withContext(Dispatchers.IO) {
                val ip = Net.localIpFor(tv.ip) ?: return@withContext false
                TvClient.hello(tv, "http://$ip:${MediaServer.PORT}/${Prefs.token(this@MainActivity)}", TvClient.phoneName(this@MainActivity))
            }
            toast(if (ok) "Open TV Cast on the TV to browse this phone" else "TV not reachable")
        }
    }

    /**
     * Online link. The TV keeps nothing about it; played links are remembered only on this phone
     * (tap to replay, long-press to remove, Clear history removes all).
     */
    private fun askLink() {
        val dp = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            hint = "https://…"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            // A pasted-in clipboard link is replaced (not appended to) when typing.
            setSelectAllOnFocus(true)
        }
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            ?.getItemAt(0)?.text?.toString()?.trim()
        if (clip != null && (clip.startsWith("http://") || clip.startsWith("https://"))) input.setText(clip)
        val vpn = android.widget.CheckBox(this).apply {
            text = "Use built-in VPN (ads & trackers are always blocked)"
            isChecked = Prefs.vpn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setVpn(this@MainActivity, on) }
        }
        val links = Prefs.links(this).toMutableList()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, links.map { linkLabel(it) }.toMutableList())
        val list = android.widget.ListView(this).apply {
            this.adapter = adapter
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, if (links.isEmpty()) 0 else (minOf(links.size, 4) * 64 * dp).toInt())
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, 0, p, 0)
            addView(input)
            addView(vpn)
            if (links.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = "Saved links (tap to play, hold to remove)"
                setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            })
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Play a link on the TV")
            .setMessage("Video or stream link (mp4, m3u8 …), a web page with a video, a YouTube link, or a Google Photos shared-album link (slideshow). Links are saved only on this phone; the TV keeps nothing.")
            .setView(box)
            .setPositiveButton("Play") { _, _ -> playLink(input.text.toString().trim(), vpn.isChecked) }
            .setNeutralButton("VPN server") { _, _ -> vpnSettings() }
            .setNegativeButton("Cancel", null)
            .show()
        list.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            playLink(links[pos], vpn.isChecked)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            Prefs.removeLink(this, links.removeAt(pos))
            adapter.remove(adapter.getItem(pos))
            true
        }
    }

    private fun linkLabel(url: String): String {
        val u = Uri.parse(url)
        val name = u.lastPathSegment?.takeIf { it.length in 3..60 } ?: ""
        return "${u.host.orEmpty()}${if (name.isNotEmpty()) " · $name" else ""}\n$url"
    }

    private fun playLink(url: String, vpn: Boolean) {
        val u = Uri.parse(url)
        if ((u.scheme != "http" && u.scheme != "https") || u.host.isNullOrBlank()) {
            toast("Enter a link starting with http:// or https://")
            return
        }
        if (AlbumLink.isAlbum(url)) {
            // The TV has too little memory to load an album page; the phone reads it and sends the picture list.
            lifecycleScope.launch {
                val tv = connectTv() ?: return@launch
                toast("Reading the album…")
                val pics = withContext(Dispatchers.IO) { runCatching { AlbumLink.resolve(url) }.getOrDefault(emptyList()) }
                if (pics.isEmpty()) { toast("No photos found. Is the album shared by link?"); return@launch }
                val ok = withContext(Dispatchers.IO) {
                    TvClient.images(tv, pics, List(pics.size) { "Item ${it + 1}" }, 0, slideshow = true)
                }
                if (ok) {
                    Prefs.addLink(this@MainActivity, url)
                    toast("${pics.size} items · slideshow on the TV")
                    startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
                } else toast("Could not reach ${tv.name}")
            }
            return
        }
        sendOnline { tv -> TvClient.url(tv, url, vpn).also { if (it) Prefs.addLink(this, url) } }
    }

    private fun vpnSettings() {
        val options = arrayOf("Cloudflare WARP (free, automatic)", "My WireGuard config (paste)…")
        AlertDialog.Builder(this)
            .setTitle("Built-in VPN server")
            .setItems(options) { _, which ->
                if (which == 0) sendVpn("") else {
                    val input = EditText(this).apply {
                        hint = "[Interface]\nPrivateKey = …\n\n[Peer]\n…"
                        minLines = 6
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                            ?.getItemAt(0)?.text?.toString()
                        if (clip != null && clip.contains("[Interface]")) setText(clip)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("WireGuard config")
                        .setMessage("Paste a WireGuard config from your VPN provider (e.g. Proton VPN free → WireGuard configuration). It's sent encrypted and stored only on the TV.")
                        .setView(input)
                        .setPositiveButton("Use") { _, _ -> sendVpn(input.text.toString()) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendVpn(config: String) {
        lifecycleScope.launch {
            val tv = connectTv() ?: return@launch
            val name = withContext(Dispatchers.IO) { TvClient.vpnConfig(tv, config) }
            toast(if (name != null) "TV VPN: $name" else "The TV didn't accept that config")
        }
    }

    private fun sendOnline(send: (Tv) -> Boolean) {
        lifecycleScope.launch {
            val tv = connectTv() ?: return@launch
            val ok = withContext(Dispatchers.IO) { send(tv) }
            if (ok) startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
            else toast("Could not reach ${tv.name}")
        }
    }

    private fun showApks() {
        if (!Environment.isExternalStorageManager()) {
            toast("Allow \"All files access\" for TV Cast to list APK files")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            val apks = withContext(Dispatchers.IO) { MediaRepo.apks(this@MainActivity) }
            if (apks.isEmpty()) { toast("No APK files found on this phone"); return@launch }
            val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            val labels = apks.map { a ->
                val title = a.appName?.let { n -> "$n ${a.version?.let { "v$it" }.orEmpty()}" } ?: a.fileName
                "$title\n${a.fileName} · ${formatSize(a.size).let { if (a.size < 1 shl 20) "${a.size / 1024} KB" else it }} · ${fmt.format(java.util.Date(a.modified))}\n${a.path.substringBeforeLast('/')}"
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("APK files (${apks.size})")
                .setItems(labels.toTypedArray()) { _, which ->
                    val a = apks[which]
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(a.appName ?: a.fileName)
                        .setMessage("File: ${a.fileName}\nPackage: ${a.packageName ?: "unknown (not a valid APK?)"}\nVersion: ${a.version ?: "-"}\nSize: ${formatSize(a.size)}\nFolder: ${a.path.substringBeforeLast('/')}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun disconnect() {
        ServerService.disconnect(this)
        updateTvLabel(connected = false)
        toast("Disconnected. Sharing stopped.")
    }

    private fun applyHistory() {
        val h = Prefs.history(this)
        history = h
        val byKey = videos.associateBy { it.key }
        recent = h.keys.mapNotNull { byKey[it] }
        render()
    }

    private fun hasMediaPermission() = mediaPermissions
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun maybeAskAllFilesAccess() {
        if (Environment.isExternalStorageManager() || Prefs.getFlag(this, "asked_all_files")) {
            maybeAskBattery()
            return
        }
        Prefs.setFlag(this, "asked_all_files")
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setMessage("To send .srt subtitle files that sit next to your movies, allow \"All files access\" for TV Cast on the next screen.")
            .setPositiveButton("Allow") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    /**
     * A locked phone lying still goes into Android's deep sleep ("Doze"): the TV Cast sharing service is
     * frozen and a long movie stops on the TV until the phone is unlocked. Only apps without battery
     * restrictions keep running. Asked at first start, and again (once per app start) when sending a
     * video, until allowed or "Don't ask again".
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun maybeAskBattery(whenPlaying: Boolean = false) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Prefs.getFlag(this, "battery_never")) return
        if (whenPlaying) { if (batteryAskedThisRun) return } else if (Prefs.getFlag(this, "asked_battery")) return
        batteryAskedThisRun = true
        Prefs.setFlag(this, "asked_battery")
        AlertDialog.Builder(this)
            .setTitle("Keep playing when the phone is locked")
            .setMessage(
                "Right now Android pauses TV Cast when your phone is locked, so long videos stop on the TV " +
                    "until you unlock the phone.\n\nTap Allow, then choose \"Allow\" / \"Don't optimise\" for TV Cast.\n\n" +
                    "On vivo / Xiaomi / Oppo also check: Settings → Battery → Background power consumption " +
                    "(or App battery saver) → TV Cast → Allow / No restrictions."
            )
            .setPositiveButton("Allow") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Don't ask again") { _, _ -> Prefs.setFlag(this, "battery_never") }
            .show()
    }

    private fun loadMedia() {
        lifecycleScope.launch {
            val (v, i) = withContext(Dispatchers.IO) {
                MediaRepo.videos(this@MainActivity) to MediaRepo.images(this@MainActivity)
            }
            videos = v
            images = i
            applyHistory()
        }
    }

    private fun render() {
        if (!::videoFilter.isInitialized) return
        val items = if (page == null) currentItems.take(3) else currentItems
        shown = items
        val span = if (tab == TAB_PICTURES || page == null) 3 else 2
        (list.layoutManager as GridLayoutManager).spanCount = span
        adapter.submit(items, span, history)
        updateChips(items.size)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            !hasMediaPermission() -> "Permission needed to read your media"
            !filter.isDefault -> "Nothing matches these filters.\nTap Reset to show everything."
            tab == TAB_RECENT -> "Nothing played yet"
            else -> "Nothing found"
        }
    }

    private fun updateChips(count: Int) {
        val f = filter
        val pictures = tab == TAB_PICTURES
        chipSort.text = if (tab == TAB_RECENT) "Sort: Last played" else "Sort: ${f.sort.label}"
        chipSort.isEnabled = tab != TAB_RECENT
        chipFolder.text = "Category: ${f.folder ?: "All"}"
        chipFolder.isChecked = f.folder != null
        chipShow.visibility = if (pictures) View.GONE else View.VISIBLE
        chipShow.text = "Show: ${f.show.label}"
        chipShow.isChecked = f.show != Show.ALL
        chipReset.visibility = if (f.isDefault) View.GONE else View.VISIBLE
        chipReset.text = "Reset ($count)"
        search.hint = if (pictures) "Search ${images.size} pictures" else "Search ${videos.size} videos"
        if (search.text.toString() != f.query) search.setText(f.query)
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> onPick(which); d.dismiss() }
            .show()
    }

    private fun pickSort() {
        val options = SortBy.entries.filter { tab != TAB_PICTURES || it.forPictures }
        choose("Sort by", options.map { it.label }, options.indexOf(filter.sort)) { filter = filter.copy(sort = options[it]) }
    }

    /** Categories are the phone's folders (Download, Camera, WhatsApp Video…), with item counts. */
    private fun pickFolder() {
        val source = if (tab == TAB_PICTURES) images else if (tab == TAB_RECENT) recent else videos
        val counts = source.groupingBy { it.folder }.eachCount().toList().sortedByDescending { it.second }
        val folders = listOf<String?>(null) + counts.map { it.first }
        val labels = listOf("All (${source.size})") + counts.map { "${it.first} (${it.second})" }
        choose("Category (folder)", labels, folders.indexOf(filter.folder).coerceAtLeast(0)) {
            filter = filter.copy(folder = folders[it])
        }
    }

    private fun pickShow() {
        val options = Show.entries
        val labels = options.map { s ->
            val n = Filter(show = s).apply(if (tab == TAB_RECENT) recent else videos, history).size
            "${s.label} ($n)"
        }
        choose("Show", labels, options.indexOf(filter.show)) { filter = filter.copy(show = options[it]) }
    }

    private fun updateTvLabel(connected: Boolean? = null) {
        val tv = Prefs.lastTv(this)
        tvLabel.text = when {
            tv == null -> "No TV selected"
            connected == false -> "${tv.name} · offline"
            connected == true -> "${tv.name} · connected"
            else -> tv.name
        }
        tvLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (connected == true) R.drawable.dot_on else R.drawable.dot_off, 0, 0, 0
        )
    }

    private fun onItemClick(pos: Int) {
        val item = shown.getOrNull(pos) ?: return
        // Slideshow / play queue follow what's on screen (filtered + sorted).
        val snapshot = shown.filter { it.isVideo == item.isVideo }.take(MAX_QUEUE)
        val index = snapshot.indexOf(item).coerceAtLeast(0)
        lifecycleScope.launch {
            val startMs = if (item.isVideo) askResume(item) ?: return@launch else 0L
            val tv = connectTv() ?: return@launch
            if (!startSharing()) return@launch
            toast("Sending to ${tv.name}…")
            val ok = withContext(Dispatchers.IO) {
                // Use the phone address on the TV's network (home Wi-Fi or this phone's hotspot).
                val ip = Net.localIpFor(tv.ip) ?: return@withContext false
                val base = "http://$ip:${MediaServer.PORT}/${Prefs.token(this@MainActivity)}"
                TvClient.hello(tv, base, TvClient.phoneName(this@MainActivity))
                if (item.isVideo) {
                    val subs = MediaRepo.subtitleExts(this@MainActivity)
                    val items = org.json.JSONArray()
                    snapshot.forEach { m ->
                        items.put(org.json.JSONObject().put("url", "$base/v/${m.id}").put("title", m.name).put("key", m.key)
                            .put("sub", subs[m.id]?.let { "$base/s/${m.id}.$it" } ?: ""))
                    }
                    TvClient.play(tv, items, index, startMs)
                } else {
                    TvClient.images(tv, snapshot.map { "$base/i/${it.id}" }, snapshot.map { it.name }, index)
                }
            }
            updateTvLabel(connected = ok)
            if (ok) startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
            else toast("Could not reach ${tv.name}. Is the TV on and on the same Wi-Fi?")
        }
    }

    /** Starts the phone's media sharing (only now, when something is about to play). */
    private suspend fun startSharing(): Boolean {
        if (withContext(Dispatchers.IO) { Net.localIp() } == null) {
            toast("Phone is not on Wi-Fi or hotspot")
            return false
        }
        maybeAskBattery(whenPlaying = true)
        if (!ServerService.running) {
            ServerService.start(this)
            repeat(20) { if (!ServerService.running) delay(100) }
            delay(300)
        }
        return true
    }

    /**
     * The TV to use: the saved one straight away if it's reachable and paired (no picker),
     * otherwise the picker, then pairing if needed.
     */
    private suspend fun connectTv(forcePicker: Boolean = false): Tv? {
        if (!forcePicker) {
            val saved = Prefs.lastTv(this)
            if (saved != null) {
                val tv = TvClient.ensure(this, announce = false)
                if (tv != null) {
                    updateTvLabel(connected = true)
                    return if (tv.paired) tv else pair(tv)
                }
                toast("${saved.name} not found. Choose a TV.")
            }
        }
        val picked = pickTv() ?: return null
        return if (picked.paired) picked else pair(picked)
    }

    /** One-time pairing: the TV asks "Allow this phone?"; we wait for Allow. */
    private suspend fun pair(tv: Tv): Tv? {
        val name = TvClient.phoneName(this)
        withContext(Dispatchers.IO) { TvClient.pairStart(tv, name) }?.let { toast(it); return null }

        var cancelled = false
        val waiting = AlertDialog.Builder(this)
            .setTitle("Allow on the TV")
            .setMessage("On ${tv.name}, choose Allow to let this phone use it.\n\nIf nothing appears, open TV Cast on the TV — the question shows there.")
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }
            .show()
        val deadline = System.currentTimeMillis() + 2 * 60 * 1000L
        var result: String? = "waiting"
        while (!cancelled && result == "waiting" && System.currentTimeMillis() < deadline) {
            delay(1500)
            result = withContext(Dispatchers.IO) { TvClient.pairPoll(tv) } ?: "waiting"
        }
        if (waiting.isShowing) waiting.dismiss()
        when {
            cancelled -> return null
            result == "denied" -> { toast("${tv.name} denied the request"); return null }
            result != "allowed" -> { toast("No answer from ${tv.name}. Try again."); return null }
        }
        val paired = withContext(Dispatchers.IO) { TvClient.ping(tv.ip, 2000, tv.id) } ?: tv.copy(paired = true)
        Prefs.saveTv(this, paired)
        updateTvLabel(connected = true)
        toast("Paired with ${tv.name}")
        return paired
    }

    /** Returns the start position (0 = beginning), or null if the user cancelled. */
    private suspend fun askResume(item: Media): Long? {
        // Ask the TV for the latest position first (it may have been watched from the TV remote since).
        Prefs.lastTv(this)?.takeIf { it.paired }?.let { tv ->
            withContext(Dispatchers.IO) { TvClient.history(tv, 1500) }?.let { Prefs.saveHistory(this, it); history = Prefs.history(this) }
        }
        val p = history[item.key]
        if (p == null || !p.resumable) return 0L
        return suspendCancellableCoroutine { cont ->
            var answered = false
            fun answer(v: Long?) {
                if (answered) return
                answered = true
                if (cont.isActive) cont.resume(v)
            }
            AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(arrayOf("Resume from ${formatTime(p.pos)}", "Start from beginning")) { _, which ->
                    answer(if (which == 0) p.pos else 0L)
                }
                .setOnDismissListener { answer(null) }
                .show()
        }
    }

    /** Shows the TV picker: last used TV immediately, then anything found by scanning the Wi-Fi. */
    private suspend fun pickTv(): Tv? = suspendCancellableCoroutine { cont ->
        val found = mutableListOf<Tv>()
        Prefs.lastTv(this)?.let { found += it }
        val names = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        fun refresh() {
            names.clear()
            names.addAll(found.map { "${it.name}    (${it.ip})" })
        }
        refresh()

        var handled = false
        var handedOff = false
        var scanJob: Job? = null
        fun done(tv: Tv?) {
            if (handled) return
            handled = true
            scanJob?.cancel()
            if (tv != null) {
                Prefs.saveTv(this, tv)
            }
            updateTvLabel()
            if (cont.isActive) cont.resume(tv)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Searching for TVs…")
            .setAdapter(names) { _, which -> done(found.getOrNull(which)) }
            .setNeutralButton("Enter IP") { _, _ -> handedOff = true; askIp { done(it) } }
            .setNegativeButton("Cancel") { _, _ -> done(null) }
            .create()
        dialog.setOnDismissListener { scanJob?.cancel(); if (!handedOff) done(null) }
        cont.invokeOnCancellation { scanJob?.cancel() }

        scanJob = lifecycleScope.launch {
            val my = withContext(Dispatchers.IO) { Net.localIp() }
            if (my == null) {
                dialog.setTitle("Phone is not on Wi-Fi or hotspot")
                return@launch
            }
            TvClient.scan { tv ->
                val i = found.indexOfFirst { it.ip == tv.ip }
                if (i >= 0) found[i] = tv else found += tv
                refresh()
            }
            dialog.setTitle(if (found.isEmpty()) "No TV found. Open TV Cast on the TV once." else "Select TV")
        }
        dialog.show()
    }

    private fun askIp(onResult: (Tv?) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "192.168.0.104"
            setText(Prefs.lastTv(this@MainActivity)?.ip ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("TV IP address")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                lifecycleScope.launch {
                    val tv = withContext(Dispatchers.IO) { TvClient.ping(ip, 2500) }
                    if (tv == null) toast("No TV Cast app answering at $ip")
                    onResult(tv)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history, saved links & cache?")
            .setMessage("Forgets all resume positions (on the phone and the TV), deletes the saved links list and cached thumbnails. History older than 30 days is removed automatically anyway.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    Prefs.clearHistory(this@MainActivity)
                    val tvOk = withContext(Dispatchers.IO) {
                        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        Prefs.lastTv(this@MainActivity)?.let { TvClient.clear(it) } ?: true
                    }
                    applyHistory()
                    toast(if (tvOk) "Cleared" else "Cleared on phone (TV not reachable)")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_RECENT = 0
        private const val TAB_VIDEOS = 1
        private const val TAB_PICTURES = 2
        const val EXTRA_PAGE = "page"
        private const val MAX_QUEUE = 300
    }
}
