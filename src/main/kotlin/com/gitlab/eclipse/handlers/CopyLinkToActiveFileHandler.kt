package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.lazyService
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
import org.eclipse.ui.texteditor.ITextEditor

@Suppress("unused")
class CopyLinkToActiveFileHandler(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val resolver: GitLabProjectUrlResolver = GitLabProjectUrlResolver(),
  private val clipboard: ClipboardWriter = ClipboardWriter(),
) : AbstractHandler() {
  private val logger = logger<CopyLinkToActiveFileHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    val editor: ITextEditor = platformUtils.getActiveTextEditor()
      ?: return notifyNoFile()
    val file = editor.editorInput?.getAdapter(IFile::class.java) ?: return notifyNoFile()
    val location = file.location ?: run {
      NotificationUtils.show(NOT_IN_REPO)
      return null
    }
    val selection = editor.selectionProvider?.selection as? ITextSelection
    val startLine = selection?.startLine?.takeIf { it >= 0 }
    val endLine = selection?.endLine?.takeIf { it >= 0 }
    val ioFile = location.toFile()
    coroutineScope.launch {
      when (val r = resolver.resolveBlobUrl(ioFile, startLine, endLine)) {
        is GitLabProjectUrlResolver.Resolution.Ok -> clipboard.write(r.url)
        is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
      }
    }
    logger.info("copyLinkToActiveFile requested for ${file.name}")
    return null
  }

  private fun notifyNoFile(): Any? {
    NotificationUtils.show("GitLab: No open file.")
    return null
  }

  private companion object {
    const val NOT_IN_REPO = "The current file is not in the project repository."
  }
}
