package io.github.offlineglass.hook.adapters.spotify

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Restore the native media output from the same immutable state used by Spotify's UI.
 * HyperOS builds its music island from MediaStyle + token + actual playback state.
 * The NetEase media focus extension supplies share data, not a second notification.
 */
internal object SpotifyMediaIslandHooks {
    private data class Track(val title: String, val artist: String, val album: String,
        val uri: String, val picture: String, val duration: Long, val position: Long,
        val playing: Boolean, val buffering: Boolean, val speed: Float)
    private val handler = Handler(Looper.getMainLooper())
    private var session = WeakReference<MediaSession>(null)
    private var context: Context? = null
    @Volatile private var track: Track? = null
    private var notification: Notification? = null
    private val actionRoles=android.util.LruCache<String,String>(32)
    private var nativeSongKey=""
    private var notificationId = 0
    private var notificationTag: String? = null
    private val updating = ThreadLocal.withInitial { false }
    private var scheduled = false
    private var lastMetadataKey = ""
    private var lastNotificationKey = ""
    @Volatile private var nativeClock: SpotifyPlaybackHooks.Clock? = null

    fun onClock(value: SpotifyPlaybackHooks.Clock) {
        nativeClock = value
        trackFromNotification()?.let { track = it }
        schedule()
    }

    private fun trackFromNotification(): Track? {
        val value = notification ?: return null
        if (value.channelId != "playback_channel") return null
        val clock = nativeClock ?: return null
        if (clock.duration <= 0) return null
        val title = value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (title.isBlank()) return null
        val artist = value.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val previous = track?.takeIf { it.title == title && it.artist == artist }
        val metadata=session.get()?.controller?.metadata?.takeIf {
            it.getString(MediaMetadata.METADATA_KEY_TITLE)==title
        }
        // Prefer the real notification action over clock-rate inference.
        // The native notification's offered action is the authoritative UI state:
        // pause offered => playing; play offered => paused.
        val offeredState = offeredPlaying(value)
        val playing = SpotifyPlaybackHooks.playingState() ?: offeredState ?: (clock.speed > 0f)
        return Track(title, artist, previous?.album ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            previous?.uri ?: metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "local:$title|$artist",
            previous?.picture ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty(), clock.duration, clock.position,
            playing, false, if (playing) clock.speed.takeIf { it > 0f } ?: 1f else 0f)
    }

    private fun offeredPlaying(value: Notification): Boolean? {
        val ctx=context
        return value.actions?.mapNotNull { action ->
            val resource=runCatching {
                val icon=action.getIcon() ?: return@runCatching ""
                if(icon.type!=android.graphics.drawable.Icon.TYPE_RESOURCE || ctx==null)return@runCatching ""
                val key=icon.resPackage+":"+icon.resId
                actionRoles[key] ?: run {
                    val res=if(icon.resPackage==ctx.packageName)ctx.resources else
                        ctx.packageManager.getResourcesForApplication(icon.resPackage)
                    res.getResourceEntryName(icon.resId).lowercase(java.util.Locale.ROOT).split('_')
                        .filter { it=="play" || it=="pause" }.distinct().singleOrNull().orEmpty()
                        .also { actionRoles.put(key,it) }
                }
            }.getOrDefault("")
            when(resource) {
                "pause" -> true
                "play" -> false
                else -> when(action.title?.toString()?.lowercase(java.util.Locale.ROOT)) {
                    "pause", "暂停" -> true
                    "play", "播放", "播放曲目" -> false
                    else -> null
                }
            }
        }?.distinct()?.singleOrNull()
    }

