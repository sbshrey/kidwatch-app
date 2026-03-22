package com.kidwatch.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kidwatch.app.R

class ActivityFeedFooterAdapter(
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<ActivityFeedFooterAdapter.FooterViewHolder>() {

    private var footerState: FooterState = FooterState.Hidden

    fun submitState(state: ActivityFeedUiState) {
        val nextState = FooterState.from(state)
        val hadItem = footerState != FooterState.Hidden
        val hasItem = nextState != FooterState.Hidden
        footerState = nextState

        when {
            !hadItem && hasItem -> notifyItemInserted(0)
            hadItem && !hasItem -> notifyItemRemoved(0)
            hadItem && hasItem -> notifyItemChanged(0)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_feed_footer, parent, false)
        return FooterViewHolder(view)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) {
        holder.bind(footerState)
    }

    override fun getItemCount(): Int = if (footerState == FooterState.Hidden) 0 else 1

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val progress: LinearProgressIndicator = itemView.findViewById(R.id.activityFooterProgress)
        private val titleText: TextView = itemView.findViewById(R.id.tvActivityFooterTitle)
        private val bodyText: TextView = itemView.findViewById(R.id.tvActivityFooterBody)
        private val retryButton: MaterialButton = itemView.findViewById(R.id.btnActivityFooterRetry)

        init {
            retryButton.setOnClickListener { onRetry() }
        }

        fun bind(state: FooterState) {
            progress.visibility = if (state.showProgress) View.VISIBLE else View.GONE
            retryButton.visibility = if (state.showRetry) View.VISIBLE else View.GONE
            titleText.text = state.titleRes?.let(itemView.context::getString).orEmpty()
            bodyText.text = state.bodyRes?.let(itemView.context::getString).orEmpty()
            bodyText.visibility = if (state.bodyRes == null) View.GONE else View.VISIBLE
        }
    }

    data class FooterState(
        val titleRes: Int?,
        val bodyRes: Int?,
        val showProgress: Boolean,
        val showRetry: Boolean
    ) {
        companion object {
            val Hidden = FooterState(
                titleRes = null,
                bodyRes = null,
                showProgress = false,
                showRetry = false
            )

            fun from(state: ActivityFeedUiState): FooterState {
                return when {
                    state.isInitialLoading -> FooterState(
                        titleRes = R.string.activity_feed_loading,
                        bodyRes = R.string.activity_feed_loading_more_body,
                        showProgress = true,
                        showRetry = false
                    )

                    !state.errorMessage.isNullOrBlank() && state.sessions.isEmpty() -> FooterState(
                        titleRes = R.string.activity_feed_error_title,
                        bodyRes = R.string.activity_feed_error_body,
                        showProgress = false,
                        showRetry = true
                    )

                    state.sessions.isEmpty() -> FooterState(
                        titleRes = R.string.activity_feed_empty_title,
                        bodyRes = R.string.activity_feed_empty_body,
                        showProgress = false,
                        showRetry = false
                    )

                    state.isLoadingMore -> FooterState(
                        titleRes = R.string.activity_feed_loading_more,
                        bodyRes = R.string.activity_feed_loading_more_body,
                        showProgress = true,
                        showRetry = false
                    )

                    !state.loadMoreErrorMessage.isNullOrBlank() -> FooterState(
                        titleRes = R.string.activity_feed_error_more_title,
                        bodyRes = R.string.activity_feed_error_more_body,
                        showProgress = false,
                        showRetry = true
                    )

                    !state.hasMore -> FooterState(
                        titleRes = R.string.activity_feed_end_title,
                        bodyRes = R.string.activity_feed_end_body,
                        showProgress = false,
                        showRetry = false
                    )

                    else -> Hidden
                }
            }
        }
    }
}
