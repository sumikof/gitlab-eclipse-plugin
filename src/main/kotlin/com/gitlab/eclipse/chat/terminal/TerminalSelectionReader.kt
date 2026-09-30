package com.gitlab.eclipse.chat.terminal

import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.viewers.IStructuredSelection

/** What a context-menu selection in the Terminal or the Console view yielded. */
sealed interface TerminalSelection {
  /** Non-blank selected text. */
  data class Text(val value: String) : TerminalSelection

  /** The view could be read, but nothing (or only whitespace) is selected. */
  data object Empty : TerminalSelection

  /**
   * The selection is not one this reader understands, or reading it failed. [reason] names types
   * only — never text or exception messages, which may quote the terminal's content.
   */
  data class Unavailable(val reason: String) : TerminalSelection
}

/**
 * Reads the selected text from the menu selection of the Console or the Terminal view, without a
 * compile-time dependency on either (no new OSGi bundles).
 *
 * - **Console**: `TextConsolePage` registers its context menu with its `TextConsoleViewer`, so the
 *   menu selection is an [ITextSelection].
 * - **Terminal**, both the `org.eclipse.terminal.*` generation (Eclipse 2025-09 and later) and the
 *   `org.eclipse.tm.terminal.*` one before it: the view registers its menu with its tab folder
 *   manager, whose selection is the active `CTabItem` — **not** the text. The item's `getData()` is
 *   the terminal control, whose public `getSelection(): String` returns the selected text (`""` when
 *   none). Both are looked up reflectively; the two generations differ only in package names.
 *
 * A bare `String` element is deliberately not accepted: the Terminal publishes one only on mouse-up
 * through the selection service, so it can be stale by the time a command runs.
 *
 * Must run on the UI thread (`Widget.getData()` checks it; the console document is UI-confined).
 */
object TerminalSelectionReader {

  @Suppress("TooGenericExceptionCaught")
  fun read(menuSelection: Any?): TerminalSelection = try {
    when (menuSelection) {
      is ITextSelection -> textOf(menuSelection.text)
      is IStructuredSelection -> readTerminalTab(menuSelection.firstElement)
      else -> TerminalSelection.Unavailable("selection=${typeName(menuSelection)}")
    }
  } catch (e: Exception) {
    TerminalSelection.Unavailable("failure=${e.javaClass.name}")
  }

  private fun readTerminalTab(element: Any?): TerminalSelection {
    if (element == null || element is String) {
      return TerminalSelection.Unavailable("element=${typeName(element)}")
    }
    val control = invokeNoArg(element, "getData")
      ?: return TerminalSelection.Unavailable("element=${typeName(element)} data=null")
    return when (val selected = invokeNoArg(control, "getSelection")) {
      null -> TerminalSelection.Empty
      is String -> textOf(selected)
      else -> TerminalSelection.Unavailable("control=${typeName(control)} selection=${typeName(selected)}")
    }
  }

  /**
   * Calls the public no-argument method [name] on [target]. A missing method surfaces as
   * `NoSuchMethodException`; an exception thrown by the method itself is unwrapped so that only its
   * type is reported.
   */
  private fun invokeNoArg(target: Any, name: String): Any? {
    val method = target.javaClass.getMethod(name)
    return try {
      method.invoke(target)
    } catch (e: java.lang.reflect.InvocationTargetException) {
      throw e.targetException as? Exception ?: e
    }
  }

  private fun textOf(text: String?): TerminalSelection =
    if (text.isNullOrBlank()) TerminalSelection.Empty else TerminalSelection.Text(text)

  private fun typeName(value: Any?): String = value?.javaClass?.name ?: "null"
}
