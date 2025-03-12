package com.gitlab.eclipse.utils

import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Label

object NotificationUtils {
  private const val NOTIFICATION_CLOSE_DELAY_IN_MS = 1000L

  fun show(message: String) {
    currentDisplay.asyncExec {
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
}
