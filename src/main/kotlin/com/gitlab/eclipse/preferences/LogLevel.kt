package com.gitlab.eclipse.preferences

/**
 * Represents the available log levels for the GitLab language server.
 *
 * @property displayName The user-friendly name displayed in the UI
 * @property value The string value stored in preferences
 */
enum class LogLevel(val displayName: String, val value: String) {
  DEBUG("Debug", "debug"),
  INFO("Info", "info"),
  WARNING("Warning", "warning"),
  ERROR("error", "error");

  companion object {
    /**
     * Convert the enum values to a 2D array format required by ComboFieldEditor
     *
     * @return Array of [displayName, value] pairs
     */
    fun toFieldEditorOptions(): Array<Array<String>> {
      return values().map {
        arrayOf(it.displayName, it.value)
      }.toTypedArray()
    }
  }
}
