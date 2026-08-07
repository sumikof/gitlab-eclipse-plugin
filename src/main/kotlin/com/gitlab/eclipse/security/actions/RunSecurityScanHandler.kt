package com.gitlab.eclipse.security.actions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.security.SecurityScanLauncher
import com.gitlab.eclipse.security.SecurityScanSource
import com.gitlab.eclipse.security.scanUriOf
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

/**
 * `com.gitlab.eclipse.commands.RunSecurityScan` — runs a remote scan on the file the user is in.
 *
 * A thin shell over [SecurityScanLauncher.launch], on purpose: every gate (the feature flag, the
 * token, "is there anything to scan") and every message lives there, so the two triggers cannot
 * drift apart. Notably this does **not** read `scanFileOnSave` — that setting is about the save
 * trigger, and an explicit command must not be silenced by it.
 *
 * A null URI is passed through rather than short-circuited here: the launcher answers `NO_EDITOR`
 * and, because this is a command, tells the user why nothing happened.
 */
@Suppress("unused")
class RunSecurityScanHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    // UI thread. `launch` returns immediately; the send runs on the plugin's own scope.
    val uri = HandlerUtil.getActiveEditor(event)?.editorInput?.let { scanUriOf(it) }
    service<SecurityScanLauncher>().launch(uri, SecurityScanSource.COMMAND)
    return null
  }
}
