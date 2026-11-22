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

        findViewById<Button>(R.id.btnTv).setOnClickListener { lifecycleScope.launch { pickTv() } }
        findViewById<Button>(R.id.btnRemote).setOnClickListener {
            if (Prefs.lastTv(this) == null) toast("Select a TV first")
            else startActivity(Intent(this, RemoteActivity::class.java))
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }

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

        if (hasMediaPermission()) maybeAskAllFilesAccess() else permLauncher.launch(mediaPermissions)
    }

    override fun onResume() {
        super.onResume()
        // Restarts sharing if it stopped itself after being idle.
        ServerService.start(this)
        updateTvLabel()
        if (hasMediaPermission()) loadMedia()
        refreshHistory()
    }

    /** Reconnects to the TV (following IP changes), announces the library, then pulls resume positions. */
    private fun refreshHistory() {
        if (Prefs.lastTv(this) == null) return
        lifecycleScope.launch {
            val tv = TvClient.ensure(this@MainActivity)
            updateTvLabel(connected = tv != null)
            if (tv == null) return@launch
            val json = withContext(Dispatchers.IO) { TvClient.history(tv) } ?: return@launch
            Prefs.saveHistory(this@MainActivity, json)
            applyHistory()
        }
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
     * vivo/Funtouch kills background apps on a locked phone, which would stop the TV mid-movie.
     * Ask once to exempt TV Cast from battery optimisation.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun maybeAskBattery() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Prefs.getFlag(this, "asked_battery")) return
        Prefs.setFlag(this, "asked_battery")
        AlertDialog.Builder(this)
            .setTitle("Keep playing when the phone is locked")
            .setMessage(
                "So the TV doesn't stop when your phone screen turns off, allow TV Cast to run without battery restrictions.\n\n" +
                    "On vivo also check: Settings → Battery → Background power consumption → TV Cast → Allow."
            )
            .setPositiveButton("Allow") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Later", null)
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
        val items = currentItems
        shown = items
        val span = if (tab == TAB_PICTURES) 3 else 2
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
        // Slideshow follows what's on screen (filtered/sorted pictures).
        val imageSnapshot = if (item.isVideo) emptyList() else shown
        val imageIndex = if (item.isVideo) 0 else pos
        lifecycleScope.launch {
            val startMs = if (item.isVideo) askResume(item) ?: return@launch else 0L
            var tv = pickTv() ?: return@launch
            if (!ServerService.running) {
                ServerService.start(this@MainActivity)
                delay(700)
            }
            if (withContext(Dispatchers.IO) { Net.localIp() } == null) {
                toast("Phone is not on Wi-Fi or hotspot")
                return@launch
            }
            toast("Sending to ${tv.name}…")
            suspend fun send(t: Tv) = withContext(Dispatchers.IO) {
                // Use the phone address on the TV's network (home Wi-Fi or this phone's hotspot).
                val ip = Net.localIpFor(t.ip) ?: return@withContext false
                val base = "http://$ip:${MediaServer.PORT}/${Prefs.token(this@MainActivity)}"
                if (item.isVideo) {
                    val sub = MediaRepo.findSubtitle(this@MainActivity, item.id)
                        ?.let { "$base/s/${item.id}.${it.extension.lowercase()}" }
                    TvClient.play(t, "$base/v/${item.id}", item.name, sub, item.key, startMs)
                } else {
                    TvClient.images(t, imageSnapshot.map { "$base/i/${it.id}" }, imageSnapshot.map { it.name }, imageIndex)
                }
            }
            var ok = send(tv)
            if (!ok) {
                // TV may have a new IP address; look for it by name and try once more.
                TvClient.ensure(this@MainActivity)?.let { tv = it; ok = send(it) }
            }
            updateTvLabel(connected = ok)
            if (ok) startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
            else toast("Could not reach ${tv.name}. Is the TV on and on the same Wi-Fi?")
        }
    }

    /** Returns the start position (0 = beginning), or null if the user cancelled. */
    private suspend fun askResume(item: Media): Long? {
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
                // Let the TV know about this phone so its home screen can list the phone's files.
                lifecycleScope.launch { updateTvLabel(connected = TvClient.ensure(this@MainActivity) != null) }
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
            .setTitle("Clear history & cache?")
            .setMessage("Forgets all resume positions (on the phone and the TV) and deletes cached thumbnails. History older than 30 days is removed automatically anyway.")
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
    }
}
