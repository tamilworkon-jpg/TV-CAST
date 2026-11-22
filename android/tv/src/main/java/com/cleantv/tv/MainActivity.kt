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
    )

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
    private var firstLoad = true
    private var offline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CastService.start(this)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        info = findViewById(R.id.info)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter

        tabs = listOf(findViewById(R.id.tabRecent), findViewById(R.id.tabVideos), findViewById(R.id.tabPictures))
        tabs.forEachIndexed { i, v ->
            v.setOnFocusChangeListener { _, has -> if (has) selectTab(i) }
            v.setOnClickListener { selectTab(i) }
        }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Forgets where you stopped in every video. History older than 30 days is removed automatically.")
                .setPositiveButton("Clear") { _, _ ->
                    clearAll(this)
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        tabs[TAB_VIDEOS].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Bus.onPhoneChanged = { load() }
        load()
    }

    override fun onPause() {
        Bus.onPhoneChanged = null
        super.onPause()
    }

    private fun selectTab(i: Int) {
        if (tab == i && adapter.itemCount > 0) return
        tab = i
        render()
    }

    private fun load() {
        val sp = getSharedPreferences("phone", Context.MODE_PRIVATE)
        base = sp.getString("base", null)
        phoneName = sp.getString("name", "") ?: ""
        updateInfo()
        val b = base
        if (b == null) {
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
                    if (videos.isEmpty() && images.isEmpty()) {
                        showEmpty("Can't reach $phoneName.\nOpen TV Cast on the phone (same Wi-Fi), then press Refresh.")
                        return@runOnUiThread
                    }
                } else {
                    offline = false
                    parse(json)
                    updateInfo()
                }
                if (firstLoad && history.keys.any { k -> videos.any { it.key == k } }) {
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
                j.optString("key"), j.optString("sub").takeIf { s -> s.isNotEmpty() }, true)
        }
        val i = o.getJSONArray("images")
        images = List(i.length()) {
            val j = i.getJSONObject(it)
            Item(j.getLong("id"), j.optString("name"), 0, 0, "", null, false)
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
        val list = when (tab) {
            TAB_RECENT -> {
                val byKey = videos.associateBy { it.key }
                (0 until h.length()).mapNotNull { byKey[h.getJSONObject(it).optString("key")] }
            }
            TAB_VIDEOS -> videos
            else -> images
        }
        (grid.layoutManager as GridLayoutManager).spanCount = if (tab == TAB_PICTURES) 5 else 4
        adapter.submit(list)
        if (list.isEmpty() && base != null && !offline) {
            showEmpty(
                when (tab) {
                    TAB_RECENT -> "Nothing to continue yet.\nVideos you start will show up here for 30 days."
                    TAB_VIDEOS -> "No videos found on $phoneName."
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
        if (videos.isEmpty() && images.isEmpty()) adapter.submit(emptyList())
    }

    private fun open(item: Item, index: Int) {
        val b = base ?: return
        if (!item.video) {
            Bus.pending = CastRequest.Images(images.map { "$b/i/${it.id}" }, images.map { it.name }, images.indexOf(item).coerceAtLeast(0))
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
        Bus.pending = CastRequest.Video("$b/v/${item.id}", item.name, item.sub?.let { "$b/s/${item.id}.$it" }, item.key, startMs)
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
            val b = base ?: return
            val density = resources.displayMetrics.density
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
            h.itemView.setOnClickListener { open(m, h.bindingAdapterPosition) }
        }
    }

    companion object {
        private const val TAB_RECENT = 0
        private const val TAB_VIDEOS = 1
        private const val TAB_PICTURES = 2
    }
}
