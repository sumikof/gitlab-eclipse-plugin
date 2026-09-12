package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWTError
import org.eclipse.swt.SWTException
import org.eclipse.swt.dnd.Clipboard
import org.eclipse.swt.dnd.TextTransfer
import java.util.concurrent.CompletableFuture

/**
 * The two calls this plugin makes on an SWT [Clipboard], behind a type a headless test can stand in
 * for: a real [Clipboard] needs a [org.eclipse.swt.widgets.Display], which a test JVM cannot make.
 */
internal interface ClipboardTarget {
  /**
   * Puts [text] on the clipboard as plain text. **UI thread only.** May throw [SWTError] — SWT's
   * own signal for a clipboard it cannot claim (`ERROR_CANNOT_SET_CLIPBOARD`) — as well as
   * [SWTException] for a disposed display or a wrong thread.
   */
  fun setText(text: String)

  /** Releases the clipboard handle. Must run whether or not [setText] succeeded. */
  fun dispose()
}

/** The production [ClipboardTarget]: a real [Clipboard] writing through [TextTransfer]. */
private class SwtClipboardTarget(private val clipboard: Clipboard) : ClipboardTarget {
  override fun setText(text: String) {
    clipboard.setContents(arrayOf(text), arrayOf(TextTransfer.getInstance()))
  }

  override fun dispose() = clipboard.dispose()
}

/**
 * Writes text to the SWT clipboard. Marshals to the UI thread.
 *
 * [onUiThread] and [openClipboard] are seams with production defaults (same pattern as
 * `PlatformUtils` and `NotificationUtils.show`): a defaulted lambda body only executes when
 * invoked, so a test that injects fakes never touches a [org.eclipse.swt.widgets.Display].
 *
 * The hop is `asyncExec`, never `syncExec`: callers include the lsp4j dispatch thread, which a
 * `syncExec` would deadlock against a UI thread that is itself waiting on a server response.
 *
 * @property onUiThread the UI-thread hop; defaults to `currentDisplay.asyncExec`
 * @property openClipboard opens a clipboard handle for one write. **UI thread only.**
 */
class ClipboardWriter internal constructor(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val openClipboard: () -> ClipboardTarget = { SwtClipboardTarget(Clipboard(currentDisplay)) },
) {
  // Lazy on purpose: a writer is built as a constructor default of its callers, some of them
  // before the platform log exists (and before a test's log mock is installed).
  private val log by lazy { logger<ClipboardWriter>() }

  /**
   * Writes [text] to the clipboard, fire-and-forget. Returns as soon as the write is queued; a
   * failure inside the UI turn is not reported to the caller. Kept for the existing callers that
   * want exactly that; anything that tells the user "copied" must use [writeChecked].
   */
  fun write(text: String) {
    onUiThread(Runnable { writeNow(text) })
  }

  /**
   * Writes [text] to the clipboard and reports whether it landed.
   *
   * Unlike [write], the returned future completes with the outcome — on the UI thread, once the
   * write has actually run — so a caller can hold back a "copied" notification when the write
   * failed. Never completes exceptionally and never stays pending: a failure of the write, of
   * opening the clipboard, or of the UI-thread hop itself completes the future with `false`, and
   * nothing escapes into the SWT event loop. Failures are logged by exception class name only;
   * the text is chat or snippet content and never reaches the log.
   */
  fun writeChecked(text: String): CompletableFuture<Boolean> {
    val outcome = CompletableFuture<Boolean>()
    try {
      onUiThread(Runnable { outcome.complete(tryWriteNow(text)) })
    } catch (e: RuntimeException) {
      // asyncExec throws SWTException on a disposed display, and the display lookup itself throws
      // IllegalStateException once the workbench is torn down. Neither may leave the future pending.
      outcome.complete(hopFailed(e))
    }
    return outcome
  }

  /**
   * Writes [text] and shows [COPIED_TO_CLIPBOARD] through [notify] **only once the write has
   * landed**. This is the one place the rule lives: a copy that failed shows nothing, so the
   * notice is never a lie. [notify] runs on the UI thread — it is only reached for a write that
   * landed, and a write can only land inside the UI turn — but pass something that marshals for
   * itself anyway, such as `NotificationUtils.show`.
   */
  fun writeAndNotify(text: String, notify: (String) -> Unit): CompletableFuture<Boolean> =
    writeChecked(text).thenApply { landed ->
      if (landed) notify(COPIED_TO_CLIPBOARD)
      landed
    }

  /** One write, clipboard opened and disposed around it. UI thread only. Failures propagate. */
  private fun writeNow(text: String) {
    val clipboard = openClipboard()
    try {
      clipboard.setText(text)
    } finally {
      clipboard.dispose()
    }
  }

  /**
   * [writeNow] with every failure contained. **`SWTError` extends `Error`, not `Exception`**: it is
   * what `Clipboard.setContents` throws when it cannot claim the clipboard, and a catch of
   * `Exception` would let it straight through into the event loop. `SWTException` is a
   * `RuntimeException` and is covered by that catch.
   */
  private fun tryWriteNow(text: String): Boolean =
    try {
      writeNow(text)
      true
    } catch (e: SWTError) {
      writeFailed(e)
    } catch (e: RuntimeException) {
      writeFailed(e)
    }

  private fun writeFailed(e: Throwable): Boolean {
    log.warn("clipboard: the write failed: ${e.javaClass.name}")
    return false
  }

  private fun hopFailed(e: Throwable): Boolean {
    log.warn("clipboard: could not reach the UI thread: ${e.javaClass.name}")
    return false
  }

  companion object {
    /** The notice shown after a copy that landed — by the chat webview and by `$/gitlab/copyText`. */
    const val COPIED_TO_CLIPBOARD = "Copied to clipboard"
  }
}
