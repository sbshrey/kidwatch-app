package com.kidwatch.app

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class TopAppsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var topAppsContainer: LinearLayout
    private lateinit var topAppsEmpty: TextView
    private lateinit var topAppsMeta: TextView
    private lateinit var searchInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var chipSortTime: Chip
    private lateinit var chipSortName: Chip
    private lateinit var repository: LocalMonitoringRepository
    private var allApps: List<AppUsageItem> = emptyList()
    private var totalTrackedAppsCount: Int = 0
    private var sortByTime: Boolean = true
    private var packageFilter: String? = null

    private data class AppUsageItem(
        val packageName: String,
        val appName: String,
        val minutes: Int
    )

    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_PACKAGE_FILTER = "extra_package_filter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_top_apps)

        btnBack = findViewById(R.id.btnTopAppsBack)
        topAppsContainer = findViewById(R.id.topAppsContainer)
        topAppsEmpty = findViewById(R.id.tvTopAppsEmpty)
        topAppsMeta = findViewById(R.id.tvTopAppsMeta)
        searchInput = findViewById(R.id.etSearchApps)
        chipSortTime = findViewById(R.id.chipSortTime)
        chipSortName = findViewById(R.id.chipSortName)
        repository = LocalMonitoringRepository(applicationContext)
        packageFilter = intent.getStringExtra(EXTRA_PACKAGE_FILTER)?.takeIf { it.isNotBlank() }

        btnBack.setOnClickListener { finish() }

        searchInput.setText(intent.getStringExtra(EXTRA_QUERY).orEmpty())
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderList()
            }
        })

        chipSortTime.setOnClickListener {
            sortByTime = true
            chipSortTime.isChecked = true
            chipSortName.isChecked = false
            renderList()
        }
        chipSortName.setOnClickListener {
            sortByTime = false
            chipSortName.isChecked = true
            chipSortTime.isChecked = false
            renderList()
        }

        loadTopApps()
    }

    private fun loadTopApps() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val dayStart = LocalDate.now()
                .atStartOfDay()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            allApps = repository.aggregateDailyUsage(dayStart, now)
                .filterValues { it > 0L }
                .toList()
                .map { (packageName, minutes) ->
                    AppUsageItem(
                        packageName = packageName,
                        appName = resolveAppName(packageName),
                        minutes = minutes.toInt()
                    )
                }
            totalTrackedAppsCount = allApps.size

            if (packageFilter != null) {
                allApps = allApps.filter { it.packageName == packageFilter }
            }
            renderList()
        }
    }

    private fun renderList() {
        val query = searchInput.text?.toString().orEmpty().trim().lowercase()
        val filtered = allApps
            .filter {
                if (query.isBlank()) true
                else it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
            .let { items ->
                if (sortByTime) items.sortedByDescending { it.minutes }
                else items.sortedBy { it.appName.lowercase() }
            }

        topAppsMeta.text = if (query.isBlank() && packageFilter.isNullOrBlank()) {
            getString(R.string.top_apps_meta, totalTrackedAppsCount)
        } else {
            getString(R.string.top_apps_meta_filtered, filtered.size, totalTrackedAppsCount)
        }

        topAppsContainer.removeAllViews()
        if (filtered.isEmpty()) {
            topAppsEmpty.visibility = View.VISIBLE
            return
        }
        topAppsEmpty.visibility = View.GONE

        filtered.forEach { item ->
            topAppsContainer.addView(createAppRow(item))
        }
    }

    private fun createAppRow(item: AppUsageItem): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            radius = dp(22).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@TopAppsActivity, R.color.kw_outline_variant)
            setCardBackgroundColor(ContextCompat.getColor(this@TopAppsActivity, R.color.kw_card_surface))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val iconShell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = ContextCompat.getDrawable(this@TopAppsActivity, R.drawable.bg_pill_soft)
        }

        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            runCatching { setImageDrawable(packageManager.getApplicationIcon(item.packageName)) }
                .onFailure { setImageResource(R.drawable.ic_placeholder_apps) }
        }
        iconShell.addView(iconView)

        val textColumn = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(12)
                it.marginEnd = dp(10)
            }
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = item.appName
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@TopAppsActivity, R.color.kw_on_surface))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = item.packageName
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@TopAppsActivity, R.color.kw_on_surface_variant))
        }

        val badge = TextView(this).apply {
            text = getString(R.string.top_apps_minutes_badge, item.minutes)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@TopAppsActivity, R.color.kw_on_primary_container))
            background = ContextCompat.getDrawable(this@TopAppsActivity, R.drawable.bg_pill_soft)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        textColumn.addView(title)
        textColumn.addView(subtitle)
        row.addView(iconShell)
        row.addView(textColumn)
        row.addView(badge)
        card.addView(row)
        return card
    }

    private fun resolveAppName(packageName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
