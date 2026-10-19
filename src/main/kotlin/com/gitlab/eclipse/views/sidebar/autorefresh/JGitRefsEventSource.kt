package com.gitlab.eclipse.views.sidebar.autorefresh

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The production [RefsEventSource] (CI-04 design §2.2): JGit's process-wide refs listener list,
 * which reports ref changes made through any `Repository` instance of this JGit bundle — EGit's
 * included when it shares the bundle; otherwise nothing arrives and the periodic and focus checks
 * cover the change.
 *
 * JGit calls the listener synchronously on whichever thread changed or rescanned the refs. There
 * the listener only matches the event's git dir against [trackedGitDir] by [canonical] path (a
 * path that cannot be canonicalised counts as a match: the refresher's HEAD comparison decides)
 * and, on a match, hops the TRACKED path string to the UI thread — the refresher compares that
 * string as-is. No Git I/O here, and every failure is contained and logged by class name only, so
 * nothing reaches the thread that made the change.
 */
class JGitRefsEventSource(
  private val trackedGitDir: () -> String?,
  private val hop: (() -> Unit) -> Unit,
  private val log: (String) -> Unit,
  private val canonical: (String) -> String = { File(it).canonicalPath },
) : RefsEventSource {
  override fun register(onRefsChanged: (gitDir: String) -> Unit): Registration {
    val handle = Repository.getGlobalListenerList().addRefsChangedListener { event ->
      try {
        val tracked = trackedGitDir()
        val changed = event.repository?.directory
        if (tracked != null && changed != null && sameDir(changed.path, tracked)) hop { onRefsChanged(tracked) }
      } catch (e: Exception) {
        runCatching { log("Current-branch refs event failed: ${e.javaClass.name}") }
      }
    }
    return Registration { handle.remove() }
  }

  private fun sameDir(changed: String, tracked: String): Boolean =
    try {
      canonical(changed) == canonical(tracked)
    } catch (@Suppress("SwallowedException") e: Exception) {
      true
    }
}
