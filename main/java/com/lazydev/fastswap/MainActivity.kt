package com.lazydev.fastswap

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.lazydev.fastswap.databinding.ActivityMainBinding
import com.lazydev.fastswap.github.GitHubRelease
import com.lazydev.fastswap.github.GitHubRepository
import com.lazydev.fastswap.github.ReleaseAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val rootManager = RootManager()
    private val kernelFlasher = KernelFlasher()
    private val rebootHelper = RebootHelper()
    private val deviceInfoManager = DeviceInfoManager()
    
    // For GitHub features
    private lateinit var githubRepository: GitHubRepository
    private lateinit var releaseAdapter: ReleaseAdapter
    
    // For console log
    private val logMessages = mutableListOf<String>()
    private lateinit var logAdapter: LogAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleKernelZipSelection(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize log adapter
        logAdapter = LogAdapter(logMessages)
        
        // Initialize GitHub repository
        githubRepository = GitHubRepository(this)
        releaseAdapter = ReleaseAdapter(onDownloadClick = { release ->
            handleReleaseDownload(release)
        })

        setupUI()
        checkRootAccess()
        loadDeviceInfo()
    }

    private fun setupUI() {
        // Set up action buttons
        binding.selectKernelButton.setOnClickListener {
            openFilePicker()
        }
        
        // View log button (initially hidden)
        binding.viewLogButton.setOnClickListener {
            showLogConsole()
        }
        
        // GitHub features
        binding.releasesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.releasesRecyclerView.adapter = releaseAdapter
        
        // Set up get kernel button
        binding.getKernelButton.setOnClickListener {
            getKernelFromGitHub()
        }
        
        // Initially disable kernel selection button until root is confirmed
        binding.selectKernelButton.isEnabled = false
    }

    private fun loadDeviceInfo() {
        binding.deviceInfoCard.visibility = View.GONE // Hide initially
        
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Load device and kernel info in background
                val deviceInfo = withContext(Dispatchers.IO) {
                    deviceInfoManager.getDeviceInfo()
                }
                
                // Update the UI with device info (on Main thread)
                binding.deviceModelText.text = deviceInfo.deviceModel
                binding.deviceBrandText.text = deviceInfo.deviceBrand
                binding.androidVersionText.text = "Android ${deviceInfo.androidVersion}"
                
                // Update kernel info
                binding.kernelVersionText.text = deviceInfo.kernelVersion
                binding.kernelBuildDateText.text = deviceInfo.kernelBuildDate
                
                // Show the card
                binding.deviceInfoCard.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Handle error
                Snackbar.make(
                    binding.root,
                    "Failed to load device info: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkRootAccess() {
        binding.rootStatusCard.visibility = View.VISIBLE
        binding.rootStatusProgress.visibility = View.VISIBLE
        binding.rootStatusIcon.visibility = View.GONE
        
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val hasRoot = withContext(Dispatchers.IO) {
                    rootManager.checkRootAccess()
                }

                // Update UI based on root check result
                binding.rootStatusProgress.visibility = View.GONE
                binding.rootStatusIcon.visibility = View.VISIBLE
                
                if (!hasRoot) {
                    binding.rootStatusIcon.setImageDrawable(
                        ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_error)
                    )
                    binding.rootStatusText.text = "No Root Access"
                    binding.rootStatusDescription.text = "FastSwap requires root access to flash kernels"
                    
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Root Access Required")
                        .setMessage("FastSwap requires root access to flash kernels. Please grant root permissions and restart the app.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                } else {
                    binding.rootStatusIcon.setImageDrawable(
                        ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_check_circle)
                    )
                    binding.rootStatusText.text = "Root Access Granted"
                    binding.rootStatusDescription.text = "Your device has root access"
                    binding.selectKernelButton.isEnabled = true
                }
            } catch (e: Exception) {
                // Handle error in root check
                binding.rootStatusProgress.visibility = View.GONE
                binding.rootStatusIcon.visibility = View.VISIBLE
                binding.rootStatusIcon.setImageDrawable(
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_error)
                )
                binding.rootStatusText.text = "Root Check Failed"
                binding.rootStatusDescription.text = "Error: ${e.message}"
                
                Snackbar.make(
                    binding.root,
                    "Failed to check root access: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary:Download"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun handleKernelZipSelection(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Main) {
            val fileName = withContext(Dispatchers.IO) {
                FileUtils.getFileName(contentResolver, uri)
            }
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Flash Kernel")
                .setMessage("Are you sure you want to flash kernel: $fileName?\n\nMake sure this is a compatible kernel for your device. Flashing an incompatible kernel may cause your device to not boot properly.")
                .setPositiveButton("Flash") { _, _ ->
                    flashKernel(uri)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun flashKernel(uri: Uri) {
        // Show flashing in progress UI
        binding.flashingCard.visibility = View.VISIBLE
        binding.flashingProgress.isIndeterminate = true
        binding.flashingStatusText.text = "Preparing to flash kernel..."
        binding.flashingProgressText.text = "Starting..."
        binding.selectKernelButton.isEnabled = false
        
        // Clear previous logs
        logMessages.clear()
        logAdapter.notifyDataSetChanged()
        
        // Show log button
        binding.viewLogButton.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Collect logs from KernelFlasher
                val logCollectionJob = launch {
                    kernelFlasher.logFlow.collect { log ->
                        logMessages.add(log)
                        logAdapter.notifyItemInserted(logMessages.size - 1)
                    }
                }
                
                // Create temp file in background
                addLog("Creating temporary file from selected ZIP...")
                val tempFile = withContext(Dispatchers.IO) {
                    FileUtils.createTempFileFromUri(this@MainActivity, uri)
                }
                
                // Update UI for extraction phase
                binding.flashingStatusText.text = "Extracting kernel files..."
                binding.flashingProgressText.text = "Extracting..."
                binding.flashingProgress.isIndeterminate = false
                binding.flashingProgress.progress = 25
                
                // Extract kernel in background
                addLog("Starting kernel extraction...")
                val extractResult = withContext(Dispatchers.IO) {
                    kernelFlasher.extractKernel(tempFile.absolutePath)
                }
                
                if (extractResult) {
                    // Update UI for flashing phase
                    binding.flashingStatusText.text = "Flashing kernel to boot partition..."
                    binding.flashingProgressText.text = "Flashing..."
                    binding.flashingProgress.progress = 75
                    
                    // Flash kernel in background
                    addLog("Starting kernel flashing...")
                    val flashResult = withContext(Dispatchers.IO) {
                        kernelFlasher.flashKernel()
                    }
                    
                    if (flashResult) {
                        // Success UI update
                        binding.flashingStatusText.text = "Kernel flashed successfully!"
                        binding.flashingProgressText.text = "Complete"
                        binding.flashingProgress.progress = 100
                        
                        // Reload device info to show new kernel
                        addLog("Reloading device information...")
                        loadDeviceInfo()
                        
                        // Show reboot dialog
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Success")
                                .setMessage("Kernel flashed successfully. Device will reboot now.")
                                .setPositiveButton("Reboot") { _, _ ->
                                    lifecycleScope.launch {
                                        // Collect reboot logs
                                        launch {
                                            rebootHelper.logFlow.collect { log ->
                                                logMessages.add(log)
                                                logAdapter.notifyItemInserted(logMessages.size - 1)
                                            }
                                        }
                                        
                                        addLog("Initiating device reboot...")
                                        val rebootSuccess = withContext(Dispatchers.IO) {
                                            rebootHelper.rebootDevice()
                                        }
                                        
                                        if (!rebootSuccess) {
                                            addLog("WARNING: Automatic reboot failed. Please reboot manually.")
                                            showError("Reboot failed. Please reboot manually.")
                                        }
                                    }
                                }
                                .setNeutralButton("View Log") { _, _ ->
                                    showLogConsole()
                                }
                                .setCancelable(false)
                                .show()
                        }
                    } else {
                        showError("Failed to flash kernel")
                    }
                } else {
                    showError("Failed to extract kernel files")
                }
                
                // Clean up temp file in background
                addLog("Cleaning up temporary files...")
                withContext(Dispatchers.IO) {
                    try {
                        tempFile.delete()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error deleting temp file: ${e.message}")
                        addLog("WARNING: Failed to delete temp file: ${e.message}")
                    }
                }
                
                // Cancel log collection
                logCollectionJob.cancel()
            } catch (e: Exception) {
                addLog("ERROR: ${e.message}")
                showError("Error: ${e.message}")
            } finally {
                binding.selectKernelButton.isEnabled = true
            }
        }
    }

    private fun showError(message: String) {
        // Update UI to show error
        binding.flashingStatusText.text = message
        binding.flashingProgressText.text = "Failed"
        binding.flashingProgress.progress = 0
        binding.flashingProgress.isIndeterminate = false
        
        // Add to log
        addLog("ERROR: $message")
        
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.error_red))
            .show()
    }
    
    // Helper function to add log entries
    private fun addLog(message: String) {
        Log.d("MainActivity", message)
        logMessages.add(message)
        logAdapter.notifyItemInserted(logMessages.size - 1)
    }
    
    // Show console log dialog
    private fun showLogConsole() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_console_log, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.log_recycler_view)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = logAdapter
        
        val dialog = AlertDialog.Builder(this, R.style.ConsoleLogDialogTheme)
            .setTitle("Console Log")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        
        dialog.show()
        
        // Scroll to bottom
        recyclerView.scrollToPosition(logMessages.size - 1)
    }
    
    //
    // GitHub Integration Methods
    //
    
    private fun getKernelFromGitHub() {
        val repoUrl = binding.githubUrlInput.text.toString().trim()
        if (repoUrl.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.invalid_url), Snackbar.LENGTH_SHORT).show()
            return
        }
        
        binding.githubProgress.visibility = View.VISIBLE
        binding.releasesRecyclerView.visibility = View.GONE
        binding.noReleasesText.visibility = View.GONE
        
        lifecycleScope.launch {
            // Collect logs from GitHub repository
            val logJob = launch {
                githubRepository.logFlow.collect { log ->
                    logMessages.add(log)
                    logAdapter.notifyItemInserted(logMessages.size - 1)
                }
            }
            
            addLog("Detecting GitHub repository type: $repoUrl")
            
            // First try to get releases
            val releases = withContext(Dispatchers.IO) {
                githubRepository.getRepoReleases(repoUrl)
            }
            
            if (releases != null && releases.isNotEmpty()) {
                // Repository has releases
                addLog("Found ${releases.size} releases")
                releaseAdapter.updateReleases(releases)
                binding.releasesRecyclerView.visibility = View.VISIBLE
                binding.noReleasesText.visibility = View.GONE
                binding.githubProgress.visibility = View.GONE
                
                // If only one release, ask if user wants to download it directly
                if (releases.size == 1) {
                    val release = releases[0]
                    // Find a suitable asset
                    val kernelAsset = release.assets.find { 
                        it.name.endsWith(".zip") && (
                            it.name.contains("kernel", ignoreCase = true) || 
                            it.name.contains("boot", ignoreCase = true)
                        )
                    }
                    
                    if (kernelAsset != null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Download Kernel")
                            .setMessage("Found a kernel in release ${release.tag_name}: ${kernelAsset.name}\n\nDo you want to download it?")
                            .setPositiveButton("Download") { _, _ ->
                                handleReleaseDownload(release)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } else {
                // Repository doesn't have releases or couldn't access them
                // Try to get the repo info and clone it
                addLog("No releases found, downloading repository as ZIP")
                val downloadedFile = withContext(Dispatchers.IO) {
                    githubRepository.gitClone(repoUrl)
                }
                
                binding.githubProgress.visibility = View.GONE
                
                if (downloadedFile != null) {
                    addLog("Repository downloaded as ZIP: ${downloadedFile.name}")
                    Snackbar.make(
                        binding.root,
                        getString(R.string.download_complete),
                        Snackbar.LENGTH_LONG
                    ).show()
                    
                    // Ask if user wants to flash this kernel
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Flash Downloaded Kernel?")
                        .setMessage("Would you like to flash the downloaded kernel ZIP: ${downloadedFile.name}?")
                        .setPositiveButton("Flash") { _, _ ->
                            flashKernel(Uri.fromFile(downloadedFile))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    addLog("Failed to download repository")
                    binding.noReleasesText.visibility = View.VISIBLE
                    Snackbar.make(
                        binding.root,
                        getString(R.string.download_failed),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            
            // Cancel log collection
            logJob.cancel()
        }
    }

    private fun handleReleaseDownload(release: GitHubRelease) {
        // Find a suitable asset (ZIP file) to download
        val kernelAsset = release.assets.find { 
            it.name.endsWith(".zip") && (
                it.name.contains("kernel", ignoreCase = true) || 
                it.name.contains("boot", ignoreCase = true)
            )
        }
        
        if (kernelAsset == null) {
            Snackbar.make(
                binding.root,
                "No suitable kernel ZIP file found in this release",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        
        binding.githubProgress.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            // Collect logs from GitHub repository
            val logJob = launch {
                githubRepository.logFlow.collect { log ->
                    logMessages.add(log)
                    logAdapter.notifyItemInserted(logMessages.size - 1)
                }
            }
            
            addLog("Downloading kernel: ${kernelAsset.name}")
            val downloadedFile = withContext(Dispatchers.IO) {
                githubRepository.downloadRelease(kernelAsset.browser_download_url, kernelAsset.name)
            }
            
            binding.githubProgress.visibility = View.GONE
            
            if (downloadedFile != null) {
                addLog("Kernel downloaded: ${downloadedFile.name}")
                Snackbar.make(
                    binding.root,
                    getString(R.string.download_complete),
                    Snackbar.LENGTH_LONG
                ).show()
                
                // Ask if user wants to flash this kernel
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Flash Downloaded Kernel?")
                    .setMessage("Would you like to flash the downloaded kernel: ${downloadedFile.name}?")
                    .setPositiveButton("Flash") { _, _ ->
                        flashKernel(Uri.fromFile(downloadedFile))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.download_failed),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            
            // Cancel log collection
            logJob.cancel()
        }
    }
    
    // RecyclerView adapter for logs
    private class LogAdapter(private val logs: List<String>) : 
        RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
        
        class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(8, 2, 8, 2)
                setTextColor(ContextCompat.getColor(parent.context, android.R.color.white))
            }
            return LogViewHolder(textView)
        }
        
        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val logEntry = logs[position]
            
            // Color-code log entries based on type
            when {
                logEntry.contains("ERROR") -> {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.textView.context, R.color.error_red))
                }
                logEntry.contains("WARNING") -> {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.textView.context, R.color.warning_yellow))
                }
                logEntry.contains("SUCCESS") -> {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.textView.context, R.color.success_green))
                }
                else -> {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.textView.context, android.R.color.white))
                }
            }
            
            holder.textView.text = logEntry
        }
        
        override fun getItemCount() = logs.size
    }
}