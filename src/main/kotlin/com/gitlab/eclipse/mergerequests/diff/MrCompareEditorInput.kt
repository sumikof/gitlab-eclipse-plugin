package com.gitlab.eclipse.mergerequests.diff

import org.eclipse.compare.CompareConfiguration
import org.eclipse.compare.CompareEditorInput
import org.eclipse.compare.structuremergeviewer.DiffNode
import org.eclipse.compare.structuremergeviewer.Differencer
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.ui.IPersistableElement

/**
 * Read-only Compare input for one MR file diff: base on the left, head on the right (design §5.1).
 *
 * The content is loaded before this is built, so [prepareInput] only returns a prebuilt [DiffNode]
 * and does no IO. [canRunAsJob] is `true` so that Compare, given a result that is already there,
 * opens without a modal progress context. Equality is the [MrFileDiffKey], so opening the same file
 * at the same snapshot reuses its editor. The [configuration] is injected: at runtime the opener
 * passes `CompareConfiguration()`; `CompareConfiguration(null)` is for headless tests only.
 */
class MrCompareEditorInput(
  val content: MrFileDiffContent,
  configuration: CompareConfiguration,
) : CompareEditorInput(configuration) {
  val key: MrFileDiffKey = content.request.key

  private val node: DiffNode =
    DiffNode(
      kindOf(content.sides.kind),
      null,
      element(content.sides.left, content.left),
      element(content.sides.right, content.right),
    )

  init {
    configuration.setLeftLabel(content.sides.leftLabel)
    configuration.setRightLabel(content.sides.rightLabel)
    configuration.isLeftEditable = false
    configuration.isRightEditable = false
    title = content.sides.title
  }

  override fun canRunAsJob(): Boolean = true

  override fun prepareInput(monitor: IProgressMonitor?): Any = node

  override fun getToolTipText(): String = content.sides.tooltip

  override fun isSaveNeeded(): Boolean = false

  override fun getPersistable(): IPersistableElement? = null

  override fun equals(other: Any?): Boolean = other is MrCompareEditorInput && other.key == key

  override fun hashCode(): Int = key.hashCode()

  private companion object {
    fun element(side: SideRef?, classified: ClassifiedContent?): MrCompareElement? {
      if (side == null || classified == null) return null
      return MrCompareElement(side.path.substringAfterLast('/'), classified)
    }

    fun kindOf(kind: MrDiffKind): Int =
      when (kind) {
        MrDiffKind.ADDITION -> Differencer.ADDITION
        MrDiffKind.DELETION -> Differencer.DELETION
        MrDiffKind.CHANGE -> Differencer.CHANGE
      }
  }
}
