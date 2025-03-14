package com.lazydev.fastswap.github

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GitHubRepository(private val context: Context) {
    private val apiService = GitHubApiService.create()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()
    private val TAG = "GitHubRepository"
    
    // Shared flow for log messages
    private val _logFlow = MutableSharedFlow<String>(replay = 50)
    val logFlow: SharedFlow<String> = _logFlow
    
    private suspend fun logMessage(message: String) {
        Log.d(TAG, message)
        _logFlow.emit(message)
    }
    
    suspend fun getRepoReleases(repoUrl: String): List<GitHubRelease>? = withContext(Dispatchers.IO) {
        try {
            // Parse owner and repo from GitHub URL
            val (owner, repo) = parseGitHubUrl(repoUrl)
            if (owner == null || repo == null) {
                logMessage("ERROR: Invalid GitHub URL format: $repoUrl")
                return@withContext null
            }
            
            logMessage("Fetching releases for $owner/$repo...")
            val response = apiService.getRepoReleases(owner, repo)
            
            if (response.isSuccessful) {
                val releases = response.body()
                if (releases.isNullOrEmpty()) {
                    logMessage("No releases found for $owner/$repo")
                } else {
                    logMessage("Successfully fetched ${releases.size} releases")
                    // Filter for releases with assets
                    val releasesWithAssets = releases.filter { it.assets.isNotEmpty() }
                    if (releasesWithAssets.isEmpty()) {
                        logMessage("No releases with downloadable files found")
                    }
                }
                releases
            } else {
                when (response.code()) {
                    404 -> logMessage("Repository not found: $owner/$repo. Check if the URL is correct and the repository is public.")
                    403 -> logMessage("API rate limit exceeded or requires authentication.")
                    else -> logMessage("ERROR: Failed to fetch releases. Status code: ${response.code()}")
                }
                null
            }
        } catch (e: Exception) {
            logMessage("ERROR fetching releases: ${e.message}")
            null
        }
    }
    
    suspend fun getRepoInfo(repoUrl: String): GitHubRepo? = withContext(Dispatchers.IO) {
        try {
            // Parse owner and repo from GitHub URL
            val (owner, repo) = parseGitHubUrl(repoUrl)
            if (owner == null || repo == null) {
                logMessage("ERROR: Invalid GitHub URL format")
                return@withContext null
            }
            
            logMessage("Fetching repo info for $owner/$repo...")
            val response = apiService.getRepo(owner, repo)
            
            if (response.isSuccessful) {
                val repoInfo = response.body()
                logMessage("Successfully fetched repo info for ${repoInfo?.full_name ?: "$owner/$repo"}")
                repoInfo
            } else {
                logMessage("ERROR: Failed to fetch repo info. Status code: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            logMessage("ERROR fetching repo info: ${e.message}")
            null
        }
    }
    
    suspend fun downloadRelease(downloadUrl: String, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            logMessage("Starting download from $downloadUrl")
            
            val request = Request.Builder()
                .url(downloadUrl)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                logMessage("ERROR: Failed to download file. Status code: ${response.code}")
                return@withContext null
            }
            
            // Get downloads directory
            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            // Create file
            val file = File(downloadsDir, fileName)
            
            // Write to file
            FileOutputStream(file).use { output ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(4 * 1024) // 4K buffer
                    var read: Int
                    var totalRead = 0L
                    val fileSize = response.body?.contentLength() ?: -1L
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        
                        // Log progress every 10%
                        if (fileSize > 0) {
                            val progress = (totalRead * 100 / fileSize).toInt()
                            if (progress % 10 == 0) {
                                logMessage("Download progress: $progress%")
                            }
                        }
                    }
                }
            }
            
            logMessage("Download completed: ${file.absolutePath}")
            file
        } catch (e: IOException) {
            logMessage("ERROR downloading file: ${e.message}")
            null
        }
    }
    
    suspend fun gitClone(repoUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            // Parse owner and repo from GitHub URL
            val (owner, repo) = parseGitHubUrl(repoUrl)
            if (owner == null || repo == null) {
                logMessage("ERROR: Invalid GitHub URL format")
                return@withContext null
            }
            
            logMessage("Repository direct download not supported by git command")
            logMessage("Using GitHub's ZIP download feature instead")
            
            // GitHub provides a download ZIP option for any repo
            // Format: https://github.com/{owner}/{repo}/archive/refs/heads/{branch}.zip
            // Default to "main" branch, fallback to "master" if needed
            var zipUrl = "https://github.com/$owner/$repo/archive/refs/heads/main.zip"
            val fileName = "${repo}-main.zip"
            
            // Try to download from main branch
            logMessage("Downloading repository ZIP from main branch...")
            var file = downloadRelease(zipUrl, fileName)
            
            // If main branch fails, try master branch
            if (file == null) {
                logMessage("Main branch not found, trying master branch...")
                zipUrl = "https://github.com/$owner/$repo/archive/refs/heads/master.zip"
                val masterFileName = "${repo}-master.zip"
                file = downloadRelease(zipUrl, masterFileName)
            }
            
            // Return the downloaded file or null if both attempts failed
            if (file == null) {
                logMessage("Failed to download repository from any branch")
            }
            
            file
        } catch (e: Exception) {
            logMessage("ERROR in git clone: ${e.message}")
            null
        }
    }
    
    private fun parseGitHubUrl(url: String): Pair<String?, String?> {
        // Handle direct release URLs
        if (isDirectReleaseUrl(url)) {
            val regex = Regex("github\\.com/(.*?)/(.*?)/releases")
            val matchResult = regex.find(url)
            
            if (matchResult != null && matchResult.groupValues.size >= 3) {
                return Pair(matchResult.groupValues[1], matchResult.groupValues[2])
            }
        }
        
        // Standard repo URL handling
        val repoRegex = Regex("github\\.com[/:](.*?)/(.*?)(?:\\.git|/|$)")
        val matchResult = repoRegex.find(url)
        
        return if (matchResult != null && matchResult.groupValues.size >= 3) {
            Pair(matchResult.groupValues[1], matchResult.groupValues[2])
        } else {
            // Try simpler format like "username/repo"
            val parts = url.trim().split("/")
            if (parts.size == 2 && !parts[0].contains(".") && !parts[1].contains(".")) {
                Pair(parts[0], parts[1])
            } else {
                Pair(null, null)
            }
        }
    }
    
    private fun isDirectReleaseUrl(url: String): Boolean {
        // Check if the URL points directly to a release or releases page
        return url.contains("/releases") || url.contains("/releases/tag/")
    }
}