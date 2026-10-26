/*
 * OmniTV Data Models
 */
package app.omnitv.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Channel(
    val id: Long = 0,
    val sourceId: Long = 1L,
    val streamId: String = "",
    val name: String,
    val displayName: String = name,
    val groupKey: String = "",
    val qualityRank: Int = 0,
    val qualityLabel: String = "",
    val categoryId: String? = null,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val number: Int? = null,
    val streamUrl: String,
    val alternativeStreamUrls: List<String> = emptyList(),
    val sortIndex: Int = 0,
)
