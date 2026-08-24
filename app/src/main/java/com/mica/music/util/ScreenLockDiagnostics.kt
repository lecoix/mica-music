package com.mica.music.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Targeted probes for the reported "next track locks the screen" failure.
 *
 * Every line uses one marker so the temporary instrumentation can be located and removed after
 * the root cause is known. The probes are read-only: they observe power, keyguard, display and
 * Activity state without changing any of them.
 */
object ScreenLockDiagnostics {
    private const val MARKER = "[DEBUG-LOCK-7B31]"
    private const val CATEGORY = "ScreenLockTrace"

    private val installed = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val lastUserInteractionElapsedMs = AtomicLong(-1L)
    private val lastUserLeaveHintElapsedMs = AtomicLong(-1L)

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return

        application.registerActivityLifecycleCallbacks(ActivityCallbacks)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                SystemBroadcastReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(SystemBroadcastReceiver, filter)
        }
        event(application, null, "probe-installed")
    }

    fun onWindowFocusChanged(activity: Activity, hasFocus: Boolean) {
        event(activity, activity, "window-focus", "hasFocus=$hasFocus")
    }

    fun onTopResumedActivityChanged(activity: Activity, isTopResumedActivity: Boolean) {
        event(
            activity,
            activity,
            "top-resumed-changed",
            "isTopResumed=$isTopResumedActivity",
        )
    }

    fun onUserInteraction(activity: Activity) {
        lastUserInteractionElapsedMs.set(SystemClock.elapsedRealtime())
        event(activity, activity, "user-interaction")
    }

    fun onUserLeaveHint(activity: Activity) {
        lastUserLeaveHintElapsedMs.set(SystemClock.elapsedRealtime())
        event(activity, activity, "user-leave-hint")
    }

    fun onConfigurationChanged(activity: Activity) {
        event(activity, activity, "configuration-changed")
    }

    fun onPlaybackManualNext(context: Context, target: Int, playbackSnapshot: String) {
        event(
            context,
            null,
            "playback-manual-next",
            "target=$target playback={$playbackSnapshot}",
        )
    }

    fun onDiagnosticsExport(context: Context) {
        event(context, null, "diagnostics-export-request")
    }

    fun onTrimMemory(context: Context, level: Int) {
        event(context, null, "trim-memory", "level=$level")
    }

    fun onLowMemory(context: Context) {
        event(context, null, "low-memory")
    }

    private fun event(
        context: Context,
        activity: Activity?,
        event: String,
        detail: String? = null,
    ) {
        val elapsedMs = SystemClock.elapsedRealtime()
        val snapshot = captureSnapshot(context, activity, elapsedMs)
        DiagnosticLog.event(
            CATEGORY,
            buildString {
                append(MARKER)
                append(" seq=")
                append(sequence.incrementAndGet())
                append(" elapsedMs=")
                append(elapsedMs)
                append(" event=")
                append(event)
                if (!detail.isNullOrBlank()) {
                    append(' ')
                    append(detail)
                }
                append(' ')
                append(snapshot.toLogText())
            },
        )
    }

    private fun captureSnapshot(
        context: Context,
        activity: Activity?,
        elapsedMs: Long,
    ): ScreenLockSnapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val processState = ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }
        val decorView = activity?.window?.decorView
        val windowFlags = activity?.window?.attributes?.flags
        return ScreenLockSnapshot(
            interactive = powerManager?.isInteractive,
            powerSaveMode = powerManager?.isPowerSaveMode,
            deviceIdleMode = powerManager?.isDeviceIdleMode,
            keyguardLocked = keyguardManager?.isKeyguardLocked,
            deviceLocked = keyguardManager?.isDeviceLocked,
            displayState = displayStateLabel(display?.state),
            processImportance = processState.importance,
            lifecycleState = (activity as? LifecycleOwner)?.lifecycle?.currentState?.name,
            hasWindowFocus = decorView?.hasWindowFocus(),
            windowVisibility = decorView?.windowVisibility,
            decorShown = decorView?.isShown,
            finishing = activity?.isFinishing,
            changingConfigurations = activity?.isChangingConfigurations,
            destroyed = activity?.isDestroyed,
            taskId = activity?.taskId,
            windowFlags = windowFlags,
            keepScreenOn = windowFlags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
            showWhenLocked = windowFlags?.and(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0,
            turnScreenOn = windowFlags?.and(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0,
            screenOffTimeoutMs = runCatching {
                Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            }.getOrNull(),
            sinceUserInteractionMs = elapsedDelta(elapsedMs, lastUserInteractionElapsedMs.get()),
            sinceUserLeaveHintMs = elapsedDelta(elapsedMs, lastUserLeaveHintElapsedMs.get()),
        )
    }

    private fun elapsedDelta(nowMs: Long, eventMs: Long): Long? =
        eventMs.takeIf { it >= 0L && nowMs >= it }?.let { nowMs - it }

    private fun displayStateLabel(state: Int?): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        null -> "unknown"
        else -> "state-$state"
    }

    private object SystemBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getStringExtra("reason")
            event(
                context,
                null,
                "system-broadcast",
                buildString {
                    append("action=")
                    append(intent.action.orEmpty())
                    if (!reason.isNullOrBlank()) {
                        append(" reason=")
                        append(reason)
                    }
                },
            )
        }
    }

    private object ActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            event(
                activity,
                activity,
                "activity-created",
                "activity=${activity.javaClass.simpleName} restored=${savedInstanceState != null}",
            )
        }

        override fun onActivityStarted(activity: Activity) {
            event(activity, activity, "activity-started", "activity=${activity.javaClass.simpleName}")
        }

        override fun onActivityResumed(activity: Activity) {
            event(activity, activity, "activity-resumed", "activity=${activity.javaClass.simpleName}")
        }

        override fun onActivityPaused(activity: Activity) {
            event(activity, activity, "activity-paused", "activity=${activity.javaClass.simpleName}")
        }

        override fun onActivityStopped(activity: Activity) {
            event(activity, activity, "activity-stopped", "activity=${activity.javaClass.simpleName}")
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            event(
                activity,
                activity,
                "activity-save-instance-state",
                "activity=${activity.javaClass.simpleName}",
            )
        }

        override fun onActivityDestroyed(activity: Activity) {
            event(activity, activity, "activity-destroyed", "activity=${activity.javaClass.simpleName}")
        }
    }
}

