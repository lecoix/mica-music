package com.afalphy.sylvakru

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class IbassoHardwareVolumeResult(
    val error: String? = null,
    val actual: UsbActualVolume? = null,
    val active: Boolean = false,
    val readbackVerified: Boolean = false,
    val writeOnly: Boolean = false,
    val frozen: Boolean = false,
    val syncPending: Boolean = false,
    val trustedTarget: UsbVolumeTarget? = null,
)

/** iBasso HID hardware-volume runtime extracted from the reference engine. */
internal class UsbIbassoHardwareVolumeController(
    context: Context,
    private val onUnsolicitedVolume: ((UsbVolumeEvent) -> Unit)? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var volumeConnection: UsbDeviceConnection? = null
    private var volumeInterface: UsbInterface? = null
    private var volumeDeviceId: Int? = null
    private val pendingResponses = ConcurrentHashMap<Int, CompletableFuture<ByteArray>>()
    private val readerLock = Any()
    private val readerGeneration = AtomicLong()
    private val readerRunning = AtomicBoolean(false)
    private val readerFailureHandled = AtomicBoolean(false)
    @Volatile private var readerThread: Thread? = null
    @Volatile private var readerConnection: UsbDeviceConnection? = null
    @Volatile private var readerEndpoint: UsbEndpoint? = null
    @Volatile private var readerEventsEnabled = false
    private val readerHealthLock = Any()
    @Volatile private var readerHealth = IbassoReaderHealth()
    @Volatile private var readerHealthDeviceId: Int? = null
    @Volatile private var lastWrittenRaw: Int? = null
    @Volatile private var lastWrittenAtMs = 0L
    private val eventDebouncer = IbassoVolumeEventDebouncer()
    private var lastAppliedTarget: UsbVolumeTarget? = null
    private var lastAppliedDeviceId: Int? = null
    private var verificationFailureCount = 0

    val writeOnly: Boolean get() = readerHealth.writeOnly
    val readbackVerified: Boolean get() = readerHealth.readbackVerified

    fun apply(
        device: UsbDevice,
        target: UsbVolumeTarget,
        activeRaw: Int,
        isDsd: Boolean,
        dsdCompensationDb: Int,
        smoothHandoff: Boolean,
        wasFrozen: Boolean = false,
        hasPendingRequest: () -> Boolean = { false },
        generationMatches: () -> Boolean,
    ): IbassoHardwareVolumeResult {
        val hidInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    it.interfaceSubclass == 0 &&
                    it.interfaceProtocol == 0
            } ?: return IbassoHardwareVolumeResult(error = "iBasso HID interface is unavailable.")
        val inputEndpoint = (0 until hidInterface.endpointCount)
            .map { hidInterface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            ?: return IbassoHardwareVolumeResult(error = "iBasso HID input endpoint is unavailable.")

        val newConnection = volumeDeviceId != device.deviceId || volumeConnection == null
        val previousAppliedTarget = trustedIbassoTargetForDevice(
            lastAppliedTarget,
            lastAppliedDeviceId,
            device.deviceId,
        )
        if (newConnection) {
            val resumeHealth = shouldResumeIbassoReaderHealth(
                readerHealth,
                readerHealthDeviceId,
                device.deviceId,
            )
            closeControl(resetReaderHealth = !resumeHealth, clearTrustedTarget = previousAppliedTarget == null)
            val controlConnection = appContext.getSystemService(UsbManager::class.java).openDevice(device)
                ?: return IbassoHardwareVolumeResult(error = "iBasso control connection is unavailable.")
            if (!controlConnection.claimInterface(hidInterface, true)) {
                controlConnection.close()
                return IbassoHardwareVolumeResult(error = "Failed to claim the iBasso HID interface.")
            }
            volumeConnection = controlConnection
            volumeInterface = hidInterface
            volumeDeviceId = device.deviceId
            readerHealthDeviceId = device.deviceId
            if (!writeOnly) {
                startReader(
                    controlConnection,
                    inputEndpoint,
                    IbassoHidVolumeProtocol.capabilities.unsolicitedEvents,
                    restarted = resumeHealth,
                )
            }
        }
        val controlConnection = volumeConnection
            ?: return IbassoHardwareVolumeResult(error = "iBasso control connection is unavailable.")

        if (wasFrozen) {
            val recoveredRaw = readCurrentBaseRaw(controlConnection)
            when (
                frozenHardwareVolumeRecoveryAction(
                    wasFrozen = true,
                    trustedRaw = previousAppliedTarget?.baseRaw,
                    recoveredRaw = recoveredRaw,
                    isDsd = isDsd,
                )
            ) {
                FrozenHardwareVolumeRecoveryAction.ACCEPT_RECOVERED -> {
                    synchronized(readerHealthLock) {
                        readerHealth = readerHealth.afterVerifiedReadback()
                    }
                    verificationFailureCount = 0
                }
                FrozenHardwareVolumeRecoveryAction.KEEP_FROZEN_PCM,
                FrozenHardwareVolumeRecoveryAction.PAUSE_DSD -> {
                    val actual = previousAppliedTarget?.let {
                        ibassoActualEventGainQ16(it.baseRaw, isDsd = false, dsdCompensationDb = 0)
                    }
                    return IbassoHardwareVolumeResult(
                        error = "iBasso hardware volume synchronization is still frozen.",
                        actual = if (isDsd) null else actual,
                        active = !isDsd && actual != null,
                        readbackVerified = false,
                        writeOnly = writeOnly,
                        frozen = true,
                        trustedTarget = previousAppliedTarget,
                    )
                }
                FrozenHardwareVolumeRecoveryAction.NOT_REQUIRED -> error("Frozen recovery must be required here.")
            }
        }

        val shouldReadInitial = shouldReadInitialHardwareVolume(
            isNewConnection = newConnection,
            readable = IbassoHidVolumeProtocol.capabilities.readable && !writeOnly,
        )
        val readBaseRaw = if (shouldReadInitial) readCurrentBaseRaw(controlConnection) else null
        if (newConnection && shouldReadInitial && previousAppliedTarget != null && readBaseRaw == null && !isDsd) {
            synchronized(readerHealthLock) {
                readerHealth = readerHealth.copy(readbackVerified = false)
            }
            val actual = ibassoActualEventGainQ16(previousAppliedTarget.baseRaw, false, 0)
            return IbassoHardwareVolumeResult(
                error = "iBasso hardware volume readback is pending; kept the trusted PCM target.",
                actual = actual,
                active = true,
                readbackVerified = false,
                writeOnly = writeOnly,
                frozen = true,
                trustedTarget = previousAppliedTarget,
            )
        }
        val rollbackTarget = ibassoRollbackTarget(previousAppliedTarget, readBaseRaw, dsdCompensationDb)
            ?: return IbassoHardwareVolumeResult(
                error = "No trusted previous iBasso hardware volume is available; target was not written.",
                trustedTarget = previousAppliedTarget,
            )
        val handoff = hardwareVolumeHandoffTarget(
            smoothHandoff,
            readBaseRaw?.let(IbassoHidVolumeProtocol::rawToLinearGainQ16),
            IbassoHidVolumeProtocol.rawToLinearGainQ16(activeRaw),
        )
        val appliedTarget = if (handoff.source == HardwareVolumeHandoffSource.DEVICE) {
            val baseRaw = checkNotNull(readBaseRaw)
            UsbVolumeTarget(baseRaw, ibassoDsdVolume(baseRaw, dsdCompensationDb))
        } else {
            target
        }
        if (shouldSkipIbassoVolumeWrite(appliedTarget, previousAppliedTarget, readerHealth.readbackVerified)) {
            val actual = ibassoActualEventGainQ16(appliedTarget.baseRaw, isDsd, dsdCompensationDb)
            return IbassoHardwareVolumeResult(
                actual = actual,
                active = true,
                readbackVerified = true,
                trustedTarget = appliedTarget,
            )
        }

        if (!generationMatches()) {
            throw java.util.concurrent.CancellationException("USB volume write cancelled because the session changed.")
        }
        val appliedActiveRaw = ibassoActualEventGainQ16(
            appliedTarget.baseRaw,
            isDsd,
            dsdCompensationDb,
        ).raw
        lastWrittenRaw = appliedActiveRaw
        lastWrittenAtMs = SystemClock.elapsedRealtime()
        synchronized(readerHealthLock) {
            readerHealth = readerHealth.copy(readbackVerified = false)
        }
        val writeError = transferVolumeTarget(controlConnection, appliedTarget)
        if (writeError != null) {
            UsbDiagnostics.w(TAG, "iBasso write ACK timed out; verifying current hardware register: $writeError")
        }

        var verificationAction: IbassoVolumeVerificationAction
        verificationLoop@ do {
            when (awaitReaderForVerification(isDsd, generationMatches)) {
                IbassoReaderRecoveryAction.VERIFY_NOW -> Unit
                IbassoReaderRecoveryAction.WAIT -> error("WAIT must resolve in bounded loop")
                IbassoReaderRecoveryAction.FREEZE_PCM -> {
                    verificationAction = if (hasPendingRequest()) IbassoVolumeVerificationAction.YIELD_TO_PENDING else IbassoVolumeVerificationAction.FREEZE_PCM
                    break@verificationLoop
                }
                IbassoReaderRecoveryAction.CANCEL -> throw java.util.concurrent.CancellationException(
                    "USB volume verification cancelled because the session changed.",
                )
            }
            verificationFailureCount += 1
            val readBack = readCurrentBaseRaw(
                controlConnection,
                failReaderOnTimeout = verificationFailureCount >= 3,
            )
            verificationAction = ibassoVolumeVerificationAction(
                targetRaw = appliedTarget.baseRaw,
                previousRaw = previousAppliedTarget?.baseRaw,
                readbackRaw = readBack,
                failureCount = verificationFailureCount,
                isDsd = isDsd,
                hasPendingRequest = hasPendingRequest(),
            )
            if (verificationAction == IbassoVolumeVerificationAction.RETRY_READBACK) {
                SystemClock.sleep(50)
            }
        } while (verificationAction == IbassoVolumeVerificationAction.RETRY_READBACK)

        return when (verificationAction) {
            IbassoVolumeVerificationAction.ACCEPT_TARGET -> accept(device, appliedTarget, isDsd, dsdCompensationDb)
            IbassoVolumeVerificationAction.KEEP_PREVIOUS -> {
                verificationFailureCount = 0
                val previous = checkNotNull(previousAppliedTarget)
                accept(device, previous, isDsd, dsdCompensationDb).copy(
                    error = "iBasso write was not applied; kept the previous verified hardware volume.",
                )
            }
            IbassoVolumeVerificationAction.RETRY_READBACK -> error("RETRY_READBACK must resolve")
            IbassoVolumeVerificationAction.YIELD_TO_PENDING -> IbassoHardwareVolumeResult(
                active = previousAppliedTarget != null,
                readbackVerified = readerHealth.readbackVerified,
                writeOnly = writeOnly,
                syncPending = true,
                trustedTarget = previousAppliedTarget,
            )
            IbassoVolumeVerificationAction.FREEZE_PCM -> {
                val actual = previousAppliedTarget?.let {
                    ibassoActualEventGainQ16(it.baseRaw, false, 0)
                }
                IbassoHardwareVolumeResult(
                    error = "iBasso hardware volume synchronization is frozen.",
                    actual = actual,
                    active = actual != null,
                    readbackVerified = false,
                    writeOnly = writeOnly,
                    frozen = true,
                    trustedTarget = previousAppliedTarget,
                )
            }
            IbassoVolumeVerificationAction.PAUSE_DSD -> IbassoHardwareVolumeResult(
                error = "DSD playback paused because hardware volume could not be verified.",
                active = false,
                readbackVerified = false,
                writeOnly = writeOnly,
                frozen = true,
                trustedTarget = previousAppliedTarget,
            )
        }
    }


    fun verifyPreservedTarget(deviceId: Int, target: UsbVolumeTarget): Int? {
        val controlConnection = volumeConnection ?: return null
        if (volumeDeviceId != deviceId || lastAppliedTarget != target || lastAppliedDeviceId != deviceId) return null
        return readCurrentBaseRaw(controlConnection, failReaderOnTimeout = false)
    }

    fun acceptPreservedTarget(device: UsbDevice, target: UsbVolumeTarget, readbackRaw: Int): Boolean {
        if (volumeDeviceId != device.deviceId || lastAppliedTarget != target || lastAppliedDeviceId != device.deviceId) return false
        if (readbackRaw != target.baseRaw) return false
        synchronized(readerHealthLock) { readerHealth = readerHealth.afterVerifiedReadback() }
        verificationFailureCount = 0
        return true
    }

    private fun accept(
        device: UsbDevice,
        target: UsbVolumeTarget,
        isDsd: Boolean,
        dsdCompensationDb: Int,
    ): IbassoHardwareVolumeResult {
        val actual = ibassoActualEventGainQ16(target.baseRaw, isDsd, dsdCompensationDb)
        lastAppliedTarget = target
        lastAppliedDeviceId = device.deviceId
        synchronized(readerHealthLock) { readerHealth = readerHealth.afterVerifiedReadback() }
        verificationFailureCount = 0
        return IbassoHardwareVolumeResult(
            actual = actual,
            active = true,
            readbackVerified = true,
            writeOnly = false,
            frozen = false,
            trustedTarget = target,
        )
    }

    private fun awaitReaderForVerification(
        isDsd: Boolean,
        generationMatches: () -> Boolean,
    ): IbassoReaderRecoveryAction {
        val deadlineMs = SystemClock.elapsedRealtime() + IBASSO_READER_RECOVERY_WAIT_MS
        while (true) {
            val health = synchronized(readerHealthLock) { readerHealth }
            val action = ibassoReaderRecoveryAction(
                isDsd = isDsd,
                health = health,
                readerRunning = readerRunning.get(),
                generationMatches = generationMatches(),
                waitExpired = SystemClock.elapsedRealtime() >= deadlineMs,
            )
            if (action != IbassoReaderRecoveryAction.WAIT) return action
            SystemClock.sleep(IBASSO_READER_RESTART_RETRY_DELAY_MS)
        }
    }

    private fun transferVolumeTarget(connection: UsbDeviceConnection, target: UsbVolumeTarget): String? {
        val errors = mutableListOf<String>()
        for (packet in ibassoVolumePackets(target)) {
            val command = packet[0].toInt() and 0xff
            val response = transferPacket(
                connection,
                packet,
                command,
                failReaderOnTimeout = command != 1 && command != 2,
            )
            val responseCommand = response?.getOrNull(6)?.toInt()?.and(0xff)
            val error = when {
                response == null -> "iBasso volume command $command failed."
                responseCommand != command -> "iBasso volume command $command returned response $responseCommand."
                else -> null
            }
            if (error != null) {
                errors += error
                if (command != 1 && command != 2) break
            } else {
                SystemClock.sleep(10)
            }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun readCurrentBaseRaw(
        connection: UsbDeviceConnection,
        failReaderOnTimeout: Boolean = true,
    ): Int? {
        val response = transferPacket(
            connection,
            ibassoVolumeReadPacket(),
            65,
            failReaderOnTimeout = failReaderOnTimeout,
        )
        if (writeOnly) return null
        return response?.getOrNull(8)?.toInt()?.and(0xff)
    }

    private fun startReader(
        controlConnection: UsbDeviceConnection,
        inputEndpoint: UsbEndpoint,
        eventsEnabled: Boolean,
        restarted: Boolean = false,
    ) {
        synchronized(readerLock) {
            if (!readerRunning.compareAndSet(false, true)) return
            val generation = readerGeneration.incrementAndGet()
            readerFailureHandled.set(false)
            readerConnection = controlConnection
            readerEndpoint = inputEndpoint
            readerEventsEnabled = eventsEnabled
            eventDebouncer.clear()
            synchronized(readerHealthLock) {
                readerHealth = if (restarted) readerHealth.afterRestart() else IbassoReaderHealth()
            }
            lateinit var reader: Thread
            reader = Thread({
                val buffer = ByteArray(inputEndpoint.maxPacketSize.coerceAtLeast(16))
                try {
                    while (isCurrentReader(generation, reader, controlConnection, inputEndpoint)) {
                        val length = controlConnection.bulkTransfer(
                            inputEndpoint,
                            buffer,
                            buffer.size,
                            IBASSO_READER_TIMEOUT_MS,
                        )
                        val persistentFailure = synchronized(readerLock) {
                            if (!isCurrentReader(generation, reader, controlConnection, inputEndpoint)) {
                                return@synchronized null
                            }
                            val persistent = synchronized(readerHealthLock) {
                                readerHealth = readerHealth.afterReadResult(length, pendingResponses.isNotEmpty())
                                readerHealth.hasPersistentPendingFailure(IBASSO_PENDING_READ_FAILURE_LIMIT)
                            }
                            if (length > 0) routeReaderPacket(buffer.copyOf(length), controlConnection, eventsEnabled)
                            persistent
                        } ?: break
                        if (length <= 0 && persistentFailure) {
                            handleReaderFailure(
                                IOException("iBasso HID reader did not return a pending response."),
                                controlConnection,
                                inputEndpoint,
                                eventsEnabled,
                                generation,
                                reader,
                            )
                            break
                        } else if (length <= 0) {
                            SystemClock.sleep(20)
                        }
                    }
                } catch (error: Exception) {
                    handleReaderFailure(error, controlConnection, inputEndpoint, eventsEnabled, generation, reader)
                } finally {
                    synchronized(readerLock) {
                        if (generation == readerGeneration.get() && readerThread === reader) {
                            readerRunning.set(false)
                            readerThread = null
                        }
                    }
                }
            }, "ibasso-volume-reader")
            reader.isDaemon = true
            readerThread = reader
            reader.start()
        }
    }

    private fun isCurrentReader(
        generation: Long,
        reader: Thread,
        controlConnection: UsbDeviceConnection,
        inputEndpoint: UsbEndpoint,
    ): Boolean = isCurrentIbassoReaderGeneration(
        readerGeneration = generation,
        currentGeneration = readerGeneration.get(),
        running = readerRunning.get(),
        threadMatches = readerThread === reader,
        connectionMatches = readerConnection === controlConnection,
        endpointMatches = readerEndpoint === inputEndpoint,
    )

    private fun handleReaderFailure(
        error: Exception,
        controlConnection: UsbDeviceConnection,
        inputEndpoint: UsbEndpoint,
        eventsEnabled: Boolean,
        generation: Long,
        reader: Thread,
    ) {
        val shouldMarkWriteOnly = synchronized(readerLock) {
            if (!isCurrentReader(generation, reader, controlConnection, inputEndpoint)) return
            if (!readerFailureHandled.compareAndSet(false, true)) return
            readerRunning.set(false)
            failPendingResponses("iBasso HID reader failed: ${error.message}")
            val health = synchronized(readerHealthLock) {
                readerHealth = readerHealth.afterFailure()
                readerHealth
            }
            !health.restartRequested
        }
        if (shouldMarkWriteOnly) {
            markWriteOnly(error.message)
            return
        }
        UsbDiagnostics.w(TAG, "iBasso HID reader failed; scheduling one restart: ${error.message}")
        scheduleReaderRestart(
            controlConnection,
            inputEndpoint,
            eventsEnabled,
            generation,
            reader,
            error.message,
        )
    }

    private fun scheduleReaderRestart(
        controlConnection: UsbDeviceConnection,
        inputEndpoint: UsbEndpoint,
        eventsEnabled: Boolean,
        generation: Long,
        reader: Thread,
        failureMessage: String?,
        checksRemaining: Int = IBASSO_READER_RESTART_EXIT_CHECKS,
        delayMs: Long = IBASSO_READER_RESTART_INITIAL_DELAY_MS,
    ) {
        mainHandler.postDelayed({
            var retry = false
            var writeOnlyMessage: String? = null
            synchronized(readerLock) {
                val currentThread = readerThread
                val connectionMatches = readerConnection === controlConnection
                val endpointMatches = readerEndpoint === inputEndpoint
                val volumeConnectionMatches = volumeConnection === controlConnection
                val restartRequested = readerHealth.restartRequested
                val failedGenerationCurrent = isFailedIbassoReaderGenerationCurrent(
                    readerGeneration = generation,
                    currentGeneration = readerGeneration.get(),
                    running = readerRunning.get(),
                    failedThreadNotReplaced = currentThread == null || currentThread === reader,
                    connectionMatches = connectionMatches,
                    endpointMatches = endpointMatches,
                    volumeConnectionMatches = volumeConnectionMatches,
                )
                if (!failedGenerationCurrent || !restartRequested) return@synchronized
                if (shouldRestartIbassoReaderGeneration(
                        readerGeneration = generation,
                        currentGeneration = readerGeneration.get(),
                        running = readerRunning.get(),
                        readerThreadExited = currentThread == null,
                        connectionMatches = connectionMatches,
                        endpointMatches = endpointMatches,
                        volumeConnectionMatches = volumeConnectionMatches,
                        restartRequested = restartRequested,
                    )
                ) {
                    startReader(controlConnection, inputEndpoint, eventsEnabled, restarted = true)
                } else if (checksRemaining <= 1) {
                    writeOnlyMessage = "iBasso HID reader thread did not exit after failure: $failureMessage"
                } else {
                    retry = true
                }
            }
            writeOnlyMessage?.let {
                markWriteOnly(it)
                return@postDelayed
            }
            if (retry) {
                scheduleReaderRestart(
                    controlConnection,
                    inputEndpoint,
                    eventsEnabled,
                    generation,
                    reader,
                    failureMessage,
                    checksRemaining - 1,
                    IBASSO_READER_RESTART_RETRY_DELAY_MS,
                )
            }
        }, delayMs)
    }

    private fun markWriteOnly(message: String?) {
        synchronized(readerHealthLock) {
            readerHealth = readerHealth.copy(
                restartRequested = false,
                writeOnly = true,
                readbackVerified = false,
            )
        }
        readerRunning.set(false)
        failPendingResponses("iBasso HID reader unavailable: $message")
        UsbDiagnostics.w(TAG, "iBasso HID reader is unavailable; hardware volume control frozen: $message")
    }

    private fun routeReaderPacket(
        packet: ByteArray,
        readerConnection: UsbDeviceConnection,
        eventsEnabled: Boolean,
    ) {
        val recentWritten = recentIbassoWrittenRaw(
            lastWrittenRaw,
            lastWrittenAtMs,
            SystemClock.elapsedRealtime(),
            IBASSO_WRITE_CONFIRMATION_WINDOW_MS,
        )
        when (val route = routeIbassoVolumePacket(packet, pendingResponses.keys, recentWritten)) {
            is IbassoVolumePacketRoute.CommandResponse -> pendingResponses[route.command]?.complete(route.packet)
            is IbassoVolumePacketRoute.Event -> {
                if (!eventsEnabled) {
                    UsbDiagnostics.w(TAG, "Ignored unsupported iBasso unsolicited HID event.")
                } else if (route.isWriteConfirmation) {
                    UsbDiagnostics.i(TAG, "iBasso hardware volume write confirmation raw=${route.event.leftRaw}.")
                } else {
                    queueVolumeEvent(route.event, readerConnection)
                }
            }
            IbassoVolumePacketRoute.Unknown -> UsbDiagnostics.w(
                TAG,
                "Unknown iBasso HID packet: " + packet.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }
    }

    private fun queueVolumeEvent(event: UsbVolumeEvent, expectedConnection: UsbDeviceConnection) {
        val token = eventDebouncer.submit(event)
        mainHandler.postDelayed({
            val pending = eventDebouncer.consume(token) ?: return@postDelayed
            if (!readerRunning.get() || readerConnection !== expectedConnection) return@postDelayed
            synchronized(readerHealthLock) { readerHealth = readerHealth.afterVerifiedReadback() }
            lastAppliedTarget = ibassoTargetFromEvent(pending.leftRaw.coerceAtMost(pending.rightRaw), 0)
            lastAppliedDeviceId = volumeDeviceId
            onUnsolicitedVolume?.invoke(pending)
        }, IBASSO_EVENT_DEBOUNCE_MS)
    }

    private fun transferPacket(
        connection: UsbDeviceConnection,
        packet: ByteArray,
        expectedCommand: Int,
        allowDirectWhenReaderUnavailable: Boolean = false,
        failReaderOnTimeout: Boolean = true,
    ): ByteArray? {
        val generation = readerGeneration.get()
        val reader = readerThread
        val inputEndpoint = readerEndpoint
        val eventsEnabled = readerEventsEnabled
        val readerAvailable = reader != null && inputEndpoint != null &&
            isCurrentReader(generation, reader, connection, inputEndpoint)
        if (shouldUseDirectIbassoSetReport(writeOnly, readerAvailable, allowDirectWhenReaderUnavailable)) {
            val result = connection.controlTransfer(0x21, 0x09, 0x0200, 0, packet, packet.size, 200)
            return if (result == packet.size) {
                ByteArray(16).also { it[6] = expectedCommand.toByte() }
            } else null
        }
        if (!readerAvailable) return null
        val future = CompletableFuture<ByteArray>()
        val registered = synchronized(readerLock) {
            isCurrentReader(generation, reader, connection, inputEndpoint) &&
                pendingResponses.putIfAbsent(expectedCommand, future) == null
        }
        if (!registered) return null
        return try {
            val result = connection.controlTransfer(0x21, 0x09, 0x0200, 0, packet, packet.size, 200)
            if (result != packet.size) {
                null
            } else {
                val response = runCatching { future.get(300, TimeUnit.MILLISECONDS) }.getOrNull()
                if (response == null && !writeOnly && failReaderOnTimeout) {
                    handleReaderFailure(
                        IOException("iBasso HID command $expectedCommand response timed out."),
                        connection,
                        inputEndpoint,
                        eventsEnabled,
                        generation,
                        reader,
                    )
                }
                val failedGenerationCurrent = synchronized(readerLock) {
                    isFailedIbassoReaderGenerationCurrent(
                        readerGeneration = generation,
                        currentGeneration = readerGeneration.get(),
                        running = readerRunning.get(),
                        failedThreadNotReplaced = readerThread == null || readerThread === reader,
                        connectionMatches = readerConnection === connection,
                        endpointMatches = readerEndpoint === inputEndpoint,
                        volumeConnectionMatches = volumeConnection === connection,
                    )
                }
                response ?: if (writeOnly && failedGenerationCurrent) {
                    ByteArray(16).also { it[6] = expectedCommand.toByte() }
                } else null
            }
        } finally {
            pendingResponses.remove(expectedCommand, future)
        }
    }

    private fun failPendingResponses(message: String) {
        val error = IOException(message)
        pendingResponses.values.forEach { it.completeExceptionally(error) }
        pendingResponses.clear()
    }

    fun closeControl(resetReaderHealth: Boolean = true, clearTrustedTarget: Boolean = resetReaderHealth) {
        val reader = synchronized(readerLock) {
            readerGeneration.incrementAndGet()
            readerFailureHandled.set(true)
            readerRunning.set(false)
            val activeReader = readerThread
            readerThread = null
            readerConnection = null
            readerEndpoint = null
            readerEventsEnabled = false
            failPendingResponses("iBasso HID reader stopped.")
            eventDebouncer.clear()
            if (resetReaderHealth) {
                synchronized(readerHealthLock) { readerHealth = IbassoReaderHealth() }
                readerHealthDeviceId = null
            }
            activeReader
        }
        if (reader != null && reader != Thread.currentThread()) reader.join(250)
        val controlConnection = volumeConnection
        val hidInterface = volumeInterface
        if (controlConnection != null && hidInterface != null) {
            runCatching { controlConnection.releaseInterface(hidInterface) }
        }
        runCatching { controlConnection?.close() }
        volumeConnection = null
        volumeInterface = null
        volumeDeviceId = null
        if (clearTrustedTarget) {
            lastAppliedTarget = null
            lastAppliedDeviceId = null
        }
        lastWrittenRaw = null
        lastWrittenAtMs = 0L
    }

    override fun close() = closeControl()

    companion object {
        private const val IBASSO_READER_TIMEOUT_MS = 100
        private const val IBASSO_PENDING_READ_FAILURE_LIMIT = 3
        private const val IBASSO_READER_RESTART_INITIAL_DELAY_MS = 50L
        private const val IBASSO_READER_RESTART_RETRY_DELAY_MS = 25L
        private const val IBASSO_READER_RESTART_EXIT_CHECKS = 9
        private const val IBASSO_READER_RECOVERY_WAIT_MS =
            IBASSO_READER_RESTART_INITIAL_DELAY_MS +
                IBASSO_READER_RESTART_RETRY_DELAY_MS * (IBASSO_READER_RESTART_EXIT_CHECKS - 1)
        private const val IBASSO_EVENT_DEBOUNCE_MS = 50L
        private const val IBASSO_WRITE_CONFIRMATION_WINDOW_MS = 500L
        private const val TAG = "UsbIbassoHardwareVolume"
    }
}
