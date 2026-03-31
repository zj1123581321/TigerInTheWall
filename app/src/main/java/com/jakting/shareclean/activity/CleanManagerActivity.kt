package com.jakting.shareclean.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakting.shareclean.BaseActivity
import com.jakting.shareclean.R
import com.jakting.shareclean.data.App
import com.jakting.shareclean.data.AppInfo
import com.jakting.shareclean.data.BlockStatus
import com.jakting.shareclean.data.ListItem
import com.jakting.shareclean.databinding.ActivityCleanManagerBinding
import com.jakting.shareclean.utils.application.Companion.chipBrowser
import com.jakting.shareclean.utils.application.Companion.chipShare
import com.jakting.shareclean.utils.application.Companion.chipText
import com.jakting.shareclean.utils.application.Companion.chipView
import com.jakting.shareclean.utils.application.Companion.kv
import com.jakting.shareclean.utils.application.Companion.settingSharedPreferences
import com.jakting.shareclean.utils.deleteIfwFiles
import com.jakting.shareclean.utils.getAppIcon
import com.jakting.shareclean.utils.getColorFromAttr
import com.jakting.shareclean.utils.toast
import com.jakting.shareclean.utils.writeIfwFiles
import kotlinx.coroutines.launch
import java.util.Locale


class CleanManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityCleanManagerBinding
    private var applicationIconMap = hashMapOf<String, Drawable>()
    private lateinit var searchListener: SearchView.OnQueryTextListener
    lateinit var data: List<App>

    // Batch mode state
    private var isBatchMode = false
    private val selectedApps = mutableSetOf<String>()
    private var menu: Menu? = null

    // Status filter state
    private var showUntouched = true
    private var showPartial = true
    private var showFullyBlocked = true

    // Current search query
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityCleanManagerBinding.inflate(layoutInflater)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manager_clean, menu)
        this.menu = menu
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val searchView = menu.findItem(R.id.menu_manager_clean_search).actionView as SearchView
        searchView.setOnQueryTextListener(searchListener)
        searchView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(arg0: View) {
                binding.appBarLayout.setExpanded(false, true)
            }

            override fun onViewDetachedFromWindow(v: View) {
                binding.appBarLayout.setExpanded(false, true)
            }
        })
        searchView.findViewById<View>(androidx.appcompat.R.id.search_edit_frame).layoutDirection =
            View.LAYOUT_DIRECTION_INHERIT
        searchView.findViewById<View>(androidx.appcompat.R.id.search_plate).background = null
        searchView.findViewById<View>(androidx.appcompat.R.id.search_mag_icon).visibility =
            View.GONE
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_batch_select_all -> {
                selectAllVisible()
                true
            }
            R.id.menu_batch_deselect_all -> {
                deselectAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        setChip(chipShare, chipView, chipText, chipBrowser)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isBatchMode) {
            exitBatchMode()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun initView() {
        setSupportActionBar(findViewById(R.id.toolbar))
        searchListener = object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return false
            }

            override fun onQueryTextChange(query: String): Boolean {
                currentQuery = query
                refreshList()
                return false
            }
        }

        binding.managerCleanRecyclerView.linear().setup {
            addType<ListItem.SectionHeader>(R.layout.item_section_header)
            addType<ListItem.AppItem>(R.layout.item_manager_clean)
            onPayload {
                if (getModel<Any>() is ListItem.AppItem) {
                    val app = (getModel<Any>() as ListItem.AppItem).app
                    val checkbox = findView<CheckBox>(R.id.app_checkbox)
                    checkbox.visibility = if (isBatchMode) View.VISIBLE else View.GONE
                    checkbox.isChecked = selectedApps.contains(app.packageName)
                }
            }
            onBind {
                when (val item = getModel<Any>()) {
                    is ListItem.SectionHeader -> {
                        findView<TextView>(R.id.section_title).text =
                            String.format(item.title, item.count)
                    }
                    is ListItem.AppItem -> bindAppItem(this, item.app)
                    else -> {}
                }
            }
            onClick(R.id.app_layout) {
                val item = getModel<Any>()
                if (item !is ListItem.AppItem) return@onClick
                val app = item.app
                if (isBatchMode) {
                    toggleSelection(app)
                    val checkbox = findView<CheckBox>(R.id.app_checkbox)
                    checkbox.isChecked = selectedApps.contains(app.packageName)
                } else {
                    val shareSize = app.blockSummary.shareTotal
                    val viewSize = app.blockSummary.viewTotal
                    val textSize = app.blockSummary.textTotal
                    val browserSize = app.blockSummary.browserTotal
                    val intent = Intent(this@CleanManagerActivity, DetailsActivity::class.java)
                    intent.putExtra("app", app)
                    intent.putExtra("shareSize", shareSize)
                    intent.putExtra("viewSize", viewSize)
                    intent.putExtra("textSize", textSize)
                    intent.putExtra("browserSize", browserSize)
                    startActivity(intent)
                }
            }
            onLongClick(R.id.app_layout) {
                val item = getModel<Any>()
                if (item !is ListItem.AppItem) return@onLongClick
                if (!isBatchMode) {
                    enterBatchMode()
                    toggleSelection(item.app)
                    val checkbox = findView<CheckBox>(R.id.app_checkbox)
                    checkbox.isChecked = true
                }
            }
        }

        binding.managerCleanStateLayout.onRefresh {
            lifecycleScope.launch {
                setChip(false)
                setStatusChip(false)
                data = if (settingSharedPreferences.getBoolean("pref_system_app", true)) {
                    AppInfo().getAppList()
                        .filter { !it.packageName.startsWith("com.jakting.shareclean") }
                } else {
                    AppInfo().getAppList()
                        .filter { !it.isSystem && !it.packageName.startsWith("com.jakting.shareclean") }
                }
                refreshList()
                setChip(true)
                setStatusChip(true)
                binding.managerCleanStateLayout.showContent()
            }
        }.showLoading()

        // Type filter chips — only rebuild list, no full reload
        binding.managerCleanChipShare.setOnCheckedChangeListener { _, isChecked ->
            chipShare = isChecked
            refreshList()
        }
        binding.managerCleanChipView.setOnCheckedChangeListener { _, isChecked ->
            chipView = isChecked
            refreshList()
        }
        binding.managerCleanChipText.setOnCheckedChangeListener { _, isChecked ->
            chipText = isChecked
            refreshList()
        }
        binding.managerCleanChipBrowser.setOnCheckedChangeListener { _, isChecked ->
            chipBrowser = isChecked
            refreshList()
        }

        // Status filter chips
        binding.statusChipUntouched.setOnCheckedChangeListener { _, isChecked ->
            showUntouched = isChecked
            refreshList()
        }
        binding.statusChipPartial.setOnCheckedChangeListener { _, isChecked ->
            showPartial = isChecked
            refreshList()
        }
        binding.statusChipFullyBlocked.setOnCheckedChangeListener { _, isChecked ->
            showFullyBlocked = isChecked
            refreshList()
        }

        // Batch action buttons
        binding.batchBlockButton.setOnClickListener { showBatchConfirmDialog(true) }
        binding.batchUnblockButton.setOnClickListener { showBatchConfirmDialog(false) }
    }

    private fun bindAppItem(holder: com.drake.brv.BindingAdapter.BindingViewHolder, app: App) {
        val summary = app.blockSummary

        holder.findView<TextView>(R.id.app_name).text = app.appName
        holder.findView<TextView>(R.id.app_package_name).text = app.packageName

        // Status indicator strip
        val statusIndicator = holder.findView<View>(R.id.status_indicator)
        when (summary.status) {
            BlockStatus.FULLY_BLOCKED -> statusIndicator.setBackgroundColor(getColorFromAttr(R.attr.colorTertiary))
            BlockStatus.PARTIALLY_BLOCKED -> statusIndicator.setBackgroundColor(getColorFromAttr(R.attr.colorPrimary))
            BlockStatus.UNTOUCHED -> statusIndicator.setBackgroundColor(Color.TRANSPARENT)
        }

        // App icon
        val appIcon = holder.findView<ImageView>(R.id.app_icon)
        val pkg = app.packageName
        val cachedIcon = applicationIconMap[pkg]
        if (cachedIcon != null) {
            appIcon.setImageDrawable(cachedIcon)
        } else {
            appIcon.setImageDrawable(null)
            lifecycleScope.launch {
                getAppIcon(pkg)?.let {
                    applicationIconMap[pkg] = it
                    appIcon.setImageDrawable(it)
                }
            }
        }

        // System icon
        holder.findView<ImageView>(R.id.app_icon_system).visibility =
            if (app.isSystem) View.VISIBLE else View.GONE

        // Per-type counts with blocked/total format and color coding
        bindTypeCount(
            holder, R.id.app_icon_share, R.id.app_intent_count_share,
            app.hasType.share, summary.shareBlocked, summary.shareTotal
        )
        bindTypeCount(
            holder, R.id.app_icon_view, R.id.app_intent_count_view,
            app.hasType.view, summary.viewBlocked, summary.viewTotal
        )
        bindTypeCount(
            holder, R.id.app_icon_text, R.id.app_intent_count_text,
            app.hasType.text, summary.textBlocked, summary.textTotal
        )
        bindTypeCount(
            holder, R.id.app_icon_browser, R.id.app_intent_count_browser,
            app.hasType.browser, summary.browserBlocked, summary.browserTotal
        )

        // Batch mode checkbox
        val checkbox = holder.findView<CheckBox>(R.id.app_checkbox)
        checkbox.visibility = if (isBatchMode) View.VISIBLE else View.GONE
        checkbox.isChecked = selectedApps.contains(app.packageName)
    }

    private fun bindTypeCount(
        holder: com.drake.brv.BindingAdapter.BindingViewHolder,
        iconId: Int, textId: Int,
        hasType: Boolean, blocked: Int, total: Int
    ) {
        val icon = holder.findView<ImageView>(iconId)
        val text = holder.findView<TextView>(textId)
        icon.visibility = if (hasType) View.VISIBLE else View.GONE
        text.visibility = if (hasType) View.VISIBLE else View.GONE
        if (hasType) {
            text.text = "$blocked/$total"
            val color = when {
                total > 0 && blocked == total -> getColorFromAttr(R.attr.colorTertiary)
                blocked > 0 -> getColorFromAttr(R.attr.colorPrimary)
                else -> getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            text.setTextColor(color)
            icon.setColorFilter(color)
        }
    }

    // --- Data filtering & grouping ---

    private fun refreshList() {
        if (!::data.isInitialized) return

        val query = currentQuery.lowercase(Locale.ROOT)

        // Step 1: filter by type chips (app must have at least one intent matching active types)
        val typeFiltered = data.filter { app ->
            val shareSize = app.intentList.count { it.type == "1_share" || it.type == "2_share_multi" }
            val viewSize = app.intentList.count { it.type == "3_view" }
            val textSize = app.intentList.count { it.type == "4_text" }
            val browserSize = app.intentList.count { it.type == "5_browser" }
            (shareSize > 0 && chipShare) ||
                    (viewSize > 0 && chipView) ||
                    (textSize > 0 && chipText) ||
                    (browserSize > 0 && chipBrowser)
        }

        // Step 2: filter by search query
        val searchFiltered = if (query.isEmpty()) typeFiltered else typeFiltered.filter {
            it.appName.lowercase(Locale.ROOT).contains(query) ||
                    it.packageName.lowercase(Locale.ROOT).contains(query)
        }

        // Step 3: filter by status chips
        val statusFiltered = searchFiltered.filter { app ->
            when (app.blockSummary.status) {
                BlockStatus.UNTOUCHED -> showUntouched
                BlockStatus.PARTIALLY_BLOCKED -> showPartial
                BlockStatus.FULLY_BLOCKED -> showFullyBlocked
            }
        }

        // Step 4: group by status with section headers
        binding.managerCleanRecyclerView.models = buildGroupedList(statusFiltered)
    }

    private fun buildGroupedList(apps: List<App>): List<Any> {
        val untouched = apps.filter { it.blockSummary.status == BlockStatus.UNTOUCHED }
        val partial = apps.filter { it.blockSummary.status == BlockStatus.PARTIALLY_BLOCKED }
        val full = apps.filter { it.blockSummary.status == BlockStatus.FULLY_BLOCKED }

        val result = mutableListOf<Any>()
        if (untouched.isNotEmpty()) {
            result.add(ListItem.SectionHeader(getString(R.string.status_section_untouched), untouched.size))
            result.addAll(untouched.map { ListItem.AppItem(it) })
        }
        if (partial.isNotEmpty()) {
            result.add(ListItem.SectionHeader(getString(R.string.status_section_partial), partial.size))
            result.addAll(partial.map { ListItem.AppItem(it) })
        }
        if (full.isNotEmpty()) {
            result.add(ListItem.SectionHeader(getString(R.string.status_section_fully_blocked), full.size))
            result.addAll(full.map { ListItem.AppItem(it) })
        }
        return result
    }

    // --- Batch mode logic ---

    private fun enterBatchMode() {
        isBatchMode = true
        selectedApps.clear()
        updateBatchUI()
        notifyAllCheckboxChanged()
    }

    private fun exitBatchMode() {
        isBatchMode = false
        selectedApps.clear()
        supportActionBar?.title = getString(R.string.manager_clean_title)
        updateBatchUI()
        notifyAllCheckboxChanged()
    }

    private fun toggleSelection(app: App) {
        if (selectedApps.contains(app.packageName)) {
            selectedApps.remove(app.packageName)
        } else {
            selectedApps.add(app.packageName)
        }
        updateBatchTitle()
    }

    private fun selectAllVisible() {
        val visibleModels = binding.managerCleanRecyclerView.models as? List<*> ?: return
        for (item in visibleModels) {
            if (item is ListItem.AppItem) {
                selectedApps.add(item.app.packageName)
            }
        }
        updateBatchTitle()
        notifyAllCheckboxChanged()
    }

    private fun deselectAll() {
        selectedApps.clear()
        updateBatchTitle()
        notifyAllCheckboxChanged()
    }

    private fun notifyAllCheckboxChanged() {
        val adapter = binding.managerCleanRecyclerView.adapter ?: return
        val count = adapter.itemCount
        if (count > 0) {
            adapter.notifyItemRangeChanged(0, count, "checkbox")
        }
    }

    private fun updateBatchTitle() {
        if (isBatchMode) {
            supportActionBar?.title = String.format(
                getString(R.string.batch_mode_title),
                selectedApps.size
            )
        }
    }

    private fun updateBatchUI() {
        binding.batchActionBar.visibility = if (isBatchMode) View.VISIBLE else View.GONE
        menu?.findItem(R.id.menu_manager_clean_search)?.isVisible = !isBatchMode
        menu?.findItem(R.id.menu_batch_select_all)?.isVisible = isBatchMode
        menu?.findItem(R.id.menu_batch_deselect_all)?.isVisible = isBatchMode
        updateBatchTitle()
    }

    private fun getActiveTypeNames(): String {
        val types = mutableListOf<String>()
        if (chipShare) types.add(getString(R.string.manager_clean_type_send))
        if (chipView) types.add(getString(R.string.manager_clean_type_view))
        if (chipText) types.add(getString(R.string.manager_clean_type_text))
        if (chipBrowser) types.add(getString(R.string.manager_clean_type_browser))
        return types.joinToString(", ")
    }

    private fun getActiveTypeKeys(): List<String> {
        val types = mutableListOf<String>()
        if (chipShare) {
            types.add("1_share")
            types.add("2_share_multi")
        }
        if (chipView) types.add("3_view")
        if (chipText) types.add("4_text")
        if (chipBrowser) types.add("5_browser")
        return types
    }

    private fun showBatchConfirmDialog(block: Boolean) {
        if (selectedApps.isEmpty()) return

        val typeNames = getActiveTypeNames()
        val count = selectedApps.size

        val title = if (block) R.string.batch_block_confirm_title else R.string.batch_unblock_confirm_title
        val msg = if (block) R.string.batch_block_confirm_msg else R.string.batch_unblock_confirm_msg

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(String.format(getString(msg), typeNames, count))
            .setPositiveButton(R.string.ok) { _, _ ->
                applyBatchOperation(block)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyBatchOperation(block: Boolean) {
        val activeTypes = getActiveTypeKeys()
        val selectedPackages = selectedApps.toSet()

        var affectedCount = 0
        for (app in data) {
            if (!selectedPackages.contains(app.packageName)) continue
            affectedCount++
            for (intent in app.intentList) {
                if (activeTypes.contains(intent.type)) {
                    val keyName = "${intent.type}/${intent.packageName}/${intent.component}"
                    kv.encode(keyName, block)
                }
            }
        }

        if (deleteIfwFiles("all") && writeIfwFiles()) {
            toast(String.format(getString(R.string.batch_apply_success), affectedCount))
        }
        exitBatchMode()
        // Reload to refresh block summaries
        binding.managerCleanStateLayout.showLoading()
    }

    private fun setChip(vararg args: Boolean) {
        if (args.size == 1) {
            binding.managerCleanChipShare.isEnabled = args[0]
            binding.managerCleanChipView.isEnabled = args[0]
            binding.managerCleanChipText.isEnabled = args[0]
            binding.managerCleanChipBrowser.isEnabled = args[0]
        } else {
            binding.managerCleanChipShare.isChecked = args[0]
            binding.managerCleanChipView.isChecked = args[1]
            binding.managerCleanChipText.isChecked = args[2]
            binding.managerCleanChipBrowser.isChecked = args[3]
        }
    }

    private fun setStatusChip(enabled: Boolean) {
        binding.statusChipUntouched.isEnabled = enabled
        binding.statusChipPartial.isEnabled = enabled
        binding.statusChipFullyBlocked.isEnabled = enabled
    }
}