internal data class ScreenLockSnapshot(
    val interactive: Boolean?,
    val powerSaveMode: Boolean?,
    val deviceIdleMode: Boolean?,
    val keyguardLocked: Boolean?,
    val deviceLocked: Boolean?,
    val displayState: String,
    val processImportance: Int,
    val lifecycleState: String?,
    val hasWindowFocus: Boolean?,
    val windowVisibility: Int?,
    val decorShown: Boolean?,
    val finishing: Boolean?,
    val changingConfigurations: Boolean?,
    val destroyed: Boolean?,
    val taskId: Int?,
    val windowFlags: Int?,
    val keepScreenOn: Boolean,
    val showWhenLocked: Boolean,
    val turnScreenOn: Boolean,
    val screenOffTimeoutMs: Long?,
    val sinceUserInteractionMs: Long?,
    val sinceUserLeaveHintMs: Long?,
) {
    fun toLogText(): String = buildString {
        append("hint=")
        append(reasonHint())
        append(" interactive=")
        append(interactive)
        append(" display=")
        append(displayState)
        append(" keyguardLocked=")
        append(keyguardLocked)
        append(" deviceLocked=")
        append(deviceLocked)
        append(" powerSave=")
        append(powerSaveMode)
        append(" deviceIdle=")
        append(deviceIdleMode)
        append(" importance=")
        append(processImportance)
        append(" lifecycle=")
        append(lifecycleState)
        append(" focus=")
        append(hasWindowFocus)
        append(" visibility=")
        append(windowVisibility)
        append(" shown=")
        append(decorShown)
        append(" finishing=")
        append(finishing)
        append(" changingConfig=")
        append(changingConfigurations)
        append(" destroyed=")
        append(destroyed)
        append(" taskId=")
        append(taskId)
        append(" windowFlags=")
        append(windowFlags?.let { "0x${it.toUInt().toString(16)}" })
        append(" keepScreenOn=")
        append(keepScreenOn)
        append(" showWhenLocked=")
        append(showWhenLocked)
        append(" turnScreenOn=")
        append(turnScreenOn)
        append(" screenOffTimeoutMs=")
        append(screenOffTimeoutMs)
        append(" sinceInteractionMs=")
        append(sinceUserInteractionMs)
        append(" sinceLeaveHintMs=")
        append(sinceUserLeaveHintMs)
    }

    internal fun reasonHint(): String = when {
        finishing == true -> "activity-finishing"
        changingConfigurations == true -> "configuration-change"
        interactive == false || displayState == "OFF" -> "screen-not-interactive"
        keyguardLocked == true || deviceLocked == true -> "keyguard-locked"
        else -> "covered-or-backgrounded"
    }
}
