package com.cleantv.phone

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Internet radio picker (Fire TV has no FM tuner, so "FM" stations are played as their live streams).
 * Station list comes from the free, community-run radio-browser.info directory; nothing is stored.
 */
class RadioPicker(private val activity: Activity, private val onPick: (url: String, name: String, logo: String?) -> Unit) {
    private data class Station(val name: String, val url: String, val logo: String?, val tags: String, val codec: String, val kbps: Int)

    private val io = Executors.newSingleThreadExecutor()
    private var stations = emptyList<Station>()

    fun show() {
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val search = EditText(activity).apply {
            hint = "Search stations (e.g. Tamil, Mirchi, Hello FM, AIR)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("tamil")
        }
        val status = TextView(activity).apply { setPadding(0, pad / 2, 0, pad / 2) }
        val progress = ProgressBar(activity)
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList())
        val list = ListView(activity).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * d).toInt())
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(search); addView(progress); addView(status); addView(list)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Internet radio")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()

        fun load(q: String) {
            progress.visibility = android.view.View.VISIBLE
            status.text = "Searching…"
            io.execute {
                val result = runCatching { query(q) }.getOrNull()
                activity.runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    if (result == null) { status.text = "Couldn't load stations (no internet?)"; return@runOnUiThread }
                    stations = result
                    status.text = if (result.isEmpty()) "No stations found" else "${result.size} stations · tap to play on TV"
                    adapter.clear()
                    adapter.addAll(result.map { "${it.name}" })
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val s = stations.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            onPick(s.url, s.name, s.logo)
        }
        var pending: Runnable? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { search.removeCallbacks(it) }
                pending = Runnable { load(s.toString().trim()) }.also { search.postDelayed(it, 600) }
            }
        })
        dialog.setOnDismissListener { io.shutdownNow() }
        dialog.show()
        load("tamil")
    }

    private fun query(q: String): List<Station> {
        val params = "countrycode=IN&hidebroken=true&order=clickcount&reverse=true&limit=80" +
            if (q.isNotEmpty()) "&name=${URLEncoder.encode(q, "UTF-8")}" else ""
        var list = fetch("https://de1.api.radio-browser.info/json/stations/search?$params")
        if (list.isEmpty() && q.isNotEmpty()) {
            // Many stations are tagged by language rather than named after it.
            list = fetch("https://de1.api.radio-browser.info/json/stations/search?countrycode=IN&hidebroken=true&order=clickcount&reverse=true&limit=80&tag=${URLEncoder.encode(q, "UTF-8")}")
        }
        return list
    }

    private fun fetch(url: String): List<Station> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "TVCast/2")
        val text = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val u = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (!u.startsWith("http")) null
            else Station(
                o.optString("name").trim().ifBlank { "Station" }, u,
                o.optString("favicon").takeIf { it.startsWith("http") },
                o.optString("tags"), o.optString("codec"), o.optInt("bitrate"),
            )
        }.distinctBy { it.url }
    }
}
