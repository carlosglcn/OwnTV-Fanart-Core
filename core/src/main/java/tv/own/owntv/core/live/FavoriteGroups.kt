package tv.own.owntv.core.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.backup.UserDataWriter
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.model.MediaType

/**
 * glcn: groups inside Favourites (Live TV) — "Deportes", "Cine", "Infantil"… A channel is in one group at most, or in
 * none ("All"). A group is a set of membership rows like a custom category's, under the key "favgroup:<name>", so it
 * lists, counts and plays through the same queries ([LiveKey.Custom] with that key), survives a playlist re-sync, and
 * travels with backups and local sync as the hand-ordered data does. A group exists while it has channels: it is born
 * when the first one is moved into it and goes when the last one leaves.
 */
class FavoriteGroups(
    private val dao: CustomCategoryDao,
    private val writer: UserDataWriter,
) {
    /** The profile's groups, A–Z. */
    fun observeNames(profileId: Long): Flow<List<String>> =
        dao.observeContextKeys(profileId, MediaType.LIVE)
            .map { keys -> keys.filter(::isGroup).map(::nameOf).sortedWith(String.CASE_INSENSITIVE_ORDER) }
            .distinctUntilChanged()

    /** The group [channelId] is in, or null. */
    suspend fun groupOf(profileId: Long, channelId: Long): String? =
        dao.contextsOf(profileId, MediaType.LIVE, channelId).firstOrNull(::isGroup)?.let(::nameOf)

    /** Puts [channelId] in the group [name] — out of any other — or in none when [name] is null or blank. */
    suspend fun move(profileId: Long, channelId: Long, name: String?) {
        val target = name?.trim()?.takeIf { it.isNotEmpty() }?.let(::keyOf)
        val current = dao.contextsOf(profileId, MediaType.LIVE, channelId).filter(::isGroup)
        current.filter { it != target }.forEach { writer.removeCustomCategoryMember(profileId, MediaType.LIVE, it, channelId) }
        if (target != null && target !in current) dao.appendItem(profileId, MediaType.LIVE, target, channelId)
    }

    /** Renames a group: its channels move to the new name (joining that group if it already exists). */
    suspend fun rename(profileId: Long, from: String, to: String) {
        val newName = to.trim()
        if (newName.isEmpty() || newName == from) return
        dao.itemIds(profileId, MediaType.LIVE, keyOf(from)).forEach { move(profileId, it, newName) }
    }

    /** Deletes a group; its channels stay in Favourites, in none. */
    suspend fun delete(profileId: Long, name: String) {
        val key = keyOf(name)
        dao.itemIds(profileId, MediaType.LIVE, key).forEach { writer.removeCustomCategoryMember(profileId, MediaType.LIVE, key, it) }
    }

    /** An unfavourited channel leaves its group with it. */
    suspend fun forget(profileId: Long, channelId: Long) = move(profileId, channelId, null)

    companion object {
        const val PREFIX = "favgroup:"
        fun isGroup(key: String): Boolean = key.startsWith(PREFIX)
        fun keyOf(name: String): String = PREFIX + name.trim()
        fun nameOf(key: String): String = key.removePrefix(PREFIX)
    }
}
