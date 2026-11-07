package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How far one track's lyrics are moved, in ms. Kept apart from the cached lyrics themselves: those
 * are replaced whenever they are fetched again and shared with the community database, so a local
 * correction must never be written into them. A track with no row has an offset of 0.
 */
@Entity(tableName = "lyrics_offset")
data class LyricsOffsetEntity(
    @PrimaryKey(autoGenerate = false) val videoId: String,
    val offsetMs: Int,
)
