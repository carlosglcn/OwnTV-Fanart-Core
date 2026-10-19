package tv.own.owntv.core.settings

import android.view.KeyEvent

enum class RemoteShortcutAction {
    OPEN_HOME,
    OPEN_LIVE_TV,
    OPEN_MOVIES,
    OPEN_SERIES,
    OPEN_DOWNLOADS,
    OPEN_GUIDE,
    OPEN_SEARCH,
    OPEN_SETTINGS,
    OPEN_PROFILE_SWITCHER,
    OPEN_PLAYLIST_SWITCHER,
    CONTINUE_LAST_WATCHED,
    FOCUS_NOW_PLAYING,
    EXPAND_NOW_PLAYING,
    ENTER_MINI_PLAYER,
    ENTER_AUDIO_MODE,
    PLAY_PAUSE,
    PAGE_TOWARD_FIRST,
    PAGE_TOWARD_LAST,
    JUMP_TO_FIRST,
    JUMP_TO_LAST,
    RETURN_TO_LIVE,
    PREVIOUS_CHANNEL,
    OPEN_SUBTITLE_CONTROLS,
    OPEN_AUDIO_CONTROLS,
    OPEN_ASPECT_CONTROLS,
    TOGGLE_PLAYBACK_INFO,
    // glcn: for remotes without CH± (the SHIELD's): a live channel up/down in the player (a page in lists),
    // the channel list or guide over the picture, and one-touch toggles.
    CHANNEL_NEXT,
    CHANNEL_PREVIOUS,
    OPEN_CHANNEL_LIST,
    TOGGLE_FAVORITE,
    RECORD,
    SLEEP_TIMER,
    TOGGLE_ENGINE,
    NEXT_AUDIO_TRACK,
    TOGGLE_SUBTITLES,
}

enum class RemoteShortcutPress { SHORT, LONG }

data class RemoteShortcutBinding(
    val keyCode: Int,
    val press: RemoteShortcutPress,
    val action: RemoteShortcutAction,
) {
    val id: String get() = "$keyCode:${press.name}"
}

object RemoteShortcutBindings {
    private const val SEPARATOR = '|'

