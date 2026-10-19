package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import org.eclipse.jface.viewers.ArrayContentProvider
import org.eclipse.jface.viewers.ITableLabelProvider
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.viewers.StructuredSelection
import org.eclipse.jface.viewers.TableViewer
import org.eclipse.jface.viewers.TableViewerColumn
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Button
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Table

/** The Name / Type / Search in / Summary table of the "Sidebar Queries" page (design §10). UI thread only. */
class CustomQueriesTable(parent: Composite, heightHint: Int) {
  private val viewer = TableViewer(parent, SWT.SINGLE or SWT.BORDER or SWT.FULL_SELECTION or SWT.V_SCROLL)
  private val labels = RowLabelProvider()

  val control: Table get() = viewer.table

  init {
    viewer.table.headerVisible = true
    viewer.table.linesVisible = true
    viewer.table.layoutData = GridData(SWT.FILL, SWT.FILL, true, true).apply { this.heightHint = heightHint }
    COLUMNS.forEach { (title, width) ->
      TableViewerColumn(viewer, SWT.NONE).column.apply {
        text = title
        this.width = width
      }
    }
    viewer.contentProvider = ArrayContentProvider.getInstance()
    viewer.labelProvider = labels
  }

  /** Index of the selected row, or -1. */
  val selectedIndex: Int get() = viewer.table.selectionIndex

  /** Shows [rows] and selects [select] (ignored when out of range). */
  fun show(rows: List<CustomQuery>, select: Int) {
    labels.allNames = rows.map { it.name }
    viewer.input = rows
    rows.getOrNull(select.coerceAtMost(rows.lastIndex))?.let { viewer.setSelection(StructuredSelection(it), true) }
  }

  fun onSelectionChanged(action: () -> Unit) = viewer.addSelectionChangedListener { action() }

  fun onOpen(action: () -> Unit) = viewer.addDoubleClickListener { action() }

  private class RowLabelProvider : LabelProvider(), ITableLabelProvider {
    var allNames: List<String?> = emptyList()

    override fun getColumnImage(element: Any?, columnIndex: Int): Image? = null

    override fun getColumnText(element: Any?, columnIndex: Int): String {
      val row = element as? CustomQuery ?: return ""
      return when (columnIndex) {
        0 -> CustomQueryRowLabels.name(row, allNames)
        1 -> CustomQueryRowLabels.type(row)
        2 -> CustomQueryRowLabels.target(row)
        else -> CustomQueryRowLabels.summary(row)
      }
    }
  }

  private companion object {
    val COLUMNS = listOf("Name" to 220, "Type" to 120, "Search in" to 120, "Summary" to 240)
  }
}

/** The button column beside [CustomQueriesTable], with selection-dependent enablement (design §10). */
class CustomQueriesButtons(parent: Composite) {
  private val column = Composite(parent, SWT.NONE).apply {
    layout = GridLayout(1, false).apply { marginHeight = 0 }
    layoutData = GridData(SWT.FILL, SWT.TOP, false, false)
  }
  private val buttons = mutableMapOf<String, Button>()

  fun add(label: String, action: () -> Unit) {
    buttons[label] = Button(column, SWT.PUSH).apply {
      text = label
      layoutData = GridData(SWT.FILL, SWT.CENTER, true, false)
      addListener(SWT.Selection) { action() }
    }
  }

  /** Read-only: only [JSON] stays enabled. [index] is the selected row (-1 for none) of [rowCount] rows. */
  fun update(editable: Boolean, index: Int, rowCount: Int) {
    val selected = editable && index in 0 until rowCount
    buttons[ADD]?.isEnabled = editable
    buttons[EDIT]?.isEnabled = selected
    buttons[DUPLICATE]?.isEnabled = selected
    buttons[REMOVE]?.isEnabled = selected
    buttons[UP]?.isEnabled = selected && index > 0
    buttons[DOWN]?.isEnabled = selected && index < rowCount - 1
  }

  companion object {
    const val ADD = "Add…"
    const val EDIT = "Edit…"
    const val DUPLICATE = "Duplicate"
    const val REMOVE = "Remove"
    const val UP = "Up"
    const val DOWN = "Down"
    const val JSON = "Edit as JSON…"
  }
}
