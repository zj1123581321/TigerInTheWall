package com.jakting.shareclean.utils

import android.content.Context
import com.topjohnwu.superuser.Shell

fun Context?.moduleInfo(): Array<String> {
    // 检查 Riru 版
    val riruShell = runShell("cat /data/adb/modules/riru_ifw_enhance_tiw/module.prop")
    if (riruShell.isSuccess) {
        val ver = moduleVersion(riruShell)
        if (ver[0].isNotEmpty()) {
            return arrayOf("Riru", ver[0], ver[1])
        }
    }
    // 检查 Zygisk 版
    val zygiskShell = runShell("cat /data/adb/modules/zygisk_ifw_enhance_tiw/module.prop")
    if (zygiskShell.isSuccess) {
        val ver = moduleVersion(zygiskShell)
        if (ver[0].isNotEmpty()) {
            return arrayOf("Zygisk", ver[0], ver[1])
        }
    }
    return arrayOf("", "", "")
}

fun Context?.moduleVersion(sr: Shell.Result): Array<String> {
    val shellResult = sr.getPureCat()
    logd(shellResult)
    if (shellResult.isEmpty()) return arrayOf("", "")
    val version = Regex(".*?version=(.*?),.*?versionCode=(.*?),").find(shellResult)?.groupValues
    return arrayOf(version?.getOrNull(1) ?: "", version?.getOrNull(2) ?: "")
}
