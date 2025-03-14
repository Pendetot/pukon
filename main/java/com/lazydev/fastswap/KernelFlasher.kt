package com.lazydev.fastswap

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory

class KernelFlasher {
    private val rootManager = RootManager()
    private var workingDir: File? = null
    private var bootImagePath: String? = null
    private val TAG = "KernelFlasher"
    
    // Shared flow untuk log messages yang dapat diobservasi oleh UI
    private val _logFlow = MutableSharedFlow<String>(replay = 100)
    val logFlow: SharedFlow<String> = _logFlow
    
    private suspend fun logMessage(message: String) {
        Log.d(TAG, message)
        _logFlow.emit(message)
    }

    suspend fun extractKernel(zipFilePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            logMessage("Creating temporary working directory...")
            workingDir = createTempDirectory("kernel_extract").toFile()
            
            logMessage("Opening ZIP file: $zipFilePath")
            val zipFile = ZipFile(File(zipFilePath))
            val entries = zipFile.entries
            
            var foundKernel = false
            logMessage("Scanning for kernel files...")
            
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name
                
                if (entry.isDirectory) continue
                
                // Find kernel or boot image files
                if (entryName.endsWith("boot.img") || 
                    entryName.contains("Image") || 
                    entryName.endsWith(".img")) {
                    
                    logMessage("Found kernel file: $entryName")
                    val outputFile = File(workingDir, File(entryName).name)
                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    if (entryName.endsWith("boot.img")) {
                        bootImagePath = outputFile.absolutePath
                        foundKernel = true
                        logMessage("Using boot.img as primary kernel image")
                    } else if (bootImagePath == null && entryName.contains("Image")) {
                        bootImagePath = outputFile.absolutePath
                        foundKernel = true
                        logMessage("Using ${outputFile.name} as kernel image")
                    }
                }
                
                // Extract any scripts/tools that might be needed
                if (entryName.endsWith(".sh") || 
                    entryName.contains("META-INF")) {
                    logMessage("Found additional file: $entryName")
                    val outputFile = File(workingDir, entryName)
                    outputFile.parentFile?.mkdirs()
                    
                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            
            zipFile.close()
            
            if (foundKernel) {
                logMessage("Kernel extraction completed successfully")
            } else {
                logMessage("ERROR: No kernel or boot image found in ZIP")
            }
            
            foundKernel
        } catch (e: Exception) {
            logMessage("ERROR extracting kernel: ${e.message}")
            false
        }
    }

