package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection

/**
 * "Copy Link to Active File on Branch" (NAV-03, VS Code `copyLinkToActiveFileOnBranch`): like
 * [CopyLinkToActiveFileHandler], but the link names the current branch instead of the commit, and
 * "copied" is said only once the write has landed ([ClipboardWriter.writeAndNotify]).
 *
 * [launch] runs the resolution off the UI thread; it defaults to the shared [CoroutineScope].
 */
@Suppress("unused")
class CopyLinkToActiveFileOnBranchHandler(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val resolver: GitLabProjectUrlResolver = GitLabProjectUrlResolver(),
  private val clipboard: ClipboardWriter = ClipboardWriter(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
  private val launch: (suspend () -> Unit) -> Unit = { block -> service<CoroutineScope>().launch { block() } },
) : AbstractHandler() {
  private val logger by lazy { logger<CopyLinkToActiveFileOnBranchHandler>() }

  override fun execute(event: ExecutionEvent): Any? {
    val editor = platformUtils.getActiveTextEditor()
    val selection = editor?.selectionProvider?.selection as? ITextSelection
    run(
      editor?.editorInput?.getAdapter(IFile::class.java),
      selection?.startLine?.takeIf { it >= 0 },
      selection?.endLine?.takeIf { it >= 0 },
    )
    return null
  }

  /** UI thread. [file] is the active editor's file, null without one. */
  internal fun run(file: IFile?, startLine: Int?, endLine: Int?) {
    if (file == null) return notify(NO_OPEN_FILE)
    val ioFile = file.location?.toFile() ?: return notify(NOT_IN_REPO)
    launch {
      when (val r = resolver.resolveBranchBlobUrl(ioFile, startLine, endLine)) {
        is GitLabProjectUrlResolver.Resolution.Ok -> clipboard.writeAndNotify(r.url, notify)
        is GitLabProjectUrlResolver.Resolution.Warn -> notify(r.message)
      }
    }
    logger.info("copyLinkToActiveFileOnBranch requested for ${file.name}")
  }

  private companion object {
    const val NO_OPEN_FILE = "GitLab: No open file."
    const val NOT_IN_REPO = "The current file is not in the project repository."
  }
}
