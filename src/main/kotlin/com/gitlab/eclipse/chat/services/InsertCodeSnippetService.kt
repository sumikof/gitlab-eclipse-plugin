package com.gitlab.eclipse.chat.services

import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.ITextSelection

class InsertCodeSnippetService(
  private val platformUtils: PlatformUtils,
  private val codeFormatter: CodeFormatter
) {
  private val logger by lazy { logger<InsertCodeSnippetService>() }

  fun insertCodeSnippet(snippet: String) {
    val document = platformUtils.getActiveDocument()
      ?: return logger.info("Cannot insert code snippet: No document found.")

    val selection = currentDisplay.syncCall<ITextSelection?, Exception> {
      platformUtils.getActiveSelection()
    } ?: return logger.info("Cannot insert code snippet: No selection found.")

    val formattedSnippet = codeFormatter.format(snippet)
    currentDisplay.syncExec {
      when {
        selection.text.isNotEmpty() -> document.replace(selection.offset, selection.length, formattedSnippet)
        else -> document.replace(selection.offset, 0, formattedSnippet)
      }
    }
  }
}
