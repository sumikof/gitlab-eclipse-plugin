package com.gitlab.eclipse.mergerequests.diff

import org.eclipse.compare.IEncodedStreamContentAccessor
import org.eclipse.compare.ITypedElement
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.swt.graphics.Image
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One side of an MR file diff as Compare sees it (REVIEW-01 design §5.1): bytes already in memory,
 * because Compare reads them several times and partly on the UI thread.
 *
 * Deliberately NOT `IEditableContent`: together with the configuration's non-editable sides this
 * keeps the compare read-only twice over. [getContents] hands out a fresh stream on every call.
 * An unknown charset falls back to [defaultCharset] (the workspace encoding).
 */
class MrCompareElement(
  private val name: String,
  private val content: ClassifiedContent,
  private val defaultCharset: () -> String = ResourcesPlugin::getEncoding,
) : ITypedElement, IEncodedStreamContentAccessor {
  override fun getName(): String = name

  override fun getImage(): Image? = null

  override fun getType(): String = content.type

  override fun getContents(): InputStream = ByteArrayInputStream(content.bytes)

  override fun getCharset(): String = content.charset ?: defaultCharset()
}
