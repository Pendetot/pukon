package com.lazydev.fastswap

import com.topjohnwu.superuser.Shell

class RootManager {
    init {
        // Set configurations before Shell.getShell is called
        Shell.enableVerboseLogging = true // Ganti BuildConfig.DEBUG dengan nilai tetap
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }

    suspend fun checkRootAccess(): Boolean {
        return Shell.getShell().isRoot
    }

    suspend fun executeCommand(command: String): Shell.Result {
        return Shell.cmd(command).exec()
    }
}