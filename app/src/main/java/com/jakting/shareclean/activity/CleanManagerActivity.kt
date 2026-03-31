package com.jakting.shareclean.activity

import android.content.Intent
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
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakting.shareclean.BaseActivity
import com.jakting.shareclean.R
import com.jakting.shareclean.data.App
import com.jakting.shareclean.data.AppInfo
import com.jakting.shareclean.databinding.ActivityCleanManagerBinding
import com.jakting.shareclean.utils.application.Companion.chipBrowser
import com.jakting.shareclean.utils.application.Companion.chipShare
import com.jakting.shareclean.utils.application.Companion.chipText
import com.jakting.shareclean.utils.application.Companion.chipView
import com.jakting.shareclean.utils.application.Companion.kv
import com.jakting.shareclean.utils.application.Companion.settingSharedPreferences
import com.jakting.shareclean.utils.deleteIfwFiles
import com.jakting.shareclean.utils.getAppIcon
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
    private val selectedApps = mutableSetOf<String>() // packageName set
    private var menu: Menu? = null

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
                binding.managerCleanRecyclerView.models =
                    data.filter {
                        it.appName.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))
                                || it.packageName.lowercase(Locale.ROOT)
                            .contains(query.lowercase(Locale.ROOT))
                    }
                return false
            }
        }

        binding.managerCleanRecyclerView.linear().setup {
            addType<App>(R.layout.item_manager_clean)
            onPayload {
                // Partial update: only refresh checkbox state without rebinding everything
                val checkbox = findView<CheckBox>(R.id.app_checkbox)
                checkbox.visibility = if (isBatchMode) View.VISIBLE else View.GONE
                checkbox.isChecked = selectedApps.contains(getModel<App>().packageName)
            }
            onBind {
                findView<TextView>(R.id.app_name).text = getModel<App>().appName
                findView<TextView>(R.id.app_package_name).text = getModel<App>().packageName

                findView<ImageView>(R.id.app_icon_share).visibility =
                    if (getModel<App>().hasType.share) View.VISIBLE else View.GONE
                findView<TextView>(R.id.app_intent_count_share).visibility =
                    if (getModel<App>().hasType.share) View.VISIBLE else View.GONE
                findView<ImageView>(R.id.app_icon_view).visibility =
                    if (getModel<App>().hasType.view) View.VISIBLE else View.GONE
                findView<TextView>(R.id.app_intent_count_view).visibility =
                    if (getModel<App>().hasType.view) View.VISIBLE else View.GONE
                findView<ImageView>(R.id.app_icon_text).visibility =
                    if (getModel<App>().hasType.text) View.VISIBLE else View.GONE
                findView<TextView>(R.id.app_intent_count_text).visibility =
                    if (getModel<App>().hasType.text) View.VISIBLE else View.GONE
                findView<ImageView>(R.id.app_icon_browser).visibility =
                    if (getModel<App>().hasType.browser) View.VISIBLE else View.GONE
                findView<TextView>(R.id.app_intent_count_browser).visibility =
                    if (getModel<App>().hasType.browser) View.VISIBLE else View.GONE

                val appIcon = findView<ImageView>(R.id.app_icon)
                val pkg = getModel<App>().packageName
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
                val shareSize =
                    getModel<App>().intentList.filter { it.type == "1_share" || it.type == "2_share_multi" }.size
                val viewSize = getModel<App>().intentList.filter { it.type == "3_view" }.size
                val textSize = getModel<App>().intentList.filter { it.type == "4_text" }.size
                val browserSize = getModel<App>().intentList.filter { it.type == "5_browser" }.size

                if (!((shareSize > 0 && chipShare) ||
                            (viewSize > 0 && chipView) ||
                            (textSize > 0 && chipText) ||
                            (browserSize > 0 && chipBrowser))
                ) {
                    itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
                }

                findView<ImageView>(R.id.app_icon_system).visibility =
                    when (getModel<App>().isSystem) {
                        true -> View.VISIBLE
                        else -> View.GONE
                    }
                findView<TextView>(R.id.app_intent_count_share).text = shareSize.toString()
                findView<TextView>(R.id.app_intent_count_view).text = viewSize.toString()
                findView<TextView>(R.id.app_intent_count_text).text = textSize.toString()
                findView<TextView>(R.id.app_intent_count_browser).text = browserSize.toString()

                // Batch mode checkbox
                val checkbox = findView<CheckBox>(R.id.app_checkbox)
                checkbox.visibility = if (isBatchMode) View.VISIBLE else View.GONE
                checkbox.isChecked = selectedApps.contains(getModel<App>().packageName)
            }
            onClick(R.id.app_layout) {
                if (isBatchMode) {
                    toggleSelection(getModel<App>())
                    val checkbox = findView<CheckBox>(R.id.app_checkbox)
                    checkbox.isChecked = selectedApps.contains(getModel<App>().packageName)
                } else {
                    val intent = Intent(this@CleanManagerActivity, DetailsActivity::class.java)
                    intent.putExtra("app", getModel<App>())
                    intent.putExtra(
                        "shareSize",
                        itemView.findViewById<TextView>(R.id.app_intent_count_share).text.toString()
                            .toInt()
                    )
                    intent.putExtra(
                        "viewSize",
                        itemView.findViewById<TextView>(R.id.app_intent_count_view).text.toString()
                            .toInt()
                    )
                    intent.putExtra(
                        "textSize",
                        itemView.findViewById<TextView>(R.id.app_intent_count_text).text.toString()
                            .toInt()
                    )
                    intent.putExtra(
                        "browserSize",
                        itemView.findViewById<TextView>(R.id.app_intent_count_browser).text.toString()
                            .toInt()
                    )
                    startActivity(intent)
                }
            }
            onLongClick(R.id.app_layout) {
                if (!isBatchMode) {
                    enterBatchMode()
                    toggleSelection(getModel<App>())
                    val checkbox = findView<CheckBox>(R.id.app_checkbox)
                    checkbox.isChecked = true
                }
            }
        }

        binding.managerCleanStateLayout.onRefresh {
            lifecycleScope.launch {
                setChip(false)
                data = if (settingSharedPreferences.getBoolean("pref_system_app", true)) {
                    AppInfo().getAppList()
                        .filter { !it.packageName.startsWith("com.jakting.shareclean") }
                } else {
                    AppInfo().getAppList()
                        .filter { !it.isSystem && !it.packageName.startsWith("com.jakting.shareclean") }
                }
                binding.managerCleanRecyclerView.models = data
                setChip(true)
                binding.managerCleanStateLayout.showContent()
            }
        }.showLoading()


        binding.managerCleanChipShare.setOnCheckedChangeListener { _, isChecked ->
            chipShare = isChecked
            binding.managerCleanStateLayout.showLoading()
        }
        binding.managerCleanChipView.setOnCheckedChangeListener { _, isChecked ->
            chipView = isChecked
            binding.managerCleanStateLayout.showLoading()
        }
        binding.managerCleanChipText.setOnCheckedChangeListener { _, isChecked ->
            chipText = isChecked
            binding.managerCleanStateLayout.showLoading()
        }
        binding.managerCleanChipBrowser.setOnCheckedChangeListener { _, isChecked ->
            chipBrowser = isChecked
            binding.managerCleanStateLayout.showLoading()
        }

        // Batch action buttons
        binding.batchBlockButton.setOnClickListener { showBatchConfirmDialog(true) }
        binding.batchUnblockButton.setOnClickListener { showBatchConfirmDialog(false) }
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
            if (item is App) {
                selectedApps.add(item.packageName)
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
        // Toggle bottom bar
        binding.batchActionBar.visibility = if (isBatchMode) View.VISIBLE else View.GONE

        // Toggle menu items
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

        // Find all matching apps and their intents
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
    }

    private fun setChip(vararg args: Boolean) {
        if (args.size == 1) { // 禁用/启用
            binding.managerCleanChipShare.isEnabled = args[0]
            binding.managerCleanChipView.isEnabled = args[0]
            binding.managerCleanChipText.isEnabled = args[0]
            binding.managerCleanChipBrowser.isEnabled = args[0]
        } else { // 设置图标状态
            binding.managerCleanChipShare.isChecked = args[0]
            binding.managerCleanChipView.isChecked = args[1]
            binding.managerCleanChipText.isChecked = args[2]
            binding.managerCleanChipBrowser.isChecked = args[3]
        }
    }
}
