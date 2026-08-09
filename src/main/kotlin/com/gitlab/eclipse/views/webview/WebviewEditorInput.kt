package com.gitlab.eclipse.views.webview

import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IPersistableElement

/**
 * The transient input of a webview editor tab. Design §7.3.
 *
 * [fallbackTitle] is what the tab is called until a resolution justifies a better name (design
 * §7.3); it is display-only and takes no part in [equals] or [hashCode], which depend on [key]
 * alone.
 */
class WebviewEditorInput(val key: WebviewEditorKey, private val fallbackTitle: String) : IEditorInput {
  /** Design §21 A7, together with [getPersistable]. */
  override fun exists(): Boolean = false

  override fun getImageDescriptor(): ImageDescriptor? = null

  override fun getName(): String = fallbackTitle

  /**
   * Design §7.3: a restored tab would point at a uri the language server of the previous session
   * issued, so this input stays out of the workbench memento. [exists] keeps it out of
   * `EditorHistory`; both are needed.
   */
  override fun getPersistable(): IPersistableElement? = null

  /** The tab name again, because design §17 keeps the path in [key] off the screen. */
  override fun getToolTipText(): String = fallbackTitle

  override fun <T> getAdapter(adapter: Class<T>): T? = null

  override fun equals(other: Any?): Boolean = other is WebviewEditorInput && other.key == key

  override fun hashCode(): Int = key.hashCode()

  companion object {
    /** Design §5.2's measured ids and titles. */
    const val MCP_WEBVIEW_ID = "root/mcp"
    const val FLOW_WEBVIEW_ID = "root/flow"
    private const val MCP_TITLE = "MCP Dashboard"
    private const val FLOW_TITLE = "Flow Builder"

    /** Design §7.3a rule 8 puts the file this names into the query. */
    private const val FLOW_URI_PARAM = "uri"

    fun mcp(): WebviewEditorInput = WebviewEditorInput(WebviewEditorKey(MCP_WEBVIEW_ID, emptyMap()), MCP_TITLE)

    fun flowBuilder(fileUri: String): WebviewEditorInput =
      WebviewEditorInput(WebviewEditorKey(FLOW_WEBVIEW_ID, mapOf(FLOW_URI_PARAM to fileUri)), FLOW_TITLE)
  }
}
