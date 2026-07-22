package com.gitlab.eclipse.chat.context

import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.relativePath
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Supplies the active editor selection to the language server.
 *
 * The language server issues this request from its own thread, so reading the workbench
 * requires hopping to the UI thread. The hop is deliberately asynchronous with a deadline:
 * a blocked UI thread (modal dialog, long-running job, shutdown) would otherwise hang the
 * request forever.
 */
class EditorSelectionContextProvider(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
  private val logger by lazy { logger<EditorSelectionContextProvider>() }

  fun provide(): CompletableFuture<EditorSelectionContext?> {
    val future = CompletableFuture<EditorSelectionContext?>()

    return try {
      onUiThread(Runnable { future.complete(readSelection()) })

      // A task that finishes after the deadline finds the future already completed,
      // so its result is discarded rather than surfacing a stale selection.
      future.completeOnTimeout(null, timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: Throwable) {
      logger.warn("Could not schedule the editor selection lookup.", e)
      CompletableFuture.completedFuture(null)
    }
  }

  private fun readSelection(): EditorSelectionContext? = try {
    val textEditor = platformUtils.getActiveTextEditor()
    val file = textEditor?.editorInput?.getAdapter(IFile::class.java)
    val selectedText = (textEditor?.selectionProvider?.selection as? ITextSelection)?.text

    when {
      file == null || selectedText.isNullOrEmpty() -> null
      else -> EditorSelectionContext(fileName = file.relativePath.toString(), selectedText = selectedText)
    }
  } catch (e: Throwable) {
    // Never propagate to the language server: it treats a null response as "no selection".
    // The selected text itself is never logged.
    logger.warn("Could not read the editor selection.", e)
    null
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MILLIS = 2_000L
  }
}