    suspend fun flashKernel(): Boolean = withContext(Dispatchers.IO) {
        if (bootImagePath == null || workingDir == null) {
            logMessage("ERROR: No boot image found or working directory not created")
            return@withContext false
        }
        
        try {
            // Identify boot partition
            logMessage("Identifying boot partition...")
            val currentSlot = getCurrentSlot()
            val bootPartition = if (currentSlot == "a") {
                logMessage("Current slot is A")
                "/dev/block/by-name/boot_a"
            } else {
                logMessage("Current slot is B")
                "/dev/block/by-name/boot_b"
            }
            
            // Verify boot partition exists
            val bootPartitionCheck = rootManager.executeCommand("ls -la $bootPartition")
            if (!bootPartitionCheck.isSuccess) {
                logMessage("WARNING: Boot partition $bootPartition not found, trying alternatives...")
                // Try fallback paths
                val possibleBootPaths = listOf(
                    "/dev/block/bootdevice/by-name/boot${if (currentSlot == "a") "_a" else "_b"}",
                    "/dev/block/platform/*/by-name/boot${if (currentSlot == "a") "_a" else "_b"}",
                    "/dev/block/by-name/boot"
                )
                
                var foundBootPartition = false
                var foundPath = bootPartition
                for (path in possibleBootPaths) {
                    logMessage("Trying alternate boot path: $path")
                    val altCheck = rootManager.executeCommand("ls -la $path")
                    if (altCheck.isSuccess) {
                        logMessage("Found boot partition at: $path")
                        foundPath = path
                        foundBootPartition = true
                        break
                    }
                }
                
                if (!foundBootPartition) {
                    logMessage("ERROR: Could not locate boot partition. Aborting.")
                    return@withContext false
                }
            }
            
            // Backup current boot image (crucial for recovery)
            logMessage("Creating backup of current boot partition...")
            val backupPath = "${workingDir?.absolutePath}/boot_backup.img"
            val backupResult = rootManager.executeCommand("dd if=$bootPartition of=$backupPath bs=4096")
            
            if (!backupResult.isSuccess) {
                logMessage("WARNING: Failed to create full backup: ${backupResult.out.joinToString("\n")}")
            } else {
                logMessage("Backup created successfully at: $backupPath")
            }
            
            // Try to find and use magiskboot for safer flashing (like TWRP)
            logMessage("Searching for magiskboot tool...")
            val magiskbootPath = findMagiskboot()
            
            if (magiskbootPath != null) {
                logMessage("Found magiskboot at: $magiskbootPath - using safer method")
                
                // Extract current boot image
                val bootWorkDir = File(workingDir, "boot_work")
                bootWorkDir.mkdir()
                
                logMessage("Unpacking current boot image...")
                rootManager.executeCommand("dd if=$bootPartition of=${bootWorkDir.absolutePath}/current_boot.img bs=4096")
                rootManager.executeCommand("chmod 755 $magiskbootPath")
                
                val unpackResult = rootManager.executeCommand(
                    "cd ${bootWorkDir.absolutePath} && $magiskbootPath unpack current_boot.img"
                )
                
                if (unpackResult.isSuccess) {
                    logMessage("Boot image unpacked successfully")
                    
                    val bootFile = File(bootImagePath!!)
                    if (bootFile.name.contains("Image") || bootFile.name.contains("kernel")) {
                        // Raw kernel file - only replace kernel
                        logMessage("Replacing only kernel in boot image...")
                        rootManager.executeCommand("cp $bootImagePath ${bootWorkDir.absolutePath}/kernel")
                        
                        // Repack boot image with new kernel only
                        logMessage("Repacking boot image with new kernel...")
                        val repackResult = rootManager.executeCommand(
                            "cd ${bootWorkDir.absolutePath} && $magiskbootPath repack current_boot.img new_boot.img"
                        )
                        
                        if (repackResult.isSuccess) {
                            logMessage("Boot image repacked successfully")
                            
                            // Flash the new boot image
                            logMessage("Flashing new boot image to partition...")
                            val flashResult = rootManager.executeCommand(
                                "dd if=${bootWorkDir.absolutePath}/new_boot.img of=$bootPartition bs=4096"
                            )
                            
                            if (flashResult.isSuccess) {
                                logMessage("Kernel flashed successfully!")
                                return@withContext true
                            } else {
                                logMessage("ERROR: Failed to flash new boot image: ${flashResult.out.joinToString("\n")}")
                                logMessage("Attempting to restore backup...")
                                rootManager.executeCommand("dd if=$backupPath of=$bootPartition bs=4096")
                                return@withContext false
                            }
                        } else {
                            logMessage("ERROR: Failed to repack boot image: ${repackResult.out.joinToString("\n")}")
                        }
                    } else {
                        // Complete boot.img - extract kernel from it
                        logMessage("Unpacking new boot image...")
                        rootManager.executeCommand(
                            "cd ${bootWorkDir.absolutePath} && $magiskbootPath unpack $bootImagePath -o new_parts"
                        )
                        
                        // Copy only kernel from new boot image
                        logMessage("Replacing kernel in current boot image...")
                        rootManager.executeCommand(
                            "cp ${bootWorkDir.absolutePath}/new_parts/kernel ${bootWorkDir.absolutePath}/kernel"
                        )
                        
                        // Repack the boot image
                        logMessage("Repacking boot image with new kernel...")
                        val repackResult = rootManager.executeCommand(
                            "cd ${bootWorkDir.absolutePath} && $magiskbootPath repack current_boot.img new_boot.img"
                        )
                        
                        if (repackResult.isSuccess) {
                            logMessage("Boot image repacked successfully")
                            
                            // Flash the new boot image
                            logMessage("Flashing new boot image to partition...")
                            val flashResult = rootManager.executeCommand(
                                "dd if=${bootWorkDir.absolutePath}/new_boot.img of=$bootPartition bs=4096"
                            )
                            
                            if (flashResult.isSuccess) {
                                logMessage("Kernel flashed successfully!")
                                return@withContext true
                            } else {
                                logMessage("ERROR: Failed to flash new boot image: ${flashResult.out.joinToString("\n")}")
                                logMessage("Attempting to restore backup...")
                                rootManager.executeCommand("dd if=$backupPath of=$bootPartition bs=4096")
                                return@withContext false
                            }
                        } else {
                            logMessage("ERROR: Failed to repack boot image: ${repackResult.out.joinToString("\n")}")
                        }
                    }
                } else {
                    logMessage("WARNING: Failed to unpack boot image: ${unpackResult.out.joinToString("\n")}")
                    logMessage("Falling back to direct flash method...")
                }
            } else {
                logMessage("magiskboot not found, using direct flash method (less safe)")
            }
            
            // Fallback to direct flash - with extra verification
            logMessage("Verifying boot image before flashing...")
            val verifyResult = rootManager.executeCommand("hexdump -n 16 -C $bootImagePath")
            if (verifyResult.isSuccess) {
                logMessage("Boot image header check: ${verifyResult.out.joinToString("\n")}")
                
                // Flash the boot image directly
                logMessage("Flashing boot image to partition...")
                val flashResult = rootManager.executeCommand(
                    "dd if=$bootImagePath of=$bootPartition bs=4096"
                )
                
                if (flashResult.isSuccess) {
                    logMessage("Kernel flashed successfully!")
                    return@withContext true
                } else {
                    logMessage("ERROR: Failed to flash boot image: ${flashResult.out.joinToString("\n")}")
                    logMessage("Attempting to restore backup...")
                    rootManager.executeCommand("dd if=$backupPath of=$bootPartition bs=4096")
                    return@withContext false
                }
            } else {
                logMessage("ERROR: Boot image verification failed")
                return@withContext false
            }
            
            // If we reach here, all methods failed
            return@withContext false
        } catch (e: Exception) {
            logMessage("ERROR flashing kernel: ${e.message}")
            return@withContext false
        } finally {
            // Clean up temp files
            try {
                workingDir?.deleteRecursively()
            } catch (e: Exception) {
                logMessage("WARNING: Failed to clean up temp files: ${e.message}")
            }
        }
    }
    
