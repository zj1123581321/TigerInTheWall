package com.jakting.shareclean.data

import androidx.databinding.BaseObservable
import java.io.Serializable

data class AppIntent(
    val packageName: String,
    val component: String,
    val componentName: String,
    var checked: Boolean = false,
    val type: String
) : Serializable, BaseObservable()

data class IntentType(
    var share: Boolean,
    var view: Boolean,
    var text: Boolean,
    var browser: Boolean
) : Serializable

enum class BlockStatus {
    UNTOUCHED, PARTIALLY_BLOCKED, FULLY_BLOCKED
}

data class AppBlockSummary(
    val shareBlocked: Int = 0, val shareTotal: Int = 0,
    val viewBlocked: Int = 0, val viewTotal: Int = 0,
    val textBlocked: Int = 0, val textTotal: Int = 0,
    val browserBlocked: Int = 0, val browserTotal: Int = 0
) : Serializable {
    val totalBlocked get() = shareBlocked + viewBlocked + textBlocked + browserBlocked
    val totalCount get() = shareTotal + viewTotal + textTotal + browserTotal
    val status: BlockStatus
        get() = when {
            totalCount == 0 -> BlockStatus.UNTOUCHED
            totalBlocked == totalCount -> BlockStatus.FULLY_BLOCKED
            totalBlocked > 0 -> BlockStatus.PARTIALLY_BLOCKED
            else -> BlockStatus.UNTOUCHED
        }
}

data class App(
    val appName: String = "",
    val packageName: String = "",
    val intentList: ArrayList<AppIntent> = arrayListOf(),
    val isSystem: Boolean = false,
    val hasType: IntentType = IntentType(share = false, view = false, text = false, browser = false),
    var blockSummary: AppBlockSummary = AppBlockSummary()
) : Serializable

sealed interface ListItem {
    data class SectionHeader(val title: String, val count: Int) : ListItem
    data class AppItem(val app: App) : ListItem
}

data class AppDetail(
    var appName: String = "",
    var packageName: String = "",
    var versionCode: String = "",
    var versionName: String = "",
)

@kotlinx.serialization.Serializable
data class ComponentItem(
    val component: String,
    val status: Boolean
)

@kotlinx.serialization.Serializable
data class BackupEntity(
    val settings: Map<String, String>,
    val components: MutableList<ComponentItem>
)
