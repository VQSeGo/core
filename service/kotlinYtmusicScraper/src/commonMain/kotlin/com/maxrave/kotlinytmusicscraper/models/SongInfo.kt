package com.maxrave.kotlinytmusicscraper.models

data class SongInfo(
    val videoId: String,
    val author: String? = null,
    val authorId: String? = null,
    val authorThumbnail: String? = null,
    val description: String? = null,
    val uploadDate: String? = null,
    val subscribers: String? = null,
    val viewCount: Long? = null,
    val like: Long? = null,
    val dislike: Long? = null,
)