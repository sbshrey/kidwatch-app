package com.kidwatch.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidwatch.app.R
import com.kidwatch.app.data.local.entity.SessionScreenshotEntity

class SessionScreenshotAdapter : ListAdapter<SessionScreenshotEntity, SessionScreenshotAdapter.ScreenshotViewHolder>(
    DiffCallback
) {

    var onScreenshotClick: ((SessionScreenshotEntity) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScreenshotViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_screenshot, parent, false)
        return ScreenshotViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScreenshotViewHolder, position: Int) {
        holder.bind(getItem(position), onScreenshotClick)
    }

    class ScreenshotViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val preview: ImageView = itemView.findViewById(R.id.ivSessionScreenshot)
        private val caption: TextView = itemView.findViewById(R.id.tvSessionScreenshotCaption)

        fun bind(
            screenshot: SessionScreenshotEntity,
            onScreenshotClick: ((SessionScreenshotEntity) -> Unit)?
        ) {
            val bitmap = ActivityEvidenceUi.loadBitmap(screenshot.filePath, 480, 320)
            if (bitmap != null) {
                preview.setImageBitmap(bitmap)
                preview.scaleType = ImageView.ScaleType.CENTER_CROP
                preview.setPadding(0, 0, 0, 0)
            } else {
                preview.setImageResource(R.drawable.ic_placeholder_apps)
                preview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (16 * itemView.resources.displayMetrics.density).toInt()
                preview.setPadding(pad, pad, pad, pad)
            }
            caption.text = itemView.context.getString(
                R.string.session_screenshot_caption,
                ActivityEvidenceUi.formatClock(screenshot.capturedAt),
                screenshot.triggerType.replace('_', ' ')
            )
            itemView.setOnClickListener { onScreenshotClick?.invoke(screenshot) }
            preview.setOnClickListener { onScreenshotClick?.invoke(screenshot) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<SessionScreenshotEntity>() {
        override fun areItemsTheSame(oldItem: SessionScreenshotEntity, newItem: SessionScreenshotEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SessionScreenshotEntity, newItem: SessionScreenshotEntity): Boolean {
            return oldItem == newItem
        }
    }
}
