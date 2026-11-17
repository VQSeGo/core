package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.Serializable

/**
 * The part of a `browse/edit_playlist` answer that says whether the edit was kept. The call answers
 * 200 either way; a refused edit says so only here.
 */
@Serializable
data class EditPlaylistResponse(
    val status: String? = null,
)
