package com.maxrave.domain.data.model.update

/**
 * The latest release of the fork's GitHub repository. [pageUrl] is the release page and [assets] the files
 * attached to it, so the dialog can send the user straight to the file for their platform.
 */
data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    val pageUrl: String? = null,
    val assets: List<UpdateAsset> = emptyList(),
)

data class UpdateAsset(
    val name: String,
    val url: String,
)
