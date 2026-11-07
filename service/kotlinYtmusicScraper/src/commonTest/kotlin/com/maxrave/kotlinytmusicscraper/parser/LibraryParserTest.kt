package com.maxrave.kotlinytmusicscraper.parser

import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private fun row(
    browseId: String,
    name: String,
) = """
    {"musicResponsiveListItemRenderer": {
      "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$name"}]}}}],
      "navigationEndpoint": {"browseEndpoint": {"browseId": "$browseId"}}
    }}
    """

private fun page(section: String) =
    json.decodeFromString<BrowseResponse>(
        """
        {"responseContext": {}, "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {
          "content": {"sectionListRenderer": {"contents": [$section]}}
        }}]}}}
        """,
    )

class LibraryParserTest {
    private val shelf = """{"musicShelfRenderer": {"contents": [${row("UCaaa", "One")}, ${row("MPLAUCbbb", "Two")}, ${row("VLxyz", "Not an artist")}]}}"""

    @Test
    fun readsTheShelfInTheSectionList() {
        val subscriptions = page(shelf).libraryShelf()?.subscriptions()

        assertEquals(listOf("UCaaa", "UCbbb"), subscriptions?.map { it.channelId })
        assertEquals("Two", subscriptions?.last()?.name)
    }

    @Test
    fun readsTheShelfInsideAnItemSection() {
        val subscriptions = page("""{"itemSectionRenderer": {"contents": [$shelf]}}""").libraryShelf()?.subscriptions()

        assertEquals(2, subscriptions?.size)
    }

    @Test
    fun aPageWithoutAShelfHasNone() {
        assertNull(page("""{"gridRenderer": {"items": []}}""").libraryShelf())
    }
}
