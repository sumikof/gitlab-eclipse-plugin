package com.gitlab.eclipse.utils

import org.eclipse.core.runtime.Platform
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection

class CodeFormatter(val platformUtils: PlatformUtils) {
  companion object {
    private const val DEFAULT_TAB_SIZE = 4
  }

  fun format(snippet: String): String {
    val selection = currentDisplay.syncCall<ITextSelection?, Exception> {
      platformUtils.getActiveSelection()
    } ?: return snippet

    val document = platformUtils.getActiveDocument()
      ?: return snippet

    val startOffset = selection.startOffset()
    val endOffset = selection.endOffset()

    val trimmedSnippet = when {
      document.isLineEmpty(startOffset) -> snippet.trimStart('\n', '\t', ' ').trimEnd('\n', '\t', ' ')
      else -> snippet.trimStart('\n', '\t', ' ')
    }

    if (trimmedSnippet.lines().size == 1) {
      return trimmedSnippet
    }

    val prefix = document.get().take(startOffset)
    val suffix = document.get().drop(endOffset)
    val inlinePart = trimmedSnippet.lines().first() + '\n'
    val blockPart = trimmedSnippet.drop(inlinePart.length)
    val content = StringBuilder().apply {
      append(prefix)
      append(inlinePart)
      append(blockPart)
      append(suffix)
    }

    val formatter = ToolFactory.createCodeFormatter(getFormatterConstants())
    val documentWithCodeSnippet = Document(content.toString())

    val snippetStartOffset = prefix.length + inlinePart.length
    val snippetEndOffset = prefix.length + trimmedSnippet.length

    val formatting = formatter.format(
      CodeFormatter.K_COMPILATION_UNIT,
      documentWithCodeSnippet.get(),
      snippetStartOffset,
      snippetEndOffset - snippetStartOffset,
      0,
      null
    )

    formatting.apply(documentWithCodeSnippet)

    val indentedCodeEndOffset = documentWithCodeSnippet
      .getLineOfOffset(snippetStartOffset)
      .let { startLine -> startLine + blockPart.split("\n").size - 1 }
      .let { endLine -> documentWithCodeSnippet.getLineOffset(endLine) + documentWithCodeSnippet.getLineLength(endLine) }

    return inlinePart + documentWithCodeSnippet.get(
      snippetStartOffset,
      indentedCodeEndOffset - snippetStartOffset
    ).trimEnd('\n', '\t', ' ')
  }

  private fun getFormatterConstants(): Map<String, String> {
    return mapOf(
      DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE to Platform.getPreferencesService().getInt(
        "org.eclipse.ui.editors",
        "tabWidth",
        DEFAULT_TAB_SIZE,
        null
      ).toString(),

      DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR to Platform.getPreferencesService().getBoolean(
        "org.eclipse.ui.editors",
        "spacesForTabs",
        true,
        null
      ).let { spacesForTab -> if (spacesForTab) "space" else "tab" },
    )
  }

  private fun IDocument.isLineEmpty(caretOffset: Int): Boolean {
    val lineInformation = getLineInformationOfOffset(caretOffset)
    val lineContent = get(lineInformation.offset, lineInformation.length)

    return lineContent.trim().isEmpty()
  }

  private fun ITextSelection?.startOffset(): Int {
    return this?.offset ?: 0
  }

  private fun ITextSelection?.endOffset(): Int {
    return this?.let { it.offset + it.length } ?: 0
  }
}
