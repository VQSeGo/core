package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

/** Body of `playlist/delete`: the bare playlist id, without the "VL" browse prefix. */
@Serializable
data class DeletePlaylistBody(
    val context: Context,
    val playlistId: String,
)
