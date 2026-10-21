package com.gitlab.eclipse.lsp.messages

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.swt.SWTException
import org.eclipse.ui.PlatformUI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

private const val MAX_TEXT_LENGTH = 1000
private const val ELLIPSIS = "…"
private const val OK_LABEL = "OK"

/** Text as shown to the user (design §6): at most 1000 characters, plus an ellipsis when cut. */
internal fun displayText(text: String?): String {
  val value = text.orEmpty()
  return if (value.length > MAX_TEXT_LENGTH) value.take(MAX_TEXT_LENGTH) + ELLIPSIS else value
}

/** Closes an open `showMessageRequest` dialog without a choice. Called on the UI thread only. */
fun interface DialogHandle {
  fun dismiss()
}

/**
 * Shows the Language Server's `window/showMessage` and `window/showMessageRequest` in Eclipse
 * (LS-08, design §6).
 *
 * Threading: [show] and [request] are called on the lsp4j reader thread and return at once; every
 * UI step runs in a [uiDispatch] runnable, which re-checks that [session] is still [currentSession],
 * the lifecycle store is open ([isClosed]) and the workbench is up. A request future is **always**
 * completed through [offUi] (design §6 F3, §14.1 R2-F1): lsp4j writes the JSON-RPC response in the
 * completing thread, and a full pipe to a hung server must never block the UI thread or a lifecycle
 * listener (which runs under the provider's lock). The order on session end or `$/cancelRequest`
 * is fixed (F4): the future completes first, then its completion dismisses the dialog on the UI
 * thread, and the dialog's own late result loses to the first completion.
 *
 * [notify] runs on the UI thread and shows a non-modal popup. [ask] runs on the UI thread, opens a
 * modal dialog with the given button labels, hands its [DialogHandle] to `onDialog` before it opens,
 * and returns the index of the pressed button, or a negative code when closed without a choice.
 *
 * Logs carry the message type only — never the text or an action title (AC-G1).
 */
class LanguageServerMessagePresenter(
  private val currentSession: () -> LanguageServerSession?,
  private val isClosed: () -> Boolean,
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val offUi: (Runnable) -> Unit = { CompletableFuture.runAsync(it) },
  private val dedupe: ShowMessageDeduplicator = ShowMessageDeduplicator(),
  private val notify: (MessageType, String) -> Unit = ::presentMessage,
  private val ask: (MessageType, String, List<String>, onDialog: (DialogHandle) -> Unit) -> Int = ::askOnUiThread,
  private val workbenchAvailable: () -> Boolean = ::isWorkbenchAvailable,
) {
  private val logger by lazy { logger<LanguageServerMessagePresenter>() }

  /** Requests not answered yet, with the session each one belongs to. */
  private val pending = ConcurrentHashMap<CompletableFuture<MessageActionItem?>, LanguageServerSession>()

  fun show(session: LanguageServerSession, params: MessageParams) {
    val type = params.type
    logger.debug { "showMessage received (type=${typeName(type)})" }
    if (type == null || type == MessageType.Log) {
      logger.debug { "showMessage dropped (type=${typeName(type)})" }
      return
    }
    val text = displayText(params.message)
    dispatch {
      try {
        if (isCurrent(session) && dedupe.shouldShow(session, type, text)) {
          notify(type, text)
        }
      } catch (ignored: SWTException) {
        /* display disposed mid-turn: no-op */
      } catch (e: RuntimeException) {
        logger.warn("showMessage popup failed (${e.javaClass.simpleName}).")
      }
    }
  }

  fun request(session: LanguageServerSession, params: ShowMessageRequestParams): CompletableFuture<MessageActionItem?> {
    val future = CompletableFuture<MessageActionItem?>()
    pending[future] = session
    future.whenComplete { _, _ -> pending.remove(future) }

    // LSP gives no guidance for a Log-type request; it still has to be answerable, so it is asked as Info.
    val type = params.type?.takeUnless { it == MessageType.Log } ?: MessageType.Info
    logger.debug { "showMessageRequest received (type=${typeName(params.type)})" }
    val text = displayText(params.message)
    val actions = params.actions.orEmpty()
    val labels = actions.map { it.title.orEmpty() }.ifEmpty { listOf(OK_LABEL) }

    val dispatched = dispatch {
      try {
        askOnUi(session, future, type, text, actions, labels)
      } catch (e: RuntimeException) {
        // A failing gate (e.g. the workbench torn down): the request must still be answered.
        logger.warn("showMessageRequest failed (${e.javaClass.simpleName}).")
        answer(future, null)
      }
    }
    if (!dispatched) answer(future, null)
    return future
  }

  /** Answers every pending request of [session] with null (via [offUi]); their dialogs close after. */
  fun sessionEnded(session: LanguageServerSession) {
    pending.forEach { (future, owner) -> if (owner === session) answer(future, null) }
  }

  /** Runs on the UI thread. */
  private fun askOnUi(
    session: LanguageServerSession,
    future: CompletableFuture<MessageActionItem?>,
    type: MessageType,
    text: String,
    actions: List<MessageActionItem>,
    labels: List<String>,
  ) {
    if (future.isDone) return
    if (!isCurrent(session)) {
      answer(future, null)
      return
    }
    // UI-thread confined: whether the dialog is still open, so a completion closes only an open one.
    var open = true
    val code = try {
      ask(type, text, labels) { dialog ->
        future.whenComplete { _, _ -> dispatch { if (open) dialog.dismiss() } }
      }
    } catch (ignored: SWTException) {
      DISMISSED
    } catch (e: RuntimeException) {
      logger.warn("showMessageRequest dialog failed (${e.javaClass.simpleName}).")
      DISMISSED
    } finally {
      open = false
    }
    // First completion wins: after a session end or a cancel, this late answer is a no-op.
    answer(future, if (code >= 0) actions.getOrNull(code) else null)
  }

  private fun answer(future: CompletableFuture<MessageActionItem?>, result: MessageActionItem?) {
    offUi { future.complete(result) }
  }

  private fun isCurrent(session: LanguageServerSession): Boolean =
    !isClosed() && currentSession() === session && workbenchAvailable()

  /** Hands [block] to the UI thread; false when the hop itself failed (display or workbench gone). */
  private fun dispatch(block: () -> Unit): Boolean = try {
    uiDispatch(
      Runnable {
        try {
          block()
        } catch (ignored: SWTException) {
          /* display disposed mid-turn: no-op */
        }
      },
    )
    true
  } catch (ignored: SWTException) {
    false
  } catch (ignored: IllegalStateException) {
    false
  }

  private companion object {
    const val DISMISSED = -1
  }
}

private fun typeName(type: MessageType?): String = type?.name?.uppercase() ?: "NONE"

private fun isWorkbenchAvailable(): Boolean = PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().isClosing
