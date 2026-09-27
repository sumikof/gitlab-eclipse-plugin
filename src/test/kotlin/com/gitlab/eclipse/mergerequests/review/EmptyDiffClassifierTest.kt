package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabMrVersion
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun diffEntry(
  oldPath: String? = "a.txt",
  newPath: String? = "a.txt",
  newFile: Boolean = false,
  deletedFile: Boolean = false,
  renamedFile: Boolean = false,
  diff: String? = null,
  tooLarge: Boolean? = null,
  collapsed: Boolean? = null,
) = GitLabMrVersion.Diff(
  oldPath = oldPath,
  newPath = newPath,
  newFile = newFile,
  deletedFile = deletedFile,
  renamedFile = renamedFile,
  diff = diff,
  tooLarge = tooLarge,
  collapsed = collapsed,
)

private fun neverCalled(): Boolean? = error("blobIdsEqual should not have been invoked")

class EmptyDiffClassifierTest : DescribeSpec({
  describe("classifyDiff (design §12.2.1, rows in order)") {
    it("row 1: tooLarge == true is Unavailable, without consulting blobIdsEqual") {
      val entry = diffEntry(tooLarge = true, diff = "@@ -1,1 +1,1 @@\n-a\n+b\n")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 1: collapsed == true is Unavailable, without consulting blobIdsEqual") {
      val entry = diffEntry(collapsed = true, diff = "@@ -1,1 +1,1 @@\n-a\n+b\n")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 1: tooLarge == true and a non-empty diff is still Unavailable") {
      val entry = diffEntry(tooLarge = true, diff = "@@ -1,1 +1,1 @@\n-a\n+b\n")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 2: empty diff + renamedFile + blobIdsEqual() == true is Identity (pure rename)") {
      val entry = diffEntry(oldPath = "old.txt", newPath = "new.txt", renamedFile = true, diff = "")

      classifyDiff(entry, headBlobIsEmpty = false) { true } shouldBe DiffLineMap.Identity
    }

    it("row 2: empty diff + renamedFile + blobIdsEqual() == false falls through to Unavailable") {
      val entry = diffEntry(oldPath = "old.txt", newPath = "new.txt", renamedFile = true, diff = "")

      classifyDiff(entry, headBlobIsEmpty = false) { false } shouldBe DiffLineMap.Unavailable
    }

    it("row 2: empty diff + renamedFile + blobIdsEqual() == null (no local base) falls to Unavailable") {
      val entry = diffEntry(oldPath = "old.txt", newPath = "new.txt", renamedFile = true, diff = "")

      val result = classifyDiff(entry, headBlobIsEmpty = false) { null }

      result shouldBe DiffLineMap.Unavailable
    }

    it("blobIdsEqual is only invoked for the empty-diff + renamedFile row, not otherwise") {
      val entry = diffEntry(oldPath = "a.txt", newPath = "a.txt", renamedFile = false, diff = "")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 3: empty diff + newFile + an empty HEAD blob is a Parsed map with no hunks") {
      val entry = diffEntry(oldPath = null, newPath = "new.txt", newFile = true, diff = "")

      val result = classifyDiff(entry, headBlobIsEmpty = true, blobIdsEqual = ::neverCalled)

      result.shouldBeInstanceOf<DiffLineMap.Parsed>()
      (result as DiffLineMap.Parsed).hunks shouldBe emptyList()
    }

    it("row 3: empty diff + newFile but a non-empty HEAD blob falls through to Unavailable") {
      val entry = diffEntry(oldPath = null, newPath = "new.txt", newFile = true, diff = "")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 4: any other empty diff (e.g. a large file without the tooLarge flag) is Unavailable") {
      val entry = diffEntry(diff = "")

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }

    it("row 5: a non-empty diff is parsed via DiffLineMap.parse") {
      // @@ -1,1 +1,1 @@ / -a / +b: line 1 is a straight replacement, so new line 1 is Added.
      val diffText = "@@ -1,1 +1,1 @@\n-a\n+b\n"
      val entry = diffEntry(diff = diffText)

      val result = classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled)

      result.shouldBeInstanceOf<DiffLineMap.Parsed>()
      result.classify(1) shouldBe NewLineKind.Added
    }

    it("a null diff (field absent from the response) is Unavailable, not passed to DiffLineMap.parse") {
      val entry = diffEntry(diff = null)

      classifyDiff(entry, headBlobIsEmpty = false, blobIdsEqual = ::neverCalled) shouldBe DiffLineMap.Unavailable
    }
  }
})