    /**
     * glcn: the SHIELD remote's free buttons, for a remote without CH±. ≡: channel list / guide.
     * ⏪ ⏩: previous / next channel on live TV (a page in lists; a seek on films), held: the channel
     * watched before / back to live. ⏯: play-pause, held: the guide.
     */
    val glcnSet: List<RemoteShortcutBinding> = listOf(
        RemoteShortcutBinding(KeyEvent.KEYCODE_MENU, RemoteShortcutPress.SHORT, RemoteShortcutAction.OPEN_CHANNEL_LIST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteShortcutPress.SHORT, RemoteShortcutAction.CHANNEL_PREVIOUS),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteShortcutPress.LONG, RemoteShortcutAction.PREVIOUS_CHANNEL),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteShortcutPress.SHORT, RemoteShortcutAction.CHANNEL_NEXT),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteShortcutPress.LONG, RemoteShortcutAction.RETURN_TO_LIVE),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteShortcutPress.SHORT, RemoteShortcutAction.PLAY_PAUSE),
        // Held ≡ is the SHIELD's own quick settings — the app never receives it — so the guide is a held ⏯.
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteShortcutPress.LONG, RemoteShortcutAction.OPEN_GUIDE),
        // Info (remotes that have it): enlarge the mini player. Lists use Info for the full synopsis first,
        // so it only reaches here where nothing under the cursor took it.
        RemoteShortcutBinding(KeyEvent.KEYCODE_INFO, RemoteShortcutPress.SHORT, RemoteShortcutAction.EXPAND_NOW_PLAYING),
    )

    val defaults: List<RemoteShortcutBinding> = listOf(
        RemoteShortcutBinding(KeyEvent.KEYCODE_CHANNEL_UP, RemoteShortcutPress.SHORT, RemoteShortcutAction.PAGE_TOWARD_FIRST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_CHANNEL_DOWN, RemoteShortcutPress.SHORT, RemoteShortcutAction.PAGE_TOWARD_LAST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteShortcutPress.SHORT, RemoteShortcutAction.PAGE_TOWARD_FIRST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteShortcutPress.SHORT, RemoteShortcutAction.PAGE_TOWARD_LAST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_CHANNEL_UP, RemoteShortcutPress.LONG, RemoteShortcutAction.JUMP_TO_FIRST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_CHANNEL_DOWN, RemoteShortcutPress.LONG, RemoteShortcutAction.JUMP_TO_LAST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteShortcutPress.LONG, RemoteShortcutAction.JUMP_TO_FIRST),
        RemoteShortcutBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteShortcutPress.LONG, RemoteShortcutAction.JUMP_TO_LAST),
        // The remote's own "last channel" key, where it has one. Factory default only: a list the user
        // has edited is theirs, so it is not merged in — they can assign it in Remote shortcuts.
        RemoteShortcutBinding(KeyEvent.KEYCODE_LAST_CHANNEL, RemoteShortcutPress.SHORT, RemoteShortcutAction.PREVIOUS_CHANNEL),
    ).let(::withGlcn)

    /** [list] with the [glcnSet] laid over it (same button and press → the glcn action). */
    fun withGlcn(list: List<RemoteShortcutBinding>): List<RemoteShortcutBinding> {
        val ids = glcnSet.map { it.id }.toSet()
        return list.filterNot { it.id in ids } + glcnSet
    }

    /** Live-TV actions: on anything else the button keeps its normal job (a seek on a film). */
    val liveOnly: Set<RemoteShortcutAction> = setOf(
        RemoteShortcutAction.CHANNEL_NEXT, RemoteShortcutAction.CHANNEL_PREVIOUS, RemoteShortcutAction.PREVIOUS_CHANNEL,
        RemoteShortcutAction.RETURN_TO_LIVE, RemoteShortcutAction.OPEN_CHANNEL_LIST, RemoteShortcutAction.TOGGLE_FAVORITE,
        RemoteShortcutAction.RECORD,
    )

    fun encode(bindings: Collection<RemoteShortcutBinding>): Set<String> =
        bindings.mapTo(linkedSetOf()) { "${it.keyCode}$SEPARATOR${it.press.name}$SEPARATOR${it.action.name}" }

    fun decode(values: Collection<String>): List<RemoteShortcutBinding> = values.mapNotNull { value ->
        val parts = value.split(SEPARATOR)
        if (parts.size != 3) return@mapNotNull null
        val keyCode = parts[0].toIntOrNull() ?: return@mapNotNull null
        val press = runCatching { RemoteShortcutPress.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
        val action = runCatching { RemoteShortcutAction.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
        RemoteShortcutBinding(keyCode, press, action)
    }.distinctBy(RemoteShortcutBinding::id)

    fun replace(
        bindings: Collection<RemoteShortcutBinding>,
        binding: RemoteShortcutBinding,
    ): List<RemoteShortcutBinding> = bindings.filterNot { it.id == binding.id } + binding

    fun isProtectedKey(keyCode: Int): Boolean = keyCode in protectedKeys

    private val protectedKeys = buildSet {
        add(KeyEvent.KEYCODE_UNKNOWN)
        add(KeyEvent.KEYCODE_BACK)
        add(KeyEvent.KEYCODE_HOME)
        add(KeyEvent.KEYCODE_POWER)
        add(KeyEvent.KEYCODE_TV_INPUT)
        add(KeyEvent.KEYCODE_DPAD_UP)
        add(KeyEvent.KEYCODE_DPAD_DOWN)
        add(KeyEvent.KEYCODE_DPAD_LEFT)
        add(KeyEvent.KEYCODE_DPAD_RIGHT)
        add(KeyEvent.KEYCODE_DPAD_CENTER)
        add(KeyEvent.KEYCODE_ENTER)
        add(KeyEvent.KEYCODE_NUMPAD_ENTER)
        add(KeyEvent.KEYCODE_ESCAPE)
        add(KeyEvent.KEYCODE_BUTTON_A)
        add(KeyEvent.KEYCODE_VOLUME_UP)
        add(KeyEvent.KEYCODE_VOLUME_DOWN)
        add(KeyEvent.KEYCODE_VOLUME_MUTE)
        add(KeyEvent.KEYCODE_MUTE)
    }
}
