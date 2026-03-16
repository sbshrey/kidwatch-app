package com.kidwatch.app

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class TopAppsActivity : AppCompatActivity() {

    private lateinit var topAppsContainer: LinearLayout
    private lateinit var topAppsEmpty: TextView
    private lateinit var searchInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var chipSortTime: Chip
    private lateinit var chipSortName: Chip
    private lateinit var repository: LocalMonitoringRepository
    private var allApps: List<AppUsageItem> = emptyList()
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        topAppsContainer = findViewById(R.id.topAppsContainer)
        topAppsEmpty = findViewById(R.id.tvTopAppsEmpty)
        searchInput = findViewById(R.id.etSearchApps)
        chipSortTime = findViewById(R.id.chipSortTime)
        chipSortName = findViewById(R.id.chipSortName)
        repository = LocalMonitoringRepository(applicationContext)
        packageFilter = intent.getStringExtra(EXTRA_PACKAGE_FILTER)?.takeIf { it.isNotBlank() }

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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            runCatching { setImageDrawable(packageManager.getApplicationIcon(item.packageName)) }
                .onFailure { setImageResource(android.R.drawable.sym_def_app_icon) }
        }

        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(10)
            }
            text = item.appName
            textSize = 16f
            setTypeface(typeface, Typeface.NORMAL)
        }

        val subtitle = TextView(this).apply {
            text = getString(R.string.top_apps_item_minutes, item.minutes)
            setTextColor(ContextCompat.getColor(this@TopAppsActivity, android.R.color.darker_gray))
        }

        row.addView(iconView)
        row.addView(title)
        row.addView(subtitle)
        return row
    }

    private fun resolveAppName(packageName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
