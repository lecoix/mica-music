package com.mica.music.data

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.mica.music.media.ConfirmedPlaybackBoundary

internal class PlaybackConnectionSession(
    private val connector: MediaControllerConnector,
    private val listenerFactory: (MediaController, () -> Boolean) -> Player.Listener,
    private val onConnected: (MediaController) -> Unit,
    private val onDisconnected: (MediaController?) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
) {
    var controller: MediaController? = null
        private set

    var isConnected: Boolean = false
        private set

    private var connection: MediaControllerConnection? = null
    private var connectStarted = false
    private var generation = 0L

    fun connectIfNeeded() {
        if (connectStarted) return
        connectStarted = true
        connect()
    }

    fun retry() {
        invalidateConnection()
        connectStarted = true
        connect()
    }

    fun release() {
        invalidateConnection()
        controller = null
        isConnected = false
        connectStarted = false
    }

    private fun connect() {
        val requestGeneration = ++generation
        val nextConnection = connector.connect(
            onConnected = { connected ->
                if (requestGeneration == generation) {
                    controller = connected
                    val isCurrentConnection = {
                        requestGeneration == generation && controller === connected
                    }
                    connected.addListener(listenerFactory(connected, isCurrentConnection))
                    isConnected = true
                    onConnected(connected)
                }
            },
            onDisconnected = {
                if (requestGeneration == generation) {
                    val disconnectedController = controller
                    generation += 1
                    connection = null
                    controller = null
                    isConnected = false
                    connectStarted = false
                    onDisconnected(disconnectedController)
                }
            },
            onFailure = { error ->
                if (requestGeneration == generation) {
                    generation += 1
                    connectStarted = false
                    onFailure(error)
                }
            },
            onPlaybackBoundary = { boundary ->
                if (requestGeneration == generation) onPlaybackBoundary(boundary)
            },
        )
        if (requestGeneration == generation) {
            connection = nextConnection
        } else {
            nextConnection.cancel()
        }
    }

    private fun invalidateConnection() {
        generation += 1
        connection?.cancel()
        connection = null
    }
}
