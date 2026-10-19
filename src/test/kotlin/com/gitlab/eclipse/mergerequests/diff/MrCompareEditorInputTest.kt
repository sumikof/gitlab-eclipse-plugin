package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.eclipse.compare.CompareConfiguration
import org.eclipse.compare.IEditableContent
import org.eclipse.compare.structuremergeviewer.DiffNode
import org.eclipse.compare.structuremergeviewer.Differencer
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.jface.preference.IPreferenceStore

/** Headless: the configuration has no preference store, and `getTitleImage()` is never called (it needs the plugin). */
class MrCompareEditorInputTest : DescribeSpec({
  beforeSpec { stubHeadlessCompare() }
  afterSpec { releaseHeadlessCompare() }

  fun config() = CompareConfiguration(null as IPreferenceStore?)

  fun input(content: MrFileDiffContent = compareContent(), configuration: CompareConfiguration = config()) =
    MrCompareEditorInput(content, configuration)

  fun preparedNode(input: MrCompareEditorInput): DiffNode {
    input.run(NullProgressMonitor())
    return input.compareResult.shouldBeInstanceOf<DiffNode>()
  }

  describe("configuration") {
    it("labels base on the left and head on the right, from the sides") {
      val content = compareContent()
      val configuration = config()
      input(content, configuration)

      configuration.getLeftLabel(null) shouldBe content.sides.leftLabel
      configuration.getRightLabel(null) shouldBe content.sides.rightLabel
    }

    it("makes both sides read-only") {
      val configuration = config()
      input(configuration = configuration)

      configuration.isLeftEditable shouldBe false
      configuration.isRightEditable shouldBe false
    }

    it("takes its title and tooltip from the sides") {
      val content = compareContent()
      val input = input(content)

      input.title shouldBe content.sides.title
      input.toolTipText shouldBe content.sides.tooltip
    }

    it("runs as a job, never needs saving and is never persisted") {
      val input = input()

      input.canRunAsJob() shouldBe true
      input.isSaveNeeded shouldBe false
      input.persistable.shouldBeNull()
    }
  }

  describe("prepareInput") {
    it("a modified or renamed file is a CHANGE with both sides") {
      val node = preparedNode(input(compareContent(compareRequest(ChangeType.RENAMED))))

      node.kind shouldBe Differencer.CHANGE
      node.left.shouldNotBeNull().name shouldBe "Old.kt"
      node.right.shouldNotBeNull().name shouldBe "New.kt"
    }

    it("a new file is an ADDITION with no left side") {
      val node = preparedNode(input(compareContent(compareRequest(ChangeType.NEW, oldPath = null))))

      node.kind shouldBe Differencer.ADDITION
      node.left.shouldBeNull()
      node.right.shouldNotBeNull().name shouldBe "New.kt"
    }

    it("a deleted file is a DELETION with no right side") {
      val node = preparedNode(input(compareContent(compareRequest(ChangeType.DELETED, newPath = null))))

      node.kind shouldBe Differencer.DELETION
      node.left.shouldNotBeNull().name shouldBe "Old.kt"
      node.right.shouldBeNull()
    }

    it("hands Compare the loaded bytes, base left and head right, never editable") {
      val content = compareContent(leftBytes = "base\n".toByteArray(), rightBytes = "head\n".toByteArray())
      val node = preparedNode(input(content))

      (node.left as MrCompareElement).contents.readBytes() shouldBe "base\n".toByteArray()
      (node.right as MrCompareElement).contents.readBytes() shouldBe "head\n".toByteArray()
      node.left.shouldNotBeInstanceOf<IEditableContent>()
      node.right.shouldNotBeInstanceOf<IEditableContent>()
    }
  }

  describe("identity (editor reuse)") {
    it("two inputs for the same key are equal, even with other content instances") {
      val request = compareRequest()
      val a = input(compareContent(request, leftBytes = "1".toByteArray()))
      val b = input(compareContent(request, leftBytes = "2".toByteArray()))

      a shouldBe b
      a.hashCode() shouldBe b.hashCode()
      a.key shouldBe request.key
    }

    it("another head SHA is another editor") {
      val a = input(compareContent(compareRequest()))
      val b = input(compareContent(compareRequest(head = "c".repeat(40))))

      a shouldNotBe b
    }

    it("another file is another editor") {
      val a = input(compareContent(compareRequest(newPath = "src/A.kt")))
      val b = input(compareContent(compareRequest(newPath = "src/B.kt")))

      a shouldNotBe b
    }
  }
})
