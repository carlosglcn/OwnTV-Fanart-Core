package tv.own.owntv.core.live

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * glcn: the programme on air now on one channel, with its picture — for the zapping banner and the
 * screensaver. Honours the active profile's manual guide matches and the guide shift, like the Guide.
 */
class NowArt(
    private val guide: GuideReader,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) {
    suspend fun now(channel: ChannelEntity, at: Long = System.currentTimeMillis()): EpgProgrammeEntity? =
        many(listOf(channel), at)[channel.id]

    suspend fun many(channels: List<ChannelEntity>, at: Long = System.currentTimeMillis()): Map<Long, EpgProgrammeEntity> {
        if (channels.isEmpty()) return emptyMap()
        val pid = settings.activeProfileId.first()
        val cust = customize.observe(pid, MediaType.LIVE).first()
        val shift = settings.epgOffsetMinutes.first()
        return guide.slice(channels, cust, shift, at, at + 1).mapNotNull { (id, rows) ->
            rows.firstOrNull { at in it.startMs until it.stopMs }?.let { id to it }
        }.toMap()
    }
}
