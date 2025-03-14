package com.lazydev.fastswap.github

data class GitHubRelease(
    val id: Long,
    val tag_name: String,
    val name: String?,
    val body: String?,
    val created_at: String,
    val assets: List<GitHubAsset>,
    val html_url: String
)

data class GitHubAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val browser_download_url: String,
    val content_type: String
)

data class GitHubRepo(
    val id: Long,
    val name: String,
    val full_name: String,
    val owner: GitHubOwner,
    val description: String?,
    val html_url: String,
    val clone_url: String,
    val default_branch: String
)

data class GitHubOwner(
    val login: String,
    val avatar_url: String
)