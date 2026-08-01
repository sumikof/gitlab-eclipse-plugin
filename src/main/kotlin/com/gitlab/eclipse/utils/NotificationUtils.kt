package com.gitlab.eclipse.utils

import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Label

object NotificationUtils {
  private const val NOTIFICATION_CLOSE_DELAY_IN_MS = 1000L

  fun show(message: String) {
    currentDisplay.asyncExec { showOnUiThread(message) }
  }

  /**
   * Opens the popup synchronously on the CURRENT (UI) thread — no inner asyncExec. Callers that
   * gate on UI-thread-owned state (e.g. "is my generation still latest?") need the decision and
   * the popup in the SAME UI turn; re-marshaling would split them across turns and let a stale
   * popup through (design §14.4 R7).
   */
  fun showOnUiThread(message: String) {
    val popup = object : AbstractNotificationPopup(currentDisplay) {
      override fun getPopupShellTitle() = "GitLab Duo"

      override fun createContentArea(parent: Composite) {
        val label = Label(parent, SWT.WRAP)
        label.text = message
      }
    }

    popup.isFadingEnabled = false
    popup.delayClose = NOTIFICATION_CLOSE_DELAY_IN_MS

    popup.open()
  }
}
