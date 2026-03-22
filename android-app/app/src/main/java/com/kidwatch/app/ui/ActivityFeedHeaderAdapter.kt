package com.kidwatch.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.kidwatch.app.R
import com.kidwatch.app.services.EvidencePreferences

class ActivityFeedHeaderAdapter(
    private val onOpenManage: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onFilterSelected: (ActivityFeedFilter) -> Unit
) : RecyclerView.Adapter<ActivityFeedHeaderAdapter.HeaderViewHolder>() {

    private var state: ActivityFeedUiState = ActivityFeedUiState(isInitialLoading = true)
    private var permissionMessage: String? = null

    fun submitState(
        state: ActivityFeedUiState,
        permissionMessage: String?
    ) {
        this.state = state
        this.permissionMessage = permissionMessage
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_feed_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(state, permissionMessage)
    }

    override fun getItemCount(): Int = 1

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val summaryText: TextView = itemView.findViewById(R.id.tvActivityFeedSummary)
        private val permissionBannerText: TextView = itemView.findViewById(R.id.tvActivityPermissionBanner)
        private val openManageButton: MaterialButton = itemView.findViewById(R.id.btnActivityOpenSettings)
        private val refreshButton: MaterialButton = itemView.findViewById(R.id.btnActivityRefresh)
        private val filterAllChip: Chip = itemView.findViewById(R.id.chipActivityFilterAll)
        private val filterReviewChip: Chip = itemView.findViewById(R.id.chipActivityFilterReview)
        private val filterUnknownChip: Chip = itemView.findViewById(R.id.chipActivityFilterUnknown)
        private val filterContentChip: Chip = itemView.findViewById(R.id.chipActivityFilterYoutube)

        init {
            openManageButton.setOnClickListener { onOpenManage() }
            refreshButton.setOnClickListener { onRefresh() }
            filterAllChip.setOnClickListener { onFilterSelected(ActivityFeedFilter.ALL) }
            filterReviewChip.setOnClickListener { onFilterSelected(ActivityFeedFilter.NEEDS_REVIEW) }
            filterUnknownChip.setOnClickListener { onFilterSelected(ActivityFeedFilter.UNKNOWN_PERSON) }
            filterContentChip.setOnClickListener { onFilterSelected(ActivityFeedFilter.CONTENT_APPS) }
        }

        fun bind(
            state: ActivityFeedUiState,
            permissionMessage: String?
        ) {
            summaryText.text = when {
                state.isInitialLoading -> {
                    itemView.context.getString(R.string.activity_feed_loading)
                }
                !state.errorMessage.isNullOrBlank() && state.sessions.isEmpty() -> {
                    state.errorMessage
                }
                else -> {
                    itemView.context.getString(
                        R.string.activity_feed_summary,
                        state.totalSessions,
                        EvidencePreferences.RETENTION_DAYS,
                        state.reviewCount,
                        state.unknownCount
                    )
                }
            }

            permissionBannerText.visibility = if (permissionMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            permissionBannerText.text = permissionMessage.orEmpty()

            filterAllChip.isChecked = state.filter == ActivityFeedFilter.ALL
            filterReviewChip.isChecked = state.filter == ActivityFeedFilter.NEEDS_REVIEW
            filterUnknownChip.isChecked = state.filter == ActivityFeedFilter.UNKNOWN_PERSON
            filterContentChip.isChecked = state.filter == ActivityFeedFilter.CONTENT_APPS
        }
    }
}
