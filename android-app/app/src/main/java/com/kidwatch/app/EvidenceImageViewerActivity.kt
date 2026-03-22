package com.kidwatch.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.kidwatch.app.ui.ActivityEvidenceUi
import com.kidwatch.app.ui.ZoomableEvidenceImageView

class EvidenceImageViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var subtitleText: TextView
    private lateinit var hintText: TextView
    private lateinit var imageView: ZoomableEvidenceImageView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_image_viewer)

        toolbar = findViewById(R.id.toolbarEvidenceImage)
        subtitleText = findViewById(R.id.tvEvidenceImageSubtitle)
        hintText = findViewById(R.id.tvEvidenceImageHint)
        imageView = findViewById(R.id.ivEvidenceImage)
        emptyText = findViewById(R.id.tvEvidenceImageEmpty)

        toolbar.setNavigationOnClickListener { finish() }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)

        toolbar.title = title.ifBlank { getString(R.string.evidence_image_viewer_default_title) }
        subtitleText.text = subtitle
        subtitleText.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        hintText.visibility = View.VISIBLE

        val metrics = resources.displayMetrics
        val bitmap = ActivityEvidenceUi.loadBitmap(
            path = path,
            requestedWidth = metrics.widthPixels * 4,
            requestedHeight = metrics.heightPixels * 4
        )
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            hintText.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
        } else {
            imageView.visibility = View.GONE
            hintText.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.evidence_image_viewer_missing)
        }
    }

    companion object {
        private const val EXTRA_IMAGE_PATH = "extra_image_path"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"

        fun createIntent(
            context: Context,
            imagePath: String,
            title: String,
            subtitle: String? = null
        ): Intent {
            return Intent(context, EvidenceImageViewerActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle.orEmpty())
            }
        }
    }
}
