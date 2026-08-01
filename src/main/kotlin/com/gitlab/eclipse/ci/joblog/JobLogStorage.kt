package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.resources.IEncodedStorage
import org.eclipse.core.runtime.IPath
import java.io.InputStream

/**
 * In-memory read-only [IEncodedStorage] over a [JobLogContent].
 *
 * Implements the encoded variant (not plain IStorage) so the editor decodes with UTF-8
 * regardless of the workspace-default charset. [getContents] reads [JobLogContent.text]
 * at call time, so a later text update is reflected on the next read.
 */
class JobLogStorage(private val content: JobLogContent, private val name: String) : IEncodedStorage {
  override fun getContents(): InputStream = content.text.toByteArray(Charsets.UTF_8).inputStream()

  override fun getCharset(): String = "UTF-8"

  override fun isReadOnly(): Boolean = true

  override fun getName(): String = name

  override fun getFullPath(): IPath? = null

  override fun <T> getAdapter(adapter: Class<T>): T? = null
}
