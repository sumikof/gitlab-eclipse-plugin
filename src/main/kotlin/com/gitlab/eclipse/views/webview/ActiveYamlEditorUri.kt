package com.gitlab.eclipse.views.webview

import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IURIEditorInput
import java.net.URI

/** The sentence `open_flow_builder.ts:7` shows; design §12 sends every violation below to it. */
private const val YAML_REQUIRED_MESSAGE = "Please open a YAML file to use the Flow Builder."

private val YAML_EXTENSIONS = setOf("yml", "yaml")

/**
 * What design §7.3's `root/flow` precondition makes of the active editor: the uri of the YAML file
 * to put in the query, or the reason there is none.
 *
 * Nothing here reaches the workbench, so the precondition is decided headless. Reading the active
 * editor is therefore the caller's job, and design §8.1 requires it to read the page it will open
 * the tab on.
 */
sealed interface ActiveYamlEditorUri {
  /** Design §7.3a rule 8's uri, in ASCII form. */
  data class Resolved(val fileUri: String) : ActiveYamlEditorUri {
    /**
     * Design §17, for the same reason as `WebviewEditorKey.toString`: a data class prints every
     * component, and [fileUri] is the user's file. This one is the more exposed of the two — it is
     * a public member of a public interface, and `OpenFlowBuilderHandler` already interpolates a
     * property of its sibling [Rejected] into a log line, so `"$resolved"` is a plausible next edit.
     */
    override fun toString(): String = "ActiveYamlEditorUri.Resolved"
  }

  /**
   * Design §12's precondition-violation row. [category] is what the Error Log is told and [message]
   * what the user is told; design §17 keeps anything read off the editor out of both.
   */
  enum class Rejected(val category: String, val message: String) : ActiveYamlEditorUri {
    NO_ACTIVE_EDITOR("no active editor", YAML_REQUIRED_MESSAGE),
    NOT_YAML("the active editor is not on a YAML file", YAML_REQUIRED_MESSAGE),
    NO_FILE_URI("the active editor's input names no file", YAML_REQUIRED_MESSAGE),
  }

  companion object {
    fun of(input: IEditorInput?): ActiveYamlEditorUri {
      if (input == null) return Rejected.NO_ACTIVE_EDITOR
      if (!isYaml(input.name)) return Rejected.NOT_YAML
      val uri = fileUriOf(input) ?: return Rejected.NO_FILE_URI
      // Design §7.3a rule 2 percent-encodes this value's bytes when it goes into the query. ASCII
      // form is taken so that what one decode yields is an RFC 3986 URI; `toString()` would leave
      // characters outside ASCII literal, which is an IRI.
      return Resolved(uri.toASCIIString())
    }

    /**
     * The extension, standing in for the reference implementation's `document.languageId !== 'yaml'`
     * (`open_flow_builder.ts:6`). Eclipse's own answer to the same question is the content type,
     * which needs a running platform and would put this decision back inside the workbench.
     */
    private fun isYaml(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in YAML_EXTENSIONS

    /** Design §7.3a rule 8's two sources, in its order. */
    private fun fileUriOf(input: IEditorInput): URI? {
      if (input is IFileEditorInput) {
        val location = input.file.locationURI
        if (location != null) return location
      }
      if (input is IURIEditorInput) return input.uri
      return null
    }
  }
}