    private suspend fun findMagiskboot(): String? = withContext(Dispatchers.IO) {
        // Check in PATH
        val inPath = rootManager.executeCommand("which magiskboot")
        if (inPath.isSuccess && inPath.out.isNotEmpty()) {
            return@withContext inPath.out[0]
        }
        
        // Check common locations
        val commonLocations = listOf(
            "/data/adb/magisk/magiskboot",
            "/data/adb/magisk/bin/magiskboot",
            "/data/adb/ksu/bin/magiskboot"
        )
        
        for (location in commonLocations) {
            val check = rootManager.executeCommand("[ -f \"$location\" ] && echo \"exists\"")
            if (check.isSuccess && check.out.isNotEmpty() && check.out[0] == "exists") {
                return@withContext location
            }
        }
        
        // Search for it
        val searchResult = rootManager.executeCommand("find /data -name magiskboot 2>/dev/null | head -n 1")
        if (searchResult.isSuccess && searchResult.out.isNotEmpty()) {
            return@withContext searchResult.out[0]
        }
        
        return@withContext null
    }

    private suspend fun getCurrentSlot(): String = withContext(Dispatchers.IO) {
        val result = rootManager.executeCommand("getprop ro.boot.slot_suffix")
        if (result.out.isNotEmpty() && result.out[0].contains("_")) {
            return@withContext result.out[0].replace("_", "")
        } else {
            return@withContext "a"  // Default to "a" if not A/B or can't determine
        }
    }
}