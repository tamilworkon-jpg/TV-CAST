package com.cleantv.phone

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MediaAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<MediaAdapter.VH>() {
    private var items: List<Media> = emptyList()
    private var span = 2
    private var history: Map<String, Progress> = emptyMap()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Media>, span: Int, history: Map<String, Progress>) {
        items = list
        this.span = span
        this.history = history
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val playIcon: View = v.findViewById(R.id.playIcon)
        val duration: TextView = v.findViewById(R.id.duration)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        val cell = (h.itemView.resources.displayMetrics.widthPixels - 40) / span
        h.thumb.layoutParams.height = if (m.isVideo) cell * 9 / 16 else cell
        h.thumb.requestLayout()
        h.thumb.load(m)
        h.name.visibility = if (m.isVideo) View.VISIBLE else View.GONE
        h.info.visibility = h.name.visibility
        h.playIcon.visibility = h.name.visibility
        h.duration.visibility = h.name.visibility
        h.duration.text = formatTime(m.durationMs)
        h.name.text = m.name
        val p = if (m.isVideo) history[m.key] else null
        h.progress.visibility = if (p != null) View.VISIBLE else View.GONE
        h.progress.progress = ((p?.fraction ?: 0f) * 1000).toInt()
        h.info.text = buildString {
            append(formatSize(m.size))
            when {
                p == null -> {}
                p.finished -> append("  •  Watched")
                p.resumable -> append("  •  Resume at ").append(formatTime(p.pos))
            }
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}
