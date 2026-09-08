package com.mica.music.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Debug-only one-shot seam used by the real third-party SAF provider Gate.
 *
 * Production code never enables this service. The device Gate temporarily enables it only while
 * the system DocumentsUI is already showing the isolated MicaSafVendorGate tree. It refuses to
 * click unless that marker is visible in the active DocumentsUI window, then disables itself
 * immediately after a successful ACTION_CLICK.
 */
class LibraryAutoSyncQaPickerClickService : AccessibilityService() {

    private var awaitingConfirmationDialog = false
    private var completed = false

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50L
        }
        tryClickIsolatedPicker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != DOCUMENTS_UI_PACKAGE) return
        tryClickIsolatedPicker()
    }

    override fun onInterrupt() = Unit

    private fun tryClickIsolatedPicker() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DOCUMENTS_UI_PACKAGE) return
        if (completed) return

        if (awaitingConfirmationDialog) {
            val confirm = root.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_VIEW_ID)
                .firstOrNull { node -> node.isEnabled && node.isClickable && node.isVisibleToUser }
                ?: return
            val cancel = root.findAccessibilityNodeInfosByViewId(CANCEL_BUTTON_VIEW_ID)
                .firstOrNull { node -> node.isEnabled && node.isClickable && node.isVisibleToUser }
                ?: return
            val clicked = confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(
                TAG,
                "picker-confirm-dialog buttons=2 cancelVisible=${cancel.isVisibleToUser} clicked=$clicked",
            )
            if (clicked) {
                awaitingConfirmationDialog = false
                completed = true
                disableSelf()
            }
            return
        }

        if (root.findAccessibilityNodeInfosByText(ISOLATED_TREE_MARKER).isEmpty()) return

        val isolatedTitle = root.findAccessibilityNodeInfosByViewId(ITEM_TITLE_VIEW_ID)
            .firstOrNull { node -> node.text?.toString() == ISOLATED_TREE_MARKER }
        val isolatedItem = isolatedTitle?.firstClickableAncestor()
        if (isolatedItem != null && isolatedItem.isEnabled) {
            val clicked = isolatedItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "picker-enter-gate marker=$ISOLATED_TREE_MARKER clicked=$clicked")
            return
        }

        val musicTitle = root.findAccessibilityNodeInfosByViewId(ITEM_TITLE_VIEW_ID)
            .firstOrNull { node -> node.text?.toString() == MUSIC_DIRECTORY_NAME }
        val musicItem = musicTitle?.firstClickableAncestor()
        if (musicItem != null && musicItem.isEnabled) {
            val clicked = musicItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "picker-enter-music marker=$ISOLATED_TREE_MARKER clicked=$clicked")
            return
        }

        val fixtureVisible =
            root.findAccessibilityNodeInfosByText(ALPHA_FILE_NAME).isNotEmpty() &&
                root.findAccessibilityNodeInfosByText(BETA_FILE_NAME).isNotEmpty()
        if (!fixtureVisible) return

        val button = root.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_VIEW_ID)
            .firstOrNull { node -> node.isEnabled && node.isClickable && node.isVisibleToUser }
            ?: return
        awaitingConfirmationDialog = true
        val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) awaitingConfirmationDialog = false
        Log.i(
            TAG,
            "picker-confirm-music marker=$ISOLATED_TREE_MARKER fixtureVisible=$fixtureVisible clicked=$clicked",
        )
    }

    private fun AccessibilityNodeInfo.firstClickableAncestor(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private companion object {
        const val TAG = "MICA_S4_PICKER_A11Y"
        const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
        const val ISOLATED_TREE_MARKER = "MicaSafVendorGate"
        const val MUSIC_DIRECTORY_NAME = "Music"
        const val ALPHA_FILE_NAME = "alpha.wav"
        const val BETA_FILE_NAME = "beta.wav"
        const val ITEM_TITLE_VIEW_ID = "android:id/title"
        const val CONFIRM_BUTTON_VIEW_ID = "android:id/button1"
        const val CANCEL_BUTTON_VIEW_ID = "android:id/button2"
    }
}
