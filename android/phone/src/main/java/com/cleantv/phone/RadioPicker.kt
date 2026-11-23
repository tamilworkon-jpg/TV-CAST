package com.cleantv.phone

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A radio station picked on the phone. */
data class RadioStation(val name: String, val url: String, val logo: String?, val info: String = "")

/** The station list on screen, kept in memory only, so the remote can step to the next/previous station. */
object RadioQueue {
    @Volatile var stations: List<RadioStation> = emptyList()
    @Volatile var index = 0

    fun step(delta: Int): RadioStation? {
        if (stations.size < 2) return null
        index = ((index + delta) % stations.size + stations.size) % stations.size
        return stations[index]
    }
}

/**
 * Internet radio directory (Fire TV has no FM tuner, so "FM" stations are played as their live streams).
 * Stations come from the free, community-run radio-browser.info directory; nothing is stored.
 */
object RadioDirectory {
    fun search(q: String): List<RadioStation> {
        val base = "https://de1.api.radio-browser.info/json/stations/search?countrycode=IN&hidebroken=true&order=clickcount&reverse=true&limit=80"
        var list = fetch(base + if (q.isNotEmpty()) "&name=${URLEncoder.encode(q, "UTF-8")}" else "")
        // Many stations are tagged by language rather than named after it.
        if (list.isEmpty() && q.isNotEmpty()) list = fetch("$base&tag=${URLEncoder.encode(q, "UTF-8")}")
        return list
    }

    private fun fetch(url: String): List<RadioStation> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "TVCast/2")
        val text = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val u = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (!u.startsWith("http")) return@mapNotNull null
            val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(" · ")
            val kbps = o.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }
            RadioStation(
                o.optString("name").trim().ifBlank { "Station" }, u,
                o.optString("favicon").takeIf { it.startsWith("http") },
                listOfNotNull(tags.ifBlank { null }, kbps).joinToString(" · "),
            )
        }.distinctBy { it.url }
    }
}

/** Radio page: search box and a list of stations with their logos; tap one to play it on the TV. */
class RadioActivity : AppCompatActivity() {
    private var stations = emptyList<RadioStation>()
    private var searchJob: Job? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val adapter = StationAdapter { pos -> play(pos) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val back = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.text))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply { text = "Radio"; textSize = 22f; setTextColor(getColor(R.color.text)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(back); addView(title)
        }
        val search = EditText(this).apply {
            hint = "Search stations (Tamil, Mirchi, Hello FM, AIR…)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("tamil")
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text2))
            background = getDrawable(R.drawable.search_bg)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        }
        progress = ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL } }
        status = TextView(this).apply { setTextColor(getColor(R.color.text2)); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RadioActivity)
            adapter = this@RadioActivity.adapter
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar); addView(search); addView(progress); addView(status); addView(list)
        })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { load(s.toString().trim(), 600) }
        })
        load("tamil", 0)
    }

    override fun onStart() { super.onStart(); ServerService.uiVisible++ }
    override fun onStop() { ServerService.uiVisible = (ServerService.uiVisible - 1).coerceAtLeast(0); super.onStop() }

    private fun load(q: String, wait: Long) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(wait)
            progress.visibility = View.VISIBLE
            status.text = "Searching…"
            val result = withContext(Dispatchers.IO) { runCatching { RadioDirectory.search(q) }.getOrNull() }
            progress.visibility = View.GONE
            if (result == null) { status.text = "Couldn't load stations (no internet?)"; return@launch }
            stations = result
            status.text = if (result.isEmpty()) "No stations found" else "${result.size} stations · tap to play on the TV"
            adapter.submit(result)
        }
    }

    private fun play(pos: Int) {
        val s = stations.getOrNull(pos) ?: return
        RadioQueue.stations = stations
        RadioQueue.index = pos
        lifecycleScope.launch {
            val tv = TvClient.ensure(this@RadioActivity, announce = false)?.takeIf { it.paired }
            if (tv == null) { toast("Choose and allow a TV on the home screen first"); return@launch }
            val ok = withContext(Dispatchers.IO) { TvClient.radio(tv, s.url, s.name, s.logo, Prefs.vpn(this@RadioActivity)) }
            if (ok) startActivity(Intent(this@RadioActivity, RemoteActivity::class.java)) else toast("Could not reach ${tv.name}")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private class StationAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<StationAdapter.H>() {
        private var items = emptyList<RadioStation>()

        fun submit(list: List<RadioStation>) { items = list; notifyDataSetChanged() }

        class H(v: View, val logo: ImageView, val name: TextView, val info: TextView) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): H {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val logo = ImageView(c).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = c.getDrawable(R.drawable.search_bg)
            }
            val name = TextView(c).apply { textSize = 16f; setTextColor(c.getColor(R.color.text)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val info = TextView(c).apply { textSize = 12f; setTextColor(c.getColor(R.color.text2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(name); addView(info)
            }
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                val tv = android.util.TypedValue()
                c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                addView(logo); addView(texts)
            }
            return H(row, logo, name, info)
        }

        override fun onBindViewHolder(h: H, pos: Int) {
            val s = items[pos]
            h.name.text = s.name
            h.info.text = s.info.ifBlank { "Live radio" }
            h.logo.load(s.logo) {
                crossfade(true)
                placeholder(android.R.drawable.ic_lock_silent_mode_off)
                error(android.R.drawable.ic_lock_silent_mode_off)
            }
            h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}
