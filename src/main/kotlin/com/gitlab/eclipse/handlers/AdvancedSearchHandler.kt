package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.SearchScopes
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.URLEncoder

@Suppress("unused")
class AdvancedSearchHandler(
  private val picker: WorkspaceProjectPicker = WorkspaceProjectPicker(),
  private val browser: BrowserLauncher = BrowserLauncher(),
  private val preferenceStore: ScopedPreferenceStore = service(),
) : AbstractHandler() {
  private val logger = logger<AdvancedSearchHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val instanceUrl = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL).trimEnd('/')
    val text = promptForText() ?: return null
    val level = pickOne(
      "Search scope",
      "Search this project or the whole GitLab instance?",
      arrayOf("Project", "Instance"),
    ) ?: return null
    val scopes = when {
      level == "Project" -> SearchScopes.ALL_PROJECT
      instanceUrl == SearchScopes.GITLAB_COM_URL -> SearchScopes.GITLAB_COM
      else -> SearchScopes.SELF_MANAGED_INSTANCE
    }
    val scopeLabel = pickOne("Search scope", "Select a scope", scopes.map { it.label }.toTypedArray())
      ?: return null
    val scope = scopes.first { it.label == scopeLabel }.scope
    val query = "?search=${enc(text)}&scope=${enc(scope)}"
    if (level == "Instance") {
      browser.open("$instanceUrl/search$query")
    } else {
      picker.pickWebUrl { r ->
        when (r) {
          is GitLabProjectUrlResolver.Resolution.Ok -> browser.open("${r.url}/-/search$query")
          is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
        }
      }
    }
    logger.info("advancedSearch requested.")
    return null
  }

  private fun promptForText(): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = InputDialog(shell, "GitLab Advanced Search", "Advanced Search.", "", null)
    if (dialog.open() != Window.OK) return null
    return dialog.value?.trim()?.takeIf { it.isNotEmpty() }
  }

  private fun pickOne(title: String, message: String, elements: Array<String>): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle(title)
    dialog.setMessage(message)
    dialog.setElements(elements)
    return if (dialog.open() == Window.OK) dialog.firstResult as? String else null
  }

  private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
