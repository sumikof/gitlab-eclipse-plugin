package com.gitlab.eclipse.utils

import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Label

object NotificationUtils {
  private const val NOTIFICATION_CLOSE_DELAY_IN_MS = 1000L

  /**
   * Marshals the popup to the UI thread, best-effort. Total (never throws), for any thread and
   * any workbench state: callers include the terminal catch of background launches on the SHARED
   * [kotlinx.coroutines.CoroutineScope] (e.g. `launchCiWrite`) — an escape here would cancel that
   * scope and stop every other coroutine on it (#41). The `currentDisplay` lookup can throw
   * [IllegalStateException] from a background thread once the workbench is torn down, and
   * asyncExec can throw [SWTException] on a disposed display — both swallowed: in that window a
   * lost notification is the correct outcome. The catches are precise, not `catch (Exception)`,
   * so a genuine programming error still surfaces instead of being silently eaten. The runnable
   * re-checks disposal because the display can be disposed between scheduling and execution, and
   * (same shape as `reflectLatest`'s runnable) swallows [SWTException] from the popup itself: a
   * display disposed mid-turn would otherwise surface as an "Unhandled event loop exception".
   *
   * [onUiThread] and [isDisplayDisposed] are seams with production defaults (same pattern as
   * `EditorSelectionContextProvider`): a Display cannot exist in a headless test JVM, so the
   * display-touching lambdas are injectable while every call site keeps calling `show(message)`.
   */
  fun show(
    message: String,
    onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
    isDisplayDisposed: () -> Boolean = { currentDisplay.isDisposed },
  ) {
    try {
      onUiThread(
        Runnable {
          try {
            if (isDisplayDisposed()) return@Runnable
            showOnUiThread(message)
          } catch (ignored: SWTException) {
            /* display disposed mid-turn: no-op */
          }
        },
      )
    } catch (ignored: SWTException) {
      /* asyncExec on disposed display: notification no-op — never let it cancel a shared scope */
    } catch (ignored: IllegalStateException) {
      /* workbench torn down (background-thread display lookup): notification no-op */
    }
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