    fun install(loader: ClassLoader) {
        // Native UI progress, tint and notification/session metadata share the
        // validated mini-player binding. No separate private PlayerState consumer.
        XposedBridge.hookAllConstructors(MediaSession::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.getOrNull(1) != "spotify-media-session") return
                context = (param.args.firstOrNull() as? Context)?.applicationContext
                session = WeakReference(param.thisObject as MediaSession)
                lastMetadataKey = ""
                schedule()
            }
        })
        XposedBridge.hookAllMethods(MediaSession::class.java, "setPlaybackState", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (updating.get() == true || param.thisObject !== session.get()) return
                val original = param.args.firstOrNull() as? PlaybackState ?: return
                when(original.state) {
                    PlaybackState.STATE_PAUSED,PlaybackState.STATE_STOPPED -> SpotifyPlaybackHooks.playback(false)
                    PlaybackState.STATE_PLAYING -> SpotifyPlaybackHooks.playback(true)
                }
                val current = track ?: return
                // Retain native actions, extras and callbacks. Never turn an unknown
                // or buffering track into a claimed playing state.
                val nativeState = trackFromNotification()
                val authoritative = nativeState ?: current
                val expected = if (authoritative.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
                if ((nativeState != null && original.state != expected) ||
                    (original.state == PlaybackState.STATE_ERROR && current.playing && !current.buffering)) {
                    param.args[0] = playback(authoritative, original)
                    schedule()
                }
            }
        })
        XposedBridge.hookAllMethods(MediaSession::class.java, "setMetadata", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (updating.get() == true || param.thisObject !== session.get() || track == null) return
                val value = param.args.firstOrNull() as? MediaMetadata
                if (value?.getString(MediaMetadata.METADATA_KEY_TITLE) != track?.title) {
                    lastMetadataKey = ""
                    schedule()
                }
            }
        })
        val notificationHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (updating.get() == true) return
                val index = param.args.indexOfFirst { it is Notification }
                val native = param.args.getOrNull(index) as? Notification ?: return
                if (native.channelId !in arrayOf("spotify_updates_channel", "playback_channel") ||
                    native.extras.getString("android.template") != "android.app.Notification\$MediaStyle") return
                val ctx = when (val owner = param.thisObject) {
                    is Service -> owner.applicationContext
                    else -> context
                } ?: return
                context = ctx
                val id = param.args.getOrNull(index - 1) as? Int ?: return
                val songKey=native.extras.getCharSequence(Notification.EXTRA_TITLE).toString()+"|"+
                    native.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                if(nativeSongKey!=songKey) {
                    nativeSongKey=songKey;nativeClock=null;track=null;SpotifyPlaybackHooks.resetClock()
                }
                notification = native.clone()
                val offered=offeredPlaying(native)
                SpotifyPlaybackHooks.playback(offered)
                notificationId = id
                notificationTag = param.args.firstOrNull() as? String
                trackFromNotification()?.let { track = it; schedule() }
                param.args[index] = runCatching { enrich(native.clone()) }.getOrDefault(native)
            }
        }
        XposedBridge.hookAllMethods(NotificationManager::class.java, "notify", notificationHook)
        XposedBridge.hookAllMethods(Service::class.java, "startForeground", notificationHook)
    }

    @Synchronized private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            synchronized(this) { scheduled = false }
            runCatching { update() }.onFailure { android.util.Log.w("SpotifyGlass", "media island update failed", it) }
        }, 500L)
    }

    private fun playback(value: Track, original: PlaybackState?): PlaybackState {
        val builder = if (original != null) PlaybackState.Builder(original) else PlaybackState.Builder()
        // ERROR exposes only restricted actions. Restore only controls that the
        // actual native song notification offers; keep the native session callback.
        var actions = original?.actions ?: 0L
        notification?.actions?.forEach { action ->
            if (action.actionIntent == null) return@forEach
            val title = action.title?.toString()?.lowercase().orEmpty()
            when {
                title.contains("暂停") || title == "pause" ->
                    actions = actions or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
                title.contains("播放") || title == "play" ->
                    actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
                title.contains("上一首") || title.contains("previous") ->
                    actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                title.contains("下一首") || title.contains("next") ->
                    actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            }
        }
        val clock = nativeClock?.takeIf { it.duration == value.duration && notification?.channelId == "playback_channel" }
        val position = if (clock != null) (clock.position +
            ((SystemClock.elapsedRealtime() - clock.sampledAt) * value.speed).toLong())
            .coerceIn(0L, clock.duration) else value.position
        return builder.setState(if (value.buffering) PlaybackState.STATE_BUFFERING
            else if (value.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            position, if (value.playing && !value.buffering) value.speed else 0f,
            SystemClock.elapsedRealtime()).setActions(actions).setErrorMessage(null).build()
    }

    private fun enrich(value: Notification): Notification {
        val current = track ?: return value
        val media = session.get() ?: return value
        value.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, media.sessionToken)
        value.extras.putCharSequence(Notification.EXTRA_TITLE, current.title)
        value.extras.putCharSequence(Notification.EXTRA_TEXT, current.artist)
        val share = JSONObject().put("title", current.title).put("content", current.artist)
        if (current.picture.isNotBlank()) share.put("pic", current.picture)
        // Local songs do not get a fabricated public share URL.
        if (current.uri.startsWith("spotify:track:"))
            share.put("shareContent", "https://open.spotify.com/track/" + current.uri.substringAfterLast(':'))
        value.extras.putString("miui.focus.param.media", JSONObject().put("param_v2",
            JSONObject().put("param_island", JSONObject().put("shareData", share))).toString())
        return value
    }

    private fun update() {
        val current = track ?: return
        val media = session.get() ?: return
        val ctx = context ?: return
        updating.set(true)
        try {
            val key = "${current.uri}|${current.title}|${current.artist}|${current.duration}"
            if (key != lastMetadataKey) {
                val original = media.controller.metadata
                val sameSong = original?.getString(MediaMetadata.METADATA_KEY_TITLE) == current.title
                val builder = if (original != null && sameSong) MediaMetadata.Builder(original) else MediaMetadata.Builder()
                builder.putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, current.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, current.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, current.duration)
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, current.uri)
                val artwork = notification?.getLargeIcon()?.loadDrawable(ctx)
                    as? android.graphics.drawable.BitmapDrawable
                artwork?.bitmap?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                media.setMetadata(builder.build())
                lastMetadataKey = key
            }
            val original = media.controller.playbackState
            if (original?.state == PlaybackState.STATE_ERROR ||
                (notification?.channelId == "playback_channel" &&
                    original?.state != (if (current.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)))
                media.setPlaybackState(playback(current, original))
            val noticeKey = "$key|${current.playing}|${current.buffering}"
            if (noticeKey != lastNotificationKey) notification?.let {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(notificationTag, notificationId, enrich(it.clone()))
                lastNotificationKey = noticeKey
            }
        } finally { updating.set(false) }
    }
}
