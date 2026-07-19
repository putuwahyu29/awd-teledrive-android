package com.awd.teledrive.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val html_url: String,
    val prerelease: Boolean = false
)
