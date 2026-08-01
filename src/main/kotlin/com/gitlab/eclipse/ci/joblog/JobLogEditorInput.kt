package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.resources.IStorage
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.IPersistableElement
import org.eclipse.ui.IStorageEditorInput

/**
 * Transient in-memory editor input for a job log, identified by [key] only.
 *
 * `equals`/`hashCode` depend solely on [key] so the same job reuses the same editor
 * while a different instance/account/job opens a different one (AC-8). `exists() == false`
 * and a null persistable keep the input out of EditorHistory and workbench mementos.
 */
class JobLogEditorInput(val key: JobLogKey, val content: JobLogContent) : IStorageEditorInput {
  private val displayName = "job-${key.jobId}.log"

  override fun getStorage(): IStorage = JobLogStorage(content, displayName)

  override fun exists(): Boolean = false

  override fun getName(): String = displayName

  override fun getToolTipText(): String = displayName

  override fun getImageDescriptor(): ImageDescriptor? = null

  override fun getPersistable(): IPersistableElement? = null

  override fun <T> getAdapter(adapter: Class<T>): T? = null

  override fun equals(other: Any?): Boolean = other is JobLogEditorInput && other.key == key

  override fun hashCode(): Int = key.hashCode()
}
