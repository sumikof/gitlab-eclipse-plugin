package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.preferences.openGitLabPreferences
import com.gitlab.eclipse.utils.currentDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.jface.dialogs.Dialog
import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Link

@Suppress("MagicNumber")
class AuthenticationStateService(
  private val scope: CoroutineScope = service<CoroutineScope>(),
  private val notifDelay: Long = 500L
) {
  private var isAuthenticated: Boolean? = null
  private var showAuthNotifJob: Job? = null

  fun update(featureStateChange: FeatureStateChange) {
    showAuthNotifJob?.cancel()

    // This is a debouncing mechanism needed as the language server fires multiple featureStateChange calls successively.
    // Without this, erroneous notifications may appear as initial featureStateChange events may contain stale auth states.
    showAuthNotifJob = scope.launch {
      delay(notifDelay)

      val newAuthState = featureStateChange
        .allChecks
        ?.none { it.checkId == "authentication-required" && it.engaged }

      if (isAuthenticated == newAuthState) return@launch

      isAuthenticated = newAuthState

      if (isAuthenticated == false) {
        currentDisplay.asyncExec {
          showNotification()
        }
      }
    }
  }

  fun resetAuthenticatedState() {
    isAuthenticated = null
  }

  private fun showNotification() {
    object : AbstractNotificationPopup(currentDisplay) {
      override fun getPopupShellTitle(): String = "GitLab Duo requires authentication"

      override fun getPopupShellImage(maximumHeight: Int): Image {
        return JFaceResources.getImage(Dialog.DLG_IMG_MESSAGE_WARNING)
      }

      override fun createContentArea(parent: Composite) {
        Link(parent, SWT.WRAP).apply {
          text = "<a>Authenticate with GitLab</a>"

          // Transparent background
          background = Color(currentDisplay, 255, 255, 255, 0)

          addListener(SWT.Selection) { _ ->
            close()
            openGitLabPreferences()
          }
        }
      }
    }.apply {
      isFadingEnabled = false
      delayClose = 3000L
    }.open()
  }
}
