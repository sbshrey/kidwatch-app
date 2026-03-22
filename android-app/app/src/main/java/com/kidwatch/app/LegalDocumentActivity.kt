package com.kidwatch.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.kidwatch.app.services.EvidencePreferences

class LegalDocumentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal_document)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarLegal)
        val titleText = findViewById<TextView>(R.id.tvLegalTitle)
        val subtitleText = findViewById<TextView>(R.id.tvLegalSubtitle)
        val bodyText = findViewById<TextView>(R.id.tvLegalBody)

        toolbar.setNavigationOnClickListener { finish() }

        val documentType = intent.getStringExtra(EXTRA_DOCUMENT_TYPE).orEmpty()
        val document = when (documentType) {
            TYPE_TERMS -> LegalDocument(
                title = getString(R.string.legal_terms_title),
                subtitle = getString(R.string.legal_terms_subtitle),
                body = getString(R.string.legal_terms_body)
            )
            TYPE_EVIDENCE -> LegalDocument(
                title = getString(R.string.legal_evidence_title),
                subtitle = getString(R.string.legal_evidence_subtitle),
                body = getString(R.string.legal_evidence_body)
            )
            else -> LegalDocument(
                title = getString(R.string.legal_privacy_title),
                subtitle = getString(R.string.legal_privacy_subtitle),
                body = getString(R.string.legal_privacy_body, EvidencePreferences.RETENTION_DAYS)
            )
        }

        toolbar.title = document.title
        titleText.text = document.title
        subtitleText.text = document.subtitle
        bodyText.text = document.body
    }

    private data class LegalDocument(
        val title: String,
        val subtitle: String,
        val body: String
    )

    companion object {
        private const val EXTRA_DOCUMENT_TYPE = "document_type"
        const val TYPE_PRIVACY = "privacy"
        const val TYPE_TERMS = "terms"
        const val TYPE_EVIDENCE = "evidence"

        fun createIntent(context: Context, documentType: String): Intent {
            return Intent(context, LegalDocumentActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_TYPE, documentType)
            }
        }
    }
}
