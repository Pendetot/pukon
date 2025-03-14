package com.lazydev.fastswap

import android.os.Build

class DeviceInfoManager {
    private val rootManager = RootManager()
    
    data class DeviceInfo(
        val deviceModel: String,
        val deviceBrand: String,
        val androidVersion: String,
        val kernelVersion: String,
        val kernelBuildDate: String
    )
    
    suspend fun getDeviceInfo(): DeviceInfo {
        // Get device information from Android API
        val deviceModel = Build.MODEL
        val deviceBrand = Build.MANUFACTURER
        val androidVersion = Build.VERSION.RELEASE
        
        // Get kernel information using shell commands
        val kernelVersionResult = rootManager.executeCommand("uname -r")
        val kernelVersion = if (kernelVersionResult.isSuccess) {
            kernelVersionResult.out.joinToString(" ").trim()
        } else {
            "Unknown"
        }
        
        // Get kernel build date
        val kernelBuildDateResult = rootManager.executeCommand("uname -v")
        val kernelBuildDate = if (kernelBuildDateResult.isSuccess) {
            kernelBuildDateResult.out.joinToString(" ").trim()
        } else {
            // Try alternative method to get build date from /proc/version
            val procVersionResult = rootManager.executeCommand("cat /proc/version")
            if (procVersionResult.isSuccess) {
                val versionString = procVersionResult.out.joinToString(" ")
                // Extract date pattern like "SMP PREEMPT Tue Feb 15 17:45:33 UTC 2023"
                val datePattern = "(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d+\\s+\\d+:\\d+:\\d+\\s+\\w+\\s+\\d{4}".toRegex()
                val matchResult = datePattern.find(versionString)
                matchResult?.value ?: "Unknown"
            } else {
                "Unknown"
            }
        }
        
        return DeviceInfo(
            deviceModel = deviceModel,
            deviceBrand = deviceBrand,
            androidVersion = androidVersion,
            kernelVersion = kernelVersion,
            kernelBuildDate = kernelBuildDate
        )
    }
}