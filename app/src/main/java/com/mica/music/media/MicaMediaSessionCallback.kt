package com.mica.music.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.externallyrics.ExternalLyricsOverlayControl
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns MediaSession controller policy and media-item resolution.
 *
 * The service remains the playback-stack lifecycle owner; this callback only translates controller
 * requests into already-defined application capabilities and host operations.
 */
internal class MicaMediaSessionCallback(
    private val context: Context,
    private val mainHandler: Handler,
    private val externalLyricsOverlayControl: ExternalLyricsOverlayControl,
    private val sessionScopeProvider: () -> CoroutineScope?,
    private val trustedMediaItemResolverProvider: () -> TrustedMediaItemResolver?,
    private val decorateResolvedSong: (com.mica.music.data.Song, MediaItem) -> MediaItem,
    private val applyAppShuffleRequest: (PlaybackShuffleRequest) -> Unit,
    private val updateMediaButtonPreferences: () -> Unit,
) : MediaSession.Callback {

    private val ownPackageName: String
        get() = context.packageName

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val identity = controllerIdentity(controller)
        val capabilities = ControllerCapabilityPolicy.evaluate(identity, ownPackageName)
        DiagnosticLog.event(
            "MediaSession",
            "controller-connect package=${identity.packageName} uid=${identity.uid} " +
                "trusted=${identity.isTrusted} version=${identity.controllerVersion} " +
                "class=${capabilities.controllerClass} " +
                "hints=${identity.connectionHintKeys.sorted().joinToString(",")}",
        )

        // Media3's default callback preserves its standard trusted/untrusted player command rules.
        // Mica-specific capabilities below remain explicit.
        val defaultResult = super.onConnect(session, controller)
        if (!controller.isTrusted && controller.packageName != ownPackageName) {
            return defaultResult.also {
                grantArtworkUriPermissions(
                    targetPackage = controller.packageName,
                    mediaItems = session.player.timelineMediaItems(),
                )
            }
        }
        val availableSessionCommands = defaultResult.availableSessionCommands
            .buildUpon()
            .add(ExternalLyricsSessionCommands.toggleDesktopLyrics)
            .add(ExternalLyricsSessionCommands.toggleDesktopLock)
            .add(PlaybackShuffleSessionCommand.command)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(defaultResult.availablePlayerCommands)
            .setAvailableSessionCommands(availableSessionCommands)
            .setMediaButtonPreferences(
                ExternalLyricsSessionCommands.mediaButtonPreferences(
                    context = context,
                    overlayAvailable = externalLyricsOverlayControl.canDrawOverlays(),
                ),
            )
            .build()
            .also {
                grantArtworkUriPermissions(
                    targetPackage = controller.packageName,
                    mediaItems = session.player.timelineMediaItems(),
                )
            }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: androidx.media3.session.SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val identity = controllerIdentity(controller)
        PlaybackShuffleSessionCommand.decode(customCommand, args)?.let { request ->
            if (identity.packageName != ownPackageName) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            mainHandler.post { applyAppShuffleRequest(request) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (
            customCommand.customAction == ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LYRICS_ACTION &&
            isMediaNotificationController(session, controller)
        ) {
            mainHandler.post {
                val currentMode = LyricsPreferences.externalLyricsMode(context)
                LyricsPreferences.setExternalLyricsMode(
                    context,
                    ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(currentMode),
                )
                externalLyricsOverlayControl.sync()
                updateMediaButtonPreferences()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (
            customCommand.customAction == ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LOCK_ACTION &&
            isMediaNotificationController(session, controller)
        ) {
            mainHandler.post {
                if (LyricsPreferences.externalLyricsMode(context) == com.mica.music.data.ExternalLyricsMode.DESKTOP) {
                    LyricsPreferences.setDesktopLyricsLocked(
                        context,
                        !LyricsPreferences.desktopLyricsLocked(context),
                    )
                    externalLyricsOverlayControl.refreshSettings()
                    updateMediaButtonPreferences()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (
            !ControllerCapabilityPolicy.allowsIncomingCustomAction(
                identity = identity,
                ownPackageName = ownPackageName,
                action = customCommand.customAction,
            )
        ) {
            DiagnosticLog.event(
                "MediaSession",
                "custom-command-rejected package=${identity.packageName} action=${customCommand.customAction}",
            )
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val identity = controllerIdentity(controller)
        val capabilities = ControllerCapabilityPolicy.evaluate(identity, ownPackageName)
        if (!capabilities.resolveMediaItemsFromCatalog) {
            return Futures.immediateFuture(decorateOwnAppMediaItems(mediaItems))
        }
        return launchSessionFuture {
            val resolved = trustedMediaItemResolverProvider()
                ?.resolve(mediaItems)
                ?.mediaItems.orEmpty()
            grantArtworkUriPermissions(controller.packageName, resolved)
            resolved.toMutableList()
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val identity = controllerIdentity(controller)
        val capabilities = ControllerCapabilityPolicy.evaluate(identity, ownPackageName)
        if (!capabilities.resolveMediaItemsFromCatalog) {
            val decorated = decorateOwnAppMediaItems(mediaItems)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    decorated,
                    startIndex.coerceIn(0, (decorated.size - 1).coerceAtLeast(0)),
                    startPositionMs,
                ),
            )
        }
        if (mediaItems.size == 1 && MediaItemRequestPolicy.isEmptyRequest(mediaItems.single())) {
            val current = mediaSession.player.currentMediaItem
            if (current != null) {
                grantArtworkUriPermissions(controller.packageName, listOf(current))
                DiagnosticLog.event(
                    "MediaSession",
                    "empty-set-request-preserved-current package=${controller.packageName}",
                )
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(current),
                        0,
                        startPositionMs,
                    ),
                )
            }
        }
        return launchSessionFuture {
            val resolution = trustedMediaItemResolverProvider()?.resolve(mediaItems, startIndex)
                ?: TrustedMediaItemsResolution(emptyList(), null)
            grantArtworkUriPermissions(controller.packageName, resolution.mediaItems)
            MediaSession.MediaItemsWithStartPosition(
                resolution.mediaItems.toMutableList(),
                resolution.resolvedStartIndex ?: 0,
                startPositionMs,
            )
        }
    }

    private fun decorateOwnAppMediaItems(mediaItems: MutableList<MediaItem>): MutableList<MediaItem> =
        mediaItems.map { item ->
            SongMediaItemCodec.decode(item)?.let { song -> decorateResolvedSong(song, item) } ?: item
        }.toMutableList()

    private fun grantArtworkUriPermissions(targetPackage: String, mediaItems: List<MediaItem>) {
        val targetPackages = ArtworkUriGrantPolicy.targetPackages(targetPackage)
        if (targetPackages.isEmpty()) return
        mediaItems.forEach { mediaItem ->
            val artworkUri = mediaItem.mediaMetadata.artworkUri ?: return@forEach
            if (!ArtworkUriGrantPolicy.isGrantable(ownPackageName, artworkUri)) return@forEach
            targetPackages.forEach { grantPackage ->
                runCatching {
                    context.grantUriPermission(
                        grantPackage,
                        artworkUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.onFailure { error ->
                    DiagnosticLog.important(
                        "MediaSession",
                        "artwork-grant-failed package=$grantPackage uri=$artworkUri " +
                            "error=${error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun isMediaNotificationController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean {
        val notificationController = session.getMediaNotificationControllerInfo() ?: return false
        return notificationController.packageName == controller.packageName &&
            notificationController.uid == controller.uid
    }

    private fun Player.timelineMediaItems(): List<MediaItem> =
        List(mediaItemCount) { index -> getMediaItemAt(index) }

    private fun controllerIdentity(controller: MediaSession.ControllerInfo): ControllerIdentity =
        ControllerIdentity(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            connectionHintKeys = controller.connectionHints.keySet(),
        )

    private fun <T> launchSessionFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val scope = sessionScopeProvider()
        if (scope == null) {
            future.setException(IllegalStateException("MediaSession scope is not active"))
            return future
        }
        scope.launch {
            runCatching { block() }
                .onSuccess(future::set)
                .onFailure(future::setException)
        }
        return future
    }
}
