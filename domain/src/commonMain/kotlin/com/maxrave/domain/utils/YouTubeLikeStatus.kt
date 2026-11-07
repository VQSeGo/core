package com.maxrave.domain.utils

/**
 * The signed-in account's rating of a track on YouTube Music. One `next` call answers all three
 * states, so like and dislike travel together instead of being fetched separately.
 */
enum class YouTubeLikeStatus {
    LIKE,
    DISLIKE,
    INDIFFERENT,
}

/** Why a like did not reach the account. The song's local state has been put back by then. */
enum class LikeFailure {
    NOT_SIGNED_IN,
    REQUEST_FAILED,
}
