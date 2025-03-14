package com.lazydev.fastswap.github

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lazydev.fastswap.R
import java.text.SimpleDateFormat
import java.util.Locale

class ReleaseAdapter(
    private var releases: List<GitHubRelease> = emptyList(),
    private val onDownloadClick: (GitHubRelease) -> Unit
) : RecyclerView.Adapter<ReleaseAdapter.ReleaseViewHolder>() {

    class ReleaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.releaseNameText)
        val dateText: TextView = view.findViewById(R.id.releaseDateText)
        val descriptionText: TextView = view.findViewById(R.id.releaseDescriptionText)
        val downloadButton: Button = view.findViewById(R.id.downloadButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReleaseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_release, parent, false)
        return ReleaseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReleaseViewHolder, position: Int) {
        val release = releases[position]
        
        // Display tag name if name is null
        holder.nameText.text = release.name ?: release.tag_name
        
        // Format date
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        try {
            val date = inputFormat.parse(release.created_at)
            holder.dateText.text = outputFormat.format(date!!)
        } catch (e: Exception) {
            holder.dateText.text = release.created_at
        }
        
        // Set description or tag name if description is empty
        val description = release.body?.takeIf { it.isNotEmpty() } ?: "Release ${release.tag_name}"
        holder.descriptionText.text = description
        
        // Set up download button
        holder.downloadButton.setOnClickListener {
            onDownloadClick(release)
        }
    }

    override fun getItemCount() = releases.size

    fun updateReleases(newReleases: List<GitHubRelease>) {
        releases = newReleases
        notifyDataSetChanged()
    }
}