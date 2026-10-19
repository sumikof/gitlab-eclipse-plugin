package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private val BASE = "0123456789" + "a".repeat(30)
private val HEAD = "fedcba9876" + "b".repeat(30)

private fun request(
  oldPath: String?,
  newPath: String?,
  changeType: ChangeType,
  aMode: String? = "100644",
  bMode: String? = "100644",
  namespaceWithPath: String? = "group/project",
) = MrFileDiffRequest(
  instanceUrl = "https://gl.example",
  authFingerprint = "fp",
  projectId = 42L,
  mrIid = 7L,
  namespaceWithPath = namespaceWithPath,
  baseSha = BASE,
  headSha = HEAD,
  oldPath = oldPath,
  newPath = newPath,
  changeType = changeType,
  aMode = aMode,
  bMode = bMode,
)

class MrDiffSidesTest : DescribeSpec({
  describe("side selection") {
    it("NEW: no left side, right is newPath at head") {
      val sides = MrDiffSides.of(request(oldPath = "added.kt", newPath = "added.kt", changeType = ChangeType.NEW))

      sides.left shouldBe null
      sides.right shouldBe SideRef("added.kt", HEAD)
      sides.kind shouldBe MrDiffKind.ADDITION
    }

    it("DELETED: left is oldPath at base, no right side") {
      val sides = MrDiffSides.of(request(oldPath = "gone.kt", newPath = "gone.kt", changeType = ChangeType.DELETED))

      sides.left shouldBe SideRef("gone.kt", BASE)
      sides.right shouldBe null
      sides.kind shouldBe MrDiffKind.DELETION
    }

    it("MODIFIED: left is the path at base, right the path at head") {
      val sides = MrDiffSides.of(request(oldPath = "x.kt", newPath = "x.kt", changeType = ChangeType.MODIFIED))

      sides.left shouldBe SideRef("x.kt", BASE)
      sides.right shouldBe SideRef("x.kt", HEAD)
      sides.kind shouldBe MrDiffKind.CHANGE
    }

    it("RENAMED: left uses oldPath at base, right uses newPath at head") {
      val sides = MrDiffSides.of(request(oldPath = "old/A.kt", newPath = "new/B.kt", changeType = ChangeType.RENAMED))

      sides.left shouldBe SideRef("old/A.kt", BASE)
      sides.right shouldBe SideRef("new/B.kt", HEAD)
      sides.kind shouldBe MrDiffKind.CHANGE
    }

    it("MODIFIED with a null oldPath uses newPath for both sides") {
      val sides = MrDiffSides.of(request(oldPath = null, newPath = "x.kt", changeType = ChangeType.MODIFIED))

      sides.left shouldBe SideRef("x.kt", BASE)
      sides.right shouldBe SideRef("x.kt", HEAD)
    }
  }

  describe("title, labels and tooltip") {
    it("CHANGE: title is the head basename with the iid, labels carry path and 8-char SHA") {
      val sides = MrDiffSides.of(request(oldPath = "old/A.kt", newPath = "new/B.kt", changeType = ChangeType.RENAMED))

      sides.title shouldBe "B.kt (!7)"
      sides.leftLabel shouldBe "old/A.kt @ 01234567"
      sides.rightLabel shouldBe "new/B.kt @ fedcba98"
      sides.tooltip shouldBe "group/project !7: old/A.kt → new/B.kt"
    }

    it("NEW: the left label says added in the MR") {
      val sides = MrDiffSides.of(request(oldPath = "dir/n.kt", newPath = "dir/n.kt", changeType = ChangeType.NEW))

      sides.title shouldBe "n.kt (!7)"
      sides.leftLabel shouldBe "(added in !7)"
      sides.rightLabel shouldBe "dir/n.kt @ fedcba98"
      sides.tooltip shouldBe "group/project !7: dir/n.kt"
    }

    it("DELETED: title uses the old basename and the right label says deleted in the MR") {
      val sides = MrDiffSides.of(request(oldPath = "dir/gone.kt", newPath = null, changeType = ChangeType.DELETED))

      sides.title shouldBe "gone.kt (!7)"
      sides.leftLabel shouldBe "dir/gone.kt @ 01234567"
      sides.rightLabel shouldBe "(deleted in !7)"
      sides.tooltip shouldBe "group/project !7: dir/gone.kt"
    }

    it("tooltip falls back to the project id without a namespace path") {
      val sides =
        MrDiffSides.of(request(oldPath = "x.kt", newPath = "x.kt", ChangeType.MODIFIED, namespaceWithPath = null))

      sides.tooltip shouldBe "42 !7: x.kt"
    }
  }

  describe("submodule flag") {
    it("is set when either mode is a gitlink") {
      MrDiffSides.of(request("s", "s", ChangeType.MODIFIED, aMode = "160000", bMode = "100644")).submodule shouldBe true
      MrDiffSides.of(request("s", "s", ChangeType.MODIFIED, aMode = "100644", bMode = "160000")).submodule shouldBe true
    }

    it("is clear for regular, executable, symlink or absent modes") {
      MrDiffSides.of(request("s", "s", ChangeType.MODIFIED, aMode = "100644", bMode = "100755")).submodule shouldBe false
      MrDiffSides.of(request("s", "s", ChangeType.MODIFIED, aMode = "120000", bMode = null)).submodule shouldBe false
    }
  }
})
