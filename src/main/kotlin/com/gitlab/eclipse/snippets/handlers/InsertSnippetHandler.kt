package com.gitlab.eclipse.snippets.handlers

import com.gitlab.eclipse.api.SnippetBlob
import com.gitlab.eclipse.api.SnippetQueryService
import com.gitlab.eclipse.api.SnippetSummary
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog

/**
 * `gl.insertSnippet`: inserts the text of a project snippet at the caret (design F2).
 *
 * The editor and the caret offset are read on the UI thread before any coroutine starts; the two
 * GraphQL round trips run in the background; the pickers and the insertion hop back to the UI
 * thread. Same shape as [CreateSnippetHandler].
 */
class InsertSnippetHandler : AbstractHandler() {
  private val logger by lazy { logger<InsertSnippetHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val platformUtils = PlatformUtils()

  override fun execute(event: ExecutionEvent) {
    val editor = platformUtils.getActiveTextEditor()
    val document = editor?.let { platformUtils.getDocument(it) }
    val file = editor?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    if (document == null || file == null) {
      notify("GitLab: No open file.")
      return
    }
    // The caret is read now, on the UI thread. It is deliberately NOT re-read after the round
    // trips: inserting where the user was when they invoked the command is more predictable than
    // following a caret that may have moved while the request was in flight.
    val caretOffset = platformUtils.getActiveSelection()?.offset ?: 0

    coroutineScope.launch {
      try {
        val context = GitLabProjectUrlResolver().resolveContextForFile(file)
        if (context !is GitLabProjectUrlResolver.ContextResolution.Ok) {
          logger.info("Snippet insertion skipped: no GitLab project resolved for the active file.")
          return@launch
        }
        val project = context.project
        val accept: (String) -> Boolean = { candidate -> candidate == project.instanceUrl }
        val page = service<SnippetQueryService>().listSnippets(project.namespaceWithPath, accept)
          ?: return@launch
        if (page.snippets.isEmpty()) {
          uiNotify("GitLab: There are no project snippets.")
          return@launch
        }
        currentDisplay.asyncExec { continueOnUiThread(page.snippets, page.hasNextPage, accept, document, caretOffset) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        // Type only — the response carries snippet text and file paths.
        logger.error("Snippet listing failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not list snippets. See the Error Log.")
      }
    }
  }

  /** Runs on the UI thread: the pickers are SWT, and so is the document edit. */
  private fun continueOnUiThread(
    snippets: List<SnippetSummary>,
    hasNextPage: Boolean,
    accept: (String) -> Boolean,
    document: IDocument,
    caretOffset: Int,
  ) {
    if (hasNextPage) {
      // Never silently truncate (design section 11).
      notify("GitLab: Showing the first page of snippets only; more exist on the server.")
    }
    val snippet = pickSnippet(snippets) ?: return
    val blob = pickBlob(snippet.blobs) ?: return
    fetchAndInsert(snippet, blob, accept, document, caretOffset)
  }

  private fun pickSnippet(snippets: List<SnippetSummary>): SnippetSummary? {
    val labels = snippets.associateBy { summary ->
      val files = summary.blobs.joinToString(", ") { it.name }
      if (files.isEmpty()) summary.title else "${summary.title} — $files"
    }
    return labels[pick("Select a snippet", labels.keys.toTypedArray())]
  }

  private fun pickBlob(blobs: List<SnippetBlob>): SnippetBlob? {
    if (blobs.isEmpty()) {
      notify("GitLab: That snippet has no files.")
      return null
    }
    if (blobs.size == 1) return blobs.first()
    val labels = blobs.associateBy { it.name }
    return labels[pick("Select a file", labels.keys.toTypedArray())]
  }

  private fun fetchAndInsert(
    snippet: SnippetSummary,
    blob: SnippetBlob,
    accept: (String) -> Boolean,
    document: IDocument,
    caretOffset: Int,
  ) {
    coroutineScope.launch {
      try {
        val content = service<SnippetQueryService>().fetchBlobContent(snippet.id, blob.path, accept)
        if (content == null) {
          uiNotify("GitLab: Could not read that snippet file.")
          return@launch
        }
        currentDisplay.asyncExec {
          // Clamp: the document may have shrunk while the request was in flight.
          document.replace(caretOffset.coerceIn(0, document.length), 0, content)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Snippet insertion failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not insert the snippet. See the Error Log.")
      }
    }
  }

  private fun pick(message: String, elements: Array<String>): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle("Insert Snippet")
    dialog.setMessage(message)
    dialog.setElements(elements)
    return if (dialog.open() == Window.OK) dialog.firstResult as? String else null
  }

  private fun notify(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  private fun uiNotify(message: String) = currentDisplay.asyncExec { notify(message) }
}
