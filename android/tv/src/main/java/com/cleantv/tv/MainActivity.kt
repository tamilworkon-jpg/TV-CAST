package com.cleantv.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** TV home: lists the phone's videos and pictures so they can be played with the Fire TV remote. */
class MainActivity : AppCompatActivity() {
    private data class Item(
        val id: Long, val name: String, val size: Long, val dur: Long,
        val key: String, val sub: String?, val video: Boolean,
        val folder: String = "", val date: Long = 0,
        /** Radio station (no phone needed). */
        val station: Station? = null,
    )

    private val sorts = listOf("Newest first", "Oldest first", "Name A–Z", "Name Z–A",
        "Size: largest first", "Size: smallest first", "Length: longest first", "Length: shortest first")
    private val shows = listOf("All", "Movies (40 min+)", "Short videos (< 40 min)", "Not started", "Continue watching", "Watched")
    // Separate choices for videos and pictures, remembered across restarts.
    private val fsp by lazy { getSharedPreferences("filters", Context.MODE_PRIVATE) }
    private fun group() = if (tab == TAB_PICTURES) "images" else "videos"
    private val radio get() = tab == TAB_RADIO
    private var sortIdx: Int
        get() = fsp.getInt("${group()}.sort", 0)
        set(v) { fsp.edit().putInt("${group()}.sort", v).apply() }
    private var folder: String?
        get() = fsp.getString("${group()}.folder", null)
        set(v) { fsp.edit().putString("${group()}.folder", v).apply() }
    private var showIdx: Int
        get() = if (tab == TAB_PICTURES) 0 else fsp.getInt("videos.show", 0)
        set(v) { fsp.edit().putInt("videos.show", v).apply() }

    private lateinit var btnSort: TextView
    private lateinit var btnFolder: TextView
    private lateinit var btnShow: TextView
    private lateinit var btnReset: TextView

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var info: TextView
    private lateinit var tabs: List<TextView>
    private val adapter = CardAdapter()
    private val io = Executors.newSingleThreadExecutor()

    private var base: String? = null
    private var phoneName = ""
    private var videos = emptyList<Item>()
    private var images = emptyList<Item>()
    private var history: Map<String, JSONObject> = emptyMap()
    private var tab = TAB_VIDEOS
    private var shown = emptyList<Item>()
    private lateinit var countLabel: TextView
    private var firstLoad = true
    private var offline = false
    private var offlineTicks = 0
    private var stations = emptyList<Item>()
    /** Search text the station list was loaded for (null = not loaded yet / failed). */
    private var stationsFor: String? = null
    private var radioLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CastService.start(this)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        info = findViewById(R.id.info)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter

