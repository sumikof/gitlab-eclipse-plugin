package com.gitlab.eclipse.utils

import org.eclipse.jface.dialogs.Dialog
import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Link

/**
 * A notification popup with a message and one link per action. A link closes the popup, then runs
 * its action (on the UI thread). [severityImage] is an `SWT.ICON_*` constant; [autoCloseMillis] 0
 * means the popup stays until closed (AbstractNotificationPopup auto-closes only when > 0).
 * Keep logic out of here: the class needs a Display and cannot be unit-tested headless.
 */
class ActionNotificationPopup(
  display: Display,
  private val title: String,
  private val message: String,
  private val severityImage: Int,
  private val links: List<Pair<String, () -> Unit>>,
  autoCloseMillis: Long,
) : AbstractNotificationPopup(display) {
  init {
    isFadingEnabled = false
    delayClose = autoCloseMillis
  }

  private companion object {
    /** Below AbstractNotificationPopup's 400 px maximum shell width, leaving room for its margins. */
    const val MESSAGE_WIDTH_HINT = 350
  }

  override fun getPopupShellTitle(): String = title

  override fun getPopupShellImage(maximumHeight: Int): Image? = JFaceResources.getImage(imageKey(severityImage))

  override fun createContentArea(parent: Composite) {
    // Without a width hint, a wrapping label reports its one-line width; the popup then clamps the
    // shell to its maximum width and the text is clipped instead of wrapped.
    Label(parent, SWT.WRAP).apply {
      text = message
      layoutData = GridData(SWT.FILL, SWT.TOP, true, false).apply { widthHint = MESSAGE_WIDTH_HINT }
    }
    for ((label, action) in links) {
      Link(parent, SWT.WRAP).apply {
        layoutData = GridData(SWT.FILL, SWT.TOP, true, false)
        // '&' would otherwise be read as a mnemonic marker.
        text = "<a>${label.replace("&", "&&")}</a>"
        // Transparent background, as the authentication popup's link.
        @Suppress("MagicNumber")
        background = Color(parent.display, 255, 255, 255, 0)
        addListener(SWT.Selection) { _ ->
          close()
          action()
        }
      }
    }
  }
}

private fun imageKey(severity: Int): String = when (severity) {
  SWT.ICON_ERROR -> Dialog.DLG_IMG_MESSAGE_ERROR
  SWT.ICON_WARNING -> Dialog.DLG_IMG_MESSAGE_WARNING
  else -> Dialog.DLG_IMG_MESSAGE_INFO
}

/** Marshals an [ActionNotificationPopup] to the UI thread; mirrors [NotificationUtils.show]. */
object ActionNotifications {
  /**
   * Opens the popup on the UI thread, best-effort. Total (never throws), from any thread and in any
   * workbench state, for the same reasons as [NotificationUtils.show]. The runnable re-checks
   * disposal, then [gate] (re-check of the caller's own state in the popup's UI turn), and swallows
   * an [SWTException] from the popup itself.
   *
   * Returns a handle that closes the popup (or, before the runnable ran, prevents it from opening).
   * Call its `close()` on the UI thread.
   */
  fun show(
    title: String,
    message: String,
    severity: Int,
    links: List<Pair<String, () -> Unit>>,
    autoCloseMillis: Long,
    onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
    isDisplayDisposed: () -> Boolean = { currentDisplay.isDisposed },
    gate: () -> Boolean = { true },
  ): AutoCloseable {
    val handle = PopupHandle()
    try {
      onUiThread(
        Runnable {
          try {
            if (handle.isClosed || isDisplayDisposed() || !gate()) return@Runnable
            handle.opened(openOnUiThread(title, message, severity, links, autoCloseMillis))
          } catch (ignored: SWTException) {
            /* display disposed mid-turn: no-op */
          }
        },
      )
    } catch (ignored: SWTException) {
      /* asyncExec on disposed display: notification no-op */
    } catch (ignored: IllegalStateException) {
      /* workbench torn down (background-thread display lookup): notification no-op */
    }
    return handle
  }

  /** Opens the popup synchronously on the current (UI) thread and returns what closes it. */
  internal fun openOnUiThread(
    title: String,
    message: String,
    severity: Int,
    links: List<Pair<String, () -> Unit>>,
    autoCloseMillis: Long,
  ): AutoCloseable {
    val popup = ActionNotificationPopup(currentDisplay, title, message, severity, links, autoCloseMillis)
    popup.open()
    return AutoCloseable { popup.close() }
  }

  /** UI-thread confined after creation, except [isClosed], which a caller may set before the runnable ran. */
  private class PopupHandle : AutoCloseable {
    @Volatile
    var isClosed = false
      private set
    private var popup: AutoCloseable? = null

    fun opened(closer: AutoCloseable) {
      popup = closer
    }

    override fun close() {
      isClosed = true
      val opened = popup ?: return
      popup = null
      try {
        opened.close()
      } catch (ignored: SWTException) {
        /* already disposed: nothing to close */
      }
    }
  }
}
