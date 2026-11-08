package com.opentube.tubedw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.opentube.tubedw.R
import com.opentube.tubedw.data.DownloadStatus
import com.opentube.tubedw.data.DownloadTask
import com.opentube.tubedw.data.Formats
import java.io.File
import java.util.Locale

sealed class DownloadRow {
    data class Active(val task: DownloadTask) : DownloadRow()
    data class Problem(val task: DownloadTask) : DownloadRow()
    data class Done(val file: File) : DownloadRow()
}

class DownloadAdapter(
    /** Pausa, reanuda o reintenta según el estado de la descarga. */
    private val onPauseResume: (DownloadTask) -> Unit,
    /** Detiene por completo una descarga en curso. */
    private val onCancel: (DownloadTask) -> Unit,
    private val onDismiss: (DownloadTask) -> Unit,
    private val onOpen: (File) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.Holder>() {

    private var rows: List<DownloadRow> = emptyList()

    fun submit(newRows: List<DownloadRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DownloadRow.Active -> 0
        is DownloadRow.Problem -> 1
        is DownloadRow.Done -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.dlIcon)
        private val title: TextView = itemView.findViewById(R.id.dlTitle)
        private val meta: TextView = itemView.findViewById(R.id.dlMeta)
        private val progress: ProgressBar = itemView.findViewById(R.id.dlProgress)
        private val action: android.widget.ImageButton = itemView.findViewById(R.id.dlAction)
        private val action2: android.widget.ImageButton = itemView.findViewById(R.id.dlAction2)

        fun bind(row: DownloadRow) {
            when (row) {
                is DownloadRow.Active -> {
                    val task = row.task
                    val paused = task.status == DownloadStatus.PAUSED
                    title.text = task.title
                    meta.text = activeMeta(task)
                    progress.visibility = View.VISIBLE
                    progress.progress = task.progress.toInt().coerceIn(0, 100)
                    action.visibility = View.VISIBLE
                    if (paused) {
                        action.setImageResource(R.drawable.ic_play)
                        action.contentDescription =
                            itemView.context.getString(R.string.resume_download)
                    } else {
                        action.setImageResource(R.drawable.ic_pause)
                        action.contentDescription =
                            itemView.context.getString(R.string.pause_download)
                    }
                    action.setOnClickListener { onPauseResume(task) }
                    // Pausa a la izquierda, cancelar a la derecha.
                    action2.visibility = View.VISIBLE
                    action2.setImageResource(R.drawable.ic_close)
                    action2.contentDescription = itemView.context.getString(R.string.cancel)
                    action2.setOnClickListener { onCancel(task) }
                    itemView.setOnClickListener(null)
                }

                is DownloadRow.Problem -> {
                    val task = row.task
                    title.text = task.title
                    meta.text = when (task.status) {
                        DownloadStatus.CANCELED ->
                            itemView.context.getString(R.string.download_canceled)
                        else -> task.error
                            ?: itemView.context.getString(R.string.download_failed)
                    }
                    progress.visibility = View.GONE
                    action.visibility = View.VISIBLE
                    action2.visibility = View.VISIBLE
                    action.setImageResource(R.drawable.ic_play)
                    action.contentDescription =
                        itemView.context.getString(R.string.retry_download)
                    action.setOnClickListener { onPauseResume(task) }
                    action2.setImageResource(R.drawable.ic_close)
                    action2.contentDescription = itemView.context.getString(R.string.close)
                    action2.setOnClickListener { onDismiss(task) }
                    itemView.setOnClickListener { onDismiss(task) }
                }

                is DownloadRow.Done -> {
                    val file = row.file
                    title.text = file.name
                    meta.text = Formats.formatSize(file.length())
                    progress.visibility = View.GONE
                    action.visibility = View.GONE
                    action2.visibility = View.GONE
                    action.setOnClickListener(null)
                    action2.setOnClickListener(null)
                    itemView.setOnClickListener { onOpen(file) }
                }
            }
        }

        private fun activeMeta(task: DownloadTask): String {
            val context = itemView.context
            return when (task.status) {
                DownloadStatus.PAUSED -> context.getString(R.string.download_paused)
                DownloadStatus.QUEUED -> context.getString(R.string.download_queued)
                else -> {
                    val percent = task.progress.toInt().coerceIn(0, 100)
                    val base = if (task.attempts > 0) {
                        "${context.getString(R.string.download_retrying)} · $percent%"
                    } else {
                        "${context.getString(R.string.download_active)} · $percent%"
                    }
                    if (task.eta > 0) "$base · ${formatEta(task.eta)}" else base
                }
            }
        }

        private fun formatEta(seconds: Long): String {
            val total = seconds.toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
