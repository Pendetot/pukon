package com.lazydev.fastswap

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

class RebootHelper {
    private val rootManager = RootManager()
    private val TAG = "RebootHelper"
    
    // Shared flow untuk log messages
    private val _logFlow = MutableSharedFlow<String>(replay = 10)
    val logFlow: SharedFlow<String> = _logFlow
    
    private suspend fun logMessage(message: String) {
        Log.d(TAG, message)
        _logFlow.emit(message)
    }

    suspend fun rebootDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            logMessage("Preparing for system reboot...")
            
            // Sync filesystems first
            logMessage("Syncing filesystems...")
            rootManager.executeCommand("sync")
            
            // Safer reboot methods (in order of preference)
            val methods = listOf(
                "svc power reboot",  // Android service manager
                "reboot",            // Standard command
                "setprop sys.powerctl reboot",  // System property
                "am start -a android.intent.action.REBOOT"  // Activity manager
            )
            
            var success = false
            for (method in methods) {
                logMessage("Trying reboot method: $method")
                val result = rootManager.executeCommand(method)
                
                if (result.isSuccess) {
                    logMessage("Reboot command succeeded: $method")
                    success = true
                    break
                } else {
                    logMessage("Reboot method failed: $method")
                }
            }
            
            if (!success) {
                logMessage("All standard reboot methods failed. Please reboot manually.")
                return@withContext false
            }
            
            return@withContext true
        } catch (e: Exception) {
            logMessage("ERROR rebooting device: ${e.message}")
            return@withContext false
        }
    }
}