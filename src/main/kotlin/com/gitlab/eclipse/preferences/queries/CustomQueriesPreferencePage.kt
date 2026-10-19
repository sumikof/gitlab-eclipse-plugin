package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec
import org.eclipse.jface.dialogs.IMessageProvider
import org.eclipse.jface.preference.PreferencePage
import org.eclipse.jface.window.Window
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPreferencePage
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException

/**
 * Preferences > GitLab Duo > Sidebar Queries (design §10). Thin SWT layer over [CustomQueryListModel]; the sidebar
 * picks up a saved value through its own store listener, so this page never calls the sidebar.
 */
class CustomQueriesPreferencePage : PreferencePage(), IWorkbenchPreferencePage {
  private lateinit var store: ScopedPreferenceStore
  private lateinit var model: CustomQueryListModel
  private lateinit var table: CustomQueriesTable
  private lateinit var buttons: CustomQueriesButtons

  override fun init(workbench: IWorkbench) {
    store = service()
    preferenceStore = store
    description = "Queries listed in the GitLab sidebar, in this order."
  }

  override fun createContents(parent: Composite): Control {
    val container = Composite(parent, SWT.NONE)
    container.layout = GridLayout(2, false).apply {
      marginWidth = 0
      marginHeight = 0
    }
    table = CustomQueriesTable(container, convertHeightInCharsToPixels(TABLE_HEIGHT_IN_LINES))
    buttons = CustomQueriesButtons(container).apply {
      add(CustomQueriesButtons.ADD) { openEditor(null) }
      add(CustomQueriesButtons.EDIT) { openEditor(table.selectedIndex) }
      add(CustomQueriesButtons.DUPLICATE) { change(table.selectedIndex + 1) { model.duplicate(table.selectedIndex) } }
      add(CustomQueriesButtons.REMOVE) { change(table.selectedIndex) { model.remove(table.selectedIndex) } }
      add(CustomQueriesButtons.UP) { change(table.selectedIndex - 1) { model.moveUp(table.selectedIndex) } }
      add(CustomQueriesButtons.DOWN) { change(table.selectedIndex + 1) { model.moveDown(table.selectedIndex) } }
      add(CustomQueriesButtons.JSON) { openJson() }
    }
    table.onSelectionChanged { updateEnablement() }
    table.onOpen { if (!model.isReadOnly) openEditor(table.selectedIndex) }
    load()
    return container
  }

  override fun performDefaults() {
    model.restoreDefaults()
    show(0)
    super.performDefaults()
  }

  override fun performOk(): Boolean {
    if (!::model.isInitialized) return true
    val wasDefault = store.isDefault(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)
    val previous = store.getString(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)
    when (val action = model.saveAction()) {
      SaveAction.Nothing -> return true
      SaveAction.SetToDefault -> store.setToDefault(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)
      is SaveAction.Write -> store.setValue(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES, action.value)
    }
    return try {
      store.save()
      setErrorMessage(null)
      load()
      true
    } catch (e: IOException) {
      // setValue already changed the in-memory value: put it back, or the next save / shutdown would persist it.
      if (wasDefault) {
        store.setToDefault(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)
      } else {
        store.setValue(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES, previous)
      }
      // Type only: the value holds query names and filter values (design §11).
      logger<CustomQueriesPreferencePage>()
        .warn("Sidebar queries could not be saved: exceptionType=${e.javaClass.name}")
      setErrorMessage("Could not save the sidebar queries.")
      false
    }
  }

  /** Reads the stored value into a fresh model (initially and after a save, so later saves compare with it). */
  private fun load() {
    val selected = table.selectedIndex
    model = CustomQueryListModel(CustomQueryCodec.decode(store.getString(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)))
    show(selected.coerceAtLeast(0))
  }

  private fun show(select: Int) {
    table.show(model.rows, select)
    if (model.isReadOnly) {
      setMessage(
        "Sidebar queries could not be read. Use Edit as JSON… to fix them or Restore Defaults.",
        IMessageProvider.WARNING,
      )
    } else {
      setMessage(null)
    }
    updateEnablement()
  }

  private fun updateEnablement() {
    table.control.isEnabled = !model.isReadOnly
    buttons.update(!model.isReadOnly, table.selectedIndex, model.rows.size)
  }

  /** Applies a list edit to the selected row, then shows the rows with [select] selected. */
  private fun change(select: Int, edit: () -> Unit) {
    if (table.selectedIndex < 0) return
    edit()
    show(select)
  }

  /** Adds ([index] null) or edits the row at [index]. */
  private fun openEditor(index: Int?) {
    val rows = model.rows
    if (model.isReadOnly || (index != null && index !in rows.indices)) return
    val otherNames = rows.filterIndexed { i, _ -> i != index }.map { it.name }
    val dialog = CustomQueryDialog(shell, index?.let { rows[it] }, otherNames)
    if (dialog.open() != Window.OK) return
    val query = dialog.result ?: return
    if (index == null) model.add(query) else model.replace(index, query)
    show(index ?: model.rows.lastIndex)
  }

  private fun openJson() {
    val text = model.unreadableText ?: CustomQueriesJsonText.format(model.rows)
    val dialog = CustomQueriesJsonDialog(shell, text)
    if (dialog.open() != Window.OK) return
    model.replaceAllFromJson(dialog.result ?: return)
    show(0)
  }

  private companion object {
    const val TABLE_HEIGHT_IN_LINES = 14
  }
}
