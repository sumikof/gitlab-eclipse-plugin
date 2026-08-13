package com.gitlab.eclipse.snippets.handlers

import com.gitlab.eclipse.api.SnippetService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.snippets.SelectionRange
import com.gitlab.eclipse.snippets.SnippetPayload
import com.gitlab.eclipse.snippets.SnippetPayloadBuilder
import com.gitlab.eclipse.snippets.SnippetProjectIdResolver
import com.gitlab.eclipse.snippets.SnippetVisibility
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
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File

/**
 * `gl.createSnippet`: uploads the active editor's whole text or selection as a project snippet
 * and opens the result in a browser (design F1).
 *
 * Everything the workbench owns — the editor, its text, the selection, and both dialogs — is read
 * on the UI thread BEFORE any coroutine starts. Only the REST work runs in the background. The
 * same shape as `WorkspaceProjectPicker`.
 */
class CreateSnippetHandler : AbstractHandler() {
  private val logger by lazy { logger<CreateSnippetHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val platformUtils = PlatformUtils()

  /** What the UI thread hands to the background work. Carries no SWT type. */
  private data class EditorSnapshot(
    val fileName: String,
    val fullText: String,
    val file: File,
    val selection: SelectionRange?,
  )

  override fun execute(event: ExecutionEvent) {
    val snapshot = readActiveEditor() ?: run {
      notify("GitLab: No open file.")
      return
    }
    // Cancelling either dialog must leave no trace: no request, no file change (A15).
    val visibility = promptVisibility() ?: return
    val useSelection = promptUseSelection() ?: return

    val payload = SnippetPayloadBuilder.build(
      fileName = snapshot.fileName,
      fullText = snapshot.fullText,
      selection = if (useSelection) snapshot.selection else null,
      visibility = visibility,
    )
    launchUpload(payload, snapshot.file)
  }

  /** UI thread only: the workbench, the document and the selection are all SWT-owned. */
  private fun readActiveEditor(): EditorSnapshot? {
    val editor = platformUtils.getActiveTextEditor() ?: return null
    val document = platformUtils.getDocument(editor) ?: return null
    val file = editor.editorInput?.getAdapter(IFile::class.java)?.location?.toFile() ?: return null
    val selection = platformUtils.getActiveSelection()
      ?.takeIf { it.length > 0 }
      ?.let { SelectionRange(it.startLine, it.endLine) }
    return EditorSnapshot(file.name, document.get(), file, selection)
  }

  private fun promptVisibility(): SnippetVisibility? {
    // Private first: the default must be the one that cannot expose the file (design §22).
    val labels = linkedMapOf(
      "Private — visible only to project members" to SnippetVisibility.PRIVATE,
      "Public — accessible without authentication" to SnippetVisibility.PUBLIC,
    )
    return labels[pick("Select privacy level", labels.keys.toTypedArray())]
  }

  private fun promptUseSelection(): Boolean? {
    val fromFile = "Snippet from file"
    val fromSelection = "Snippet from selection"
    return when (pick("Select snippet source", arrayOf(fromFile, fromSelection))) {
      fromFile -> false
      fromSelection -> true
      else -> null
    }
  }

  private fun pick(message: String, elements: Array<String>): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle("Create Snippet")
    dialog.setMessage(message)
    dialog.setElements(elements)
    return if (dialog.open() == Window.OK) dialog.firstResult as? String else null
  }

  private fun notify(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  private fun launchUpload(payload: SnippetPayload, file: File) {
    coroutineScope.launch {
      try {
        val context = GitLabProjectUrlResolver().resolveContextForFile(file)
        if (context !is GitLabProjectUrlResolver.ContextResolution.Ok) {
          logger.info("Snippet creation skipped: no GitLab project resolved for the active file.")
          return@launch
        }
        val project = context.project
        val projectId = SnippetProjectIdResolver().resolve(project.namespaceWithPath)
        val webUrl = service<SnippetService>().create(projectId, payload) { candidate ->
          candidate == project.instanceUrl
        } ?: return@launch
        // BrowserLauncher already hops to the UI thread itself.
        BrowserLauncher().open(webUrl)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        // Type only — the payload carries file contents and the response carries a url.
        logger.error("Snippet creation failed: ${e.javaClass.name}")
        currentDisplay.asyncExec { notify("GitLab: Could not create the snippet. See the Error Log.") }
      }
    }
  }
}