        tabs = listOf(findViewById(R.id.tabRecent), findViewById(R.id.tabVideos), findViewById(R.id.tabPictures), findViewById(R.id.tabRadio))
        tabs.forEachIndexed { i, v ->
            v.setOnFocusChangeListener { _, has -> if (has) selectTab(i) }
            v.setOnClickListener { selectTab(i) }
        }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            if (radio) { stationsFor = null; Radio.forget(Radio.query(this)) }
            load()
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Forgets where you stopped in every video and the radio stations you played. History older than 30 days is removed automatically.")
                .setPositiveButton("Clear") { _, _ ->
                    clearAll(this)
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNeutralButton("Forget paired phones") { _, _ ->
                    val n = Auth.pairedNames(this).size
                    Auth.forgetAll(this)
                    Toast.makeText(this, "Forgot $n phone(s). They must pair again with a new code.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        btnSort = findViewById(R.id.btnSort)
        countLabel = findViewById(R.id.count)
        btnFolder = findViewById(R.id.btnFolder)
        btnShow = findViewById(R.id.btnShow)
        btnReset = findViewById(R.id.btnReset)
        btnSort.setOnClickListener {
            if (radio) { searchStations(); return@setOnClickListener }
            val options = if (tab == TAB_PICTURES) sorts.take(6) else sorts
            choose("Sort by", options, sortIdx) { sortIdx = it; render() }
        }
        btnFolder.setOnClickListener {
            if (radio) { pickStationCategory(); return@setOnClickListener }
            val source = baseList()
            val counts = source.groupingBy { it.folder }.eachCount().toList().sortedByDescending { it.second }
            val folders = listOf<String?>(null) + counts.map { it.first }
            val labels = listOf("All (${source.size})") + counts.map { "${it.first} (${it.second})" }
            choose("Category (folder)", labels, folders.indexOf(folder).coerceAtLeast(0)) { folder = folders[it]; render() }
        }
        btnShow.setOnClickListener {
            val labels = shows.mapIndexed { i, s -> "$s (${baseList().count { matchesShow(it, i) }})" }
            choose("Show", labels, showIdx) { showIdx = it; render() }
        }
        btnReset.setOnClickListener {
            if (radio) { loadStations(Radio.CATEGORIES[1].second); return@setOnClickListener }
            sortIdx = 0; folder = null; if (tab != TAB_PICTURES) showIdx = 0
            render()
        }
        tabs[TAB_VIDEOS].requestFocus()
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> onPick(which); d.dismiss() }
            .show()
    }

    /** The tab's items before any filtering. */
    private fun baseList(): List<Item> = when (tab) {
        TAB_RECENT -> {
            val byKey = videos.associateBy { it.key }
            val h = History.all(this)
            (0 until h.length()).mapNotNull { byKey[h.getJSONObject(it).optString("key")] }
        }
        TAB_VIDEOS -> videos
        TAB_RADIO -> stations
        else -> images
    }

    private fun matchesShow(m: Item, show: Int): Boolean {
        val h = history[m.key]
        val pos = h?.optLong("pos") ?: 0L
        val dur = h?.optLong("dur") ?: 0L
        return when (show) {
            1 -> m.dur >= MOVIE_MS
            2 -> m.dur < MOVIE_MS
            3 -> h == null
            4 -> h != null && pos > 10_000 && pos < dur - 30_000
            5 -> h != null && dur > 0 && pos >= dur - 30_000
            else -> true
        }
    }

    private fun applyFilters(list: List<Item>): List<Item> {
        val f = folder
        val show = showIdx
        if (radio) return list
        val filtered = list.filter { (f == null || it.folder == f) && matchesShow(it, show) }
        if (tab == TAB_RECENT) return filtered
        return when (sortIdx) {
            1 -> filtered.sortedBy { it.date }
            2 -> filtered.sortedBy { it.name.lowercase() }
            3 -> filtered.sortedByDescending { it.name.lowercase() }
            4 -> filtered.sortedByDescending { it.size }
            5 -> filtered.sortedBy { it.size }
            6 -> filtered.sortedByDescending { it.dur }
            7 -> filtered.sortedBy { it.dur }
            else -> filtered.sortedByDescending { it.date }
        }
    }

    private fun updateFilterButtons(count: Int) {
        if (radio) {
            val q = Radio.query(this)
            btnSort.text = "Search stations"
            btnSort.isEnabled = true
            btnSort.alpha = 1f
            btnFolder.text = "Category: " + (Radio.CATEGORIES.firstOrNull { it.second == q }?.first ?: "\u201c$q\u201d")
            btnShow.visibility = View.GONE
            btnReset.visibility = View.GONE
            countLabel.text = if (radioLoading) "Loading stations…" else "$count stations  ·  ◀ ▶ in the player for the next station"
            return
        }
        val pictures = tab == TAB_PICTURES
        btnSort.text = if (tab == TAB_RECENT) "Sort: Last played" else "Sort: ${sorts.getOrElse(sortIdx) { sorts[0] }}"
        btnSort.isEnabled = tab != TAB_RECENT
        btnSort.alpha = if (tab == TAB_RECENT) 0.5f else 1f
        btnFolder.text = "Category: ${folder ?: "All"}"
        btnShow.visibility = if (pictures) View.GONE else View.VISIBLE
        btnShow.text = "Show: ${shows[showIdx]}"
        val filtered = folder != null || showIdx != 0 || (sortIdx != 0 && tab != TAB_RECENT)
        btnReset.visibility = if (filtered) View.VISIBLE else View.GONE
        btnReset.text = "Reset"
        countLabel.text = "$count ${if (pictures) "pictures" else "videos"}"
    }

    override fun onResume() {
        super.onResume()
        Bus.onPhoneChanged = { load() }
        Bus.onHistoryChanged = { refreshHistory() }
        // Just played a station: bring it to the front of "Played recently".
        if (radio && Radio.query(this) == Radio.RECENT && !radioLoading) loadStations(Radio.RECENT)
        load()
        pinPoll.run()
    }

    override fun onPause() {
        Bus.onPhoneChanged = null
        Bus.onHistoryChanged = null
        grid.removeCallbacks(pinPoll)
        super.onPause()
    }

    /** Shows a pairing code here when a phone asks to pair while TV Cast is open. */
    private val pinPoll: Runnable = object : Runnable {
        override fun run() {
            PairDialog.showIfPending(this@MainActivity)
            // Phone offline: look again every 10 s, so the list appears as soon as the phone app is opened.
            if (offline && base != null && ++offlineTicks % 10 == 0) load()
            grid.postDelayed(this, 1000)
        }
    }

    private fun selectTab(i: Int) {
        if (tab == i && adapter.itemCount > 0) return
        tab = i
        if (radio && stationsFor != Radio.query(this) && !radioLoading) loadStations(Radio.query(this))
        render()
    }

    /** Progress was saved by the player: update progress bars (and the Continue order) without reloading. */
    private fun refreshHistory() {
        if (radio) return
        if (tab == TAB_RECENT || showIdx != 0) { render(); return }
        val h = History.all(this)
        history = (0 until h.length()).map { h.getJSONObject(it) }.associateBy { it.optString("key") }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    // ---------------------------------------------------------------- radio

    private fun loadStations(q: String) {
        Radio.setQuery(this, q)
        radioLoading = true
        if (radio) { adapter.submit(emptyList()); showEmpty("Loading stations…"); updateFilterButtons(0) }
        io.execute {
            val found = Radio.search(this, q)
            runOnUiThread {
                if (Radio.query(this) != q) return@runOnUiThread // another category was chosen meanwhile
                radioLoading = false
                stationsFor = if (found == null) null else q
                stations = found.orEmpty().map { s ->
                    Item(0, s.name, 0, 0, s.url, null, false, station = s)
                }
                if (radio) {
                    render()
                    if (found == null) showEmpty("Couldn't load radio stations.\nCheck the TV's internet connection, then choose Refresh.")
                }
            }
        }
    }

    private fun pickStationCategory() {
        val q = Radio.query(this)
        val labels = Radio.CATEGORIES.map { it.first }
        choose("Radio stations", labels, Radio.CATEGORIES.indexOfFirst { it.second == q }) {
            loadStations(Radio.CATEGORIES[it].second)
        }
    }

    private fun searchStations() {
        val box = android.widget.EditText(this).apply {
            hint = "Station name or language (Mirchi, Hello FM, AIR…)"
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Search radio stations")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> box.text.toString().trim().let { if (it.isNotEmpty()) loadStations(it.lowercase()) } }
            .setNegativeButton("Cancel", null)
            .create()
        box.setOnEditorActionListener { _, _, _ ->
            box.text.toString().trim().let { if (it.isNotEmpty()) loadStations(it.lowercase()) }
            d.dismiss(); true
        }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        box.requestFocus()
    }

    /** The whole visible station list goes to the player, so ◀ ▶ / ⏮ ⏭ change station. */
    private fun playStation(item: Item) {
        val list = shown.mapNotNull { it.station }
        val s = item.station ?: return
        val idx = list.indexOf(s).coerceAtLeast(0)
        Radio.remember(this, s)
        Toast.makeText(this, "Tuning in to ${s.name}…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        io.execute { Online.radio(app, list.map { Radio.item(it) }, idx, Radio.vpn(app)) }
    }

    private fun load() {
        if (radio && stationsFor == null && !radioLoading) loadStations(Radio.query(this))
        val sp = getSharedPreferences("phone", Context.MODE_PRIVATE)
        base = sp.getString("base", null)
        phoneName = sp.getString("name", "") ?: ""
        updateInfo()
        val b = base
        if (b == null) {
            if (radio) { render(); return }
            showEmpty("Open TV Cast on your phone and select this TV once.\nYour phone's videos and pictures will then appear here.")
            return
        }
        if (videos.isEmpty() && images.isEmpty()) showEmpty("Loading from $phoneName…")
        io.execute {
            val json = httpGet("$b/list")
            runOnUiThread {
                if (json == null) {
                    offline = true
                    updateInfo()
                    if (videos.isEmpty() && images.isEmpty() && !radio) {
                        showEmpty("$phoneName isn't sharing right now.\nOpen TV Cast on the phone (same Wi-Fi) — the videos appear here by themselves.")
                        return@runOnUiThread
                    }
                } else {
                    offline = false
                    parse(json)
                    updateInfo()
                }
                if (firstLoad && !radio && history.keys.any { k -> videos.any { it.key == k } }) {
                    tab = TAB_RECENT
                    tabs[TAB_RECENT].requestFocus()
                }
                firstLoad = false
                render()
            }
        }
    }

    private fun parse(json: String) {
        val o = JSONObject(json)
        val v = o.getJSONArray("videos")
        videos = List(v.length()) {
            val j = v.getJSONObject(it)
            Item(j.getLong("id"), j.optString("name"), j.optLong("size"), j.optLong("dur"),
                j.optString("key"), j.optString("sub").takeIf { s -> s.isNotEmpty() }, true,
                j.optString("folder", "Other"), j.optLong("date"))
        }
        val i = o.getJSONArray("images")
        images = List(i.length()) {
            val j = i.getJSONObject(it)
            Item(j.getLong("id"), j.optString("name"), j.optLong("size"), 0, "", null, false,
                j.optString("folder", "Other"), j.optLong("date"))
        }
    }

    private fun updateInfo() {
        val phone = when {
            base == null -> "No phone yet"
            offline -> "$phoneName (offline)"
            else -> "$phoneName connected"
        }
        info.text = "${deviceName(this)}  ·  ${localIp() ?: "no network"}  ·  $phone"
    }

    private fun render() {
        tabs.forEachIndexed { i, v -> v.isSelected = i == tab }
        val h = History.all(this)
        history = (0 until h.length()).map { h.getJSONObject(it) }.associateBy { it.optString("key") }
        val all = baseList()
        val list = applyFilters(all)
        shown = list
        updateFilterButtons(list.size)
        (grid.layoutManager as GridLayoutManager).spanCount = if (tab == TAB_PICTURES || radio) 5 else 4
        adapter.submit(list)
        if (radio) {
            when {
                radioLoading -> showEmpty("Loading stations…")
                list.isEmpty() && Radio.query(this) == Radio.RECENT -> showEmpty("No stations played yet.\nChoose Category to pick Tamil, Hindi, News…")
                list.isEmpty() && stationsFor != null -> showEmpty("No stations found.\nTry another category or search.")
                list.isNotEmpty() -> empty.visibility = View.GONE
            }
        } else if (list.isEmpty() && base != null && !offline) {
            showEmpty(
                when {
                    all.isNotEmpty() -> "Nothing matches these filters.\nChoose Reset to show everything."
                    tab == TAB_RECENT -> "Nothing to continue yet.\nVideos you start will show up here for 30 days."
                    tab == TAB_VIDEOS -> "No videos found on $phoneName."
                    else -> "No pictures found on $phoneName."
                }
            )
        } else if (list.isNotEmpty()) {
            empty.visibility = View.GONE
        }
    }

    private fun showEmpty(text: String) {
        empty.text = text
        empty.visibility = View.VISIBLE
        if ((videos.isEmpty() && images.isEmpty()) || radio) adapter.submit(emptyList())
    }

    private fun open(item: Item, index: Int) {
        if (item.station != null) { playStation(item); return }
        val b = base ?: return
        if (!item.video) {
            val pics = shown.filter { !it.video }
            Bus.pending = CastRequest.Images(pics.map { "$b/i/${it.id}" }, pics.map { it.name }, pics.indexOf(item).coerceAtLeast(0))
            startActivity(Intent(this, PlayerActivity::class.java))
            return
        }
        val h = history[item.key]
        val pos = h?.optLong("pos") ?: 0L
        val dur = h?.optLong("dur") ?: 0L
        if (pos > 10_000 && pos < dur - 30_000) {
            AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(arrayOf("Resume from ${fmt(pos)}", "Start from beginning")) { _, which ->
                    play(item, if (which == 0) pos else 0L)
                }
                .show()
        } else {
            play(item, 0L)
        }
    }

    private fun play(item: Item, startMs: Long) {
        val b = base ?: return
        // The whole visible list becomes the play queue, so Next/Previous work in the player.
        val list = shown.filter { it.video }.take(MAX_QUEUE)
        val idx = list.indexOf(item).coerceAtLeast(0)
        val items = list.map { VideoItem("$b/v/${it.id}", it.name, it.sub?.let { s -> "$b/s/${it.id}.$s" }, it.key) }
        Bus.pending = CastRequest.Video(items.ifEmpty {
            listOf(VideoItem("$b/v/${item.id}", item.name, item.sub?.let { "$b/s/${item.id}.$it" }, item.key))
        }, if (items.isEmpty()) 0 else idx, startMs)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        private var items = emptyList<Item>()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val duration: TextView = v.findViewById(R.id.duration)
            val progress: ProgressBar = v.findViewById(R.id.progress)
            val name: TextView = v.findViewById(R.id.name)
            val info: TextView = v.findViewById(R.id.info)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.06f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(150).start()
            }
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            val density = resources.displayMetrics.density
            h.itemView.setOnClickListener { open(m, h.bindingAdapterPosition) }
            m.station?.let { s ->
                h.thumb.layoutParams.height = (110 * density).toInt()
                h.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                h.thumb.setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                h.thumb.load(s.logo, imageLoader(this@MainActivity)) {
                    crossfade(200)
                    size(300, 300)
                    placeholder(R.drawable.ic_launcher)
                    error(R.drawable.ic_launcher)
                }
                h.name.visibility = View.VISIBLE
                h.info.visibility = View.VISIBLE
                h.duration.visibility = View.GONE
                h.progress.visibility = View.GONE
                h.name.text = s.name
                h.info.text = s.info.ifBlank { "Live radio" }
                return
            }
            h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            h.thumb.setPadding(0, 0, 0, 0)
            val b = base ?: return
            h.thumb.layoutParams.height = ((if (m.video) 140 else 150) * density).toInt()
            h.thumb.load("$b/t/${if (m.video) "v" else "i"}/${m.id}", imageLoader(this@MainActivity)) { crossfade(200) }
            h.name.visibility = if (m.video) View.VISIBLE else View.GONE
            h.info.visibility = h.name.visibility
            h.duration.visibility = h.name.visibility
            h.name.text = m.name
            h.duration.text = fmt(m.dur)
            val hist = history[m.key]
            val pos = hist?.optLong("pos") ?: 0L
            val dur = hist?.optLong("dur") ?: 0L
            h.progress.visibility = if (m.video && hist != null && dur > 0) View.VISIBLE else View.GONE
            if (dur > 0) h.progress.progress = (pos * 1000 / dur).toInt()
            h.info.text = buildString {
                append("%.1f GB".format(m.size / 1_073_741_824.0).let { if (m.size < 1_073_741_824) "${m.size / 1_048_576} MB" else it })
                if (m.sub != null) append("  ·  Subtitles")
                if (hist != null) append(if (pos >= dur - 30_000) "  ·  Watched" else "  ·  Resume ${fmt(pos)}")
            }
        }
    }

    companion object {
        private const val TAB_RECENT = 0
        private const val TAB_VIDEOS = 1
        private const val TAB_PICTURES = 2
        private const val TAB_RADIO = 3
        private const val MOVIE_MS = 40 * 60 * 1000L
        private const val MAX_QUEUE = 300
    }
}
