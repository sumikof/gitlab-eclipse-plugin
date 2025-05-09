package com.gitlab.eclipse.preferences.healthcheck

import com.gitlab.eclipse.utils.makeBoldFont
import org.eclipse.jface.preference.FieldEditor
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Group
import org.eclipse.swt.widgets.Label

@Suppress("MagicNumber", "TooManyFunctions")
class HealthCheckFieldEditor(private val parent: Composite) : FieldEditor() {

  private lateinit var group: Group

  private val featureLabels = mutableMapOf<String, Triple<Label, Label, Label>>()
  private val featureIds = listOf("authentication", "code_suggestions", "chat")
  private val featureNames = mapOf(
    "authentication" to "Authentication",
    "code_suggestions" to "Code Suggestions",
    "chat" to "Duo Chat"
  )

  init {
    createControl(parent)
  }

  override fun createControl(parent: Composite) {
    group = Group(parent, SWT.NONE).apply {
      layout = GridLayout(3, false)

      val gd = GridData(GridData.FILL_HORIZONTAL)
      gd.horizontalSpan = 3
      layoutData = gd
    }

    resetStatus()
  }

  fun resetStatus() {
    group.visible = false
    group.children.forEach { it.dispose() }
    featureLabels.clear()
  }

  fun updateStatus(results: Map<String, FeatureStateParams>?) {
    group.visible = true

    if (results == null) {
      createErrorLabel("Could not validate the configuration.")
      group.layout(true, true)
      parent.layout(true, true)
      return
    }

    val authFailed = results["authentication"]?.engagedChecks?.isNotEmpty() == true

    featureIds.forEach { featureId ->
      val state = results[featureId]

      // Skip creating non-auth features if auth failed
      if (featureId != "authentication" && authFailed) {
        return@forEach
      }

      if (state != null) {
        createFeatureRow(featureId, featureNames[featureId] ?: featureId)
        val (iconLabel, _, statusLabel) = featureLabels[featureId] ?: return@forEach

        if (state.engagedChecks.isEmpty()) {
          iconLabel.text = "✅"
          statusLabel.text = "OK"
        } else {
          iconLabel.text = "⚠️"
          statusLabel.text = state.engagedChecks.firstOrNull()?.details ?: "Error"
        }
      }
    }

    group.layout(true, true)
    parent.layout(true, true)
  }

  private fun createFeatureRow(featureId: String, displayName: String) {
    val iconLabel = Label(group, SWT.NONE)

    val nameLabel = Label(group, SWT.NONE).apply {
      text = displayName
      font = makeBoldFont(font)
    }

    val statusLabel = Label(group, SWT.NONE)

    featureLabels[featureId] = Triple(iconLabel, nameLabel, statusLabel)
  }

  private fun createErrorLabel(message: String) {
    Label(group, SWT.NONE).text = "⚠️"
    Label(group, SWT.NONE).text = message
  }

  override fun adjustForNumColumns(numColumns: Int) {
    (group.layoutData as GridData).horizontalSpan = numColumns
  }

  override fun doFillIntoGrid(parent: Composite, numColumns: Int) {
    // This is handled in createControl
  }

  override fun getNumberOfControls() = 1

  override fun doLoad() {
    // No-op since we don't load from preferences
  }

  override fun doLoadDefault() {
    // No-op since we don't load from preferences
  }

  override fun doStore() {
    // No-op since we don't store to preferences
  }
}
