package com.gitlab.eclipse.clone

import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor

/**
 * Bridges JGit's [ProgressMonitor] onto Eclipse's [IProgressMonitor].
 *
 * The reason this exists is [isCancelled]. An Eclipse Job holding a monitor does NOT stop a
 * blocking JGit transport on its own — JGit only gives up when the monitor it was handed says it
 * was cancelled, so the adapter has to be passed to `setProgressMonitor` explicitly.
 *
 * [beginTask] maps to [IProgressMonitor.subTask], not to [IProgressMonitor.beginTask]: the
 * Eclipse contract allows `beginTask` once per monitor, while JGit calls its own `beginTask` once
 * per phase (receiving objects, resolving deltas, ...). The single Eclipse task is opened in
 * [start] with UNKNOWN total work, because JGit reports a task count, not a unit count.
 */
class EclipseProgressMonitorAdapter(private val delegate: IProgressMonitor) : ProgressMonitor {

  override fun start(totalTasks: Int) {
    delegate.beginTask("", IProgressMonitor.UNKNOWN)
  }

  override fun beginTask(title: String?, totalWork: Int) {
    delegate.subTask(title.orEmpty())
  }

  override fun update(completed: Int) {
    delegate.worked(completed)
  }

  /** Nothing to close per phase: the single Eclipse task is closed by whoever owns the monitor. */
  override fun endTask() = Unit

  override fun isCancelled(): Boolean = delegate.isCanceled

  /** JGit's own console-timing hint; Eclipse renders its own progress, so it is ignored. */
  override fun showDuration(enabled: Boolean) = Unit
}
