package com.opentube.tubedw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.opentube.tubedw.R
import com.opentube.tubedw.data.VideoItem
import java.util.Locale

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.itemThumb)
        private val duration: TextView = view.findViewById(R.id.itemDuration)
        private val title: TextView = view.findViewById(R.id.itemTitle)
        private val meta: TextView = view.findViewById(R.id.itemMeta)

        fun bind(item: VideoItem) {
            title.text = item.title
            thumb.load(item.thumbnail)

            if (item.duration > 0) {
                duration.visibility = View.VISIBLE
                duration.text = formatDuration(item.duration)
            } else {
                duration.visibility = View.GONE
            }

            val parts = mutableListOf<String>()
            if (!item.uploader.isNullOrEmpty()) parts.add(item.uploader)
            if (item.viewCount >= 0) parts.add(formatCount(item.viewCount) + " vistas")
            meta.text = parts.joinToString("  ·  ")

            itemView.setOnClickListener { onClick(item) }
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun formatCount(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1f B", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1f M", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1f k", value / 1_000.0)
        else -> value.toString()
    }
}
