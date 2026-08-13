package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SnippetPatchBuilderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val diff = "diff --git a/A.kt b/A.kt\n--- a/A.kt\n+++ b/A.kt\n@@ -1 +1 @@\n-a\n+b\n"

  describe("build") {
    it("names the snippet and the file from the patch name") {
      val result =
        SnippetPatchBuilder.build("fix-login", diff, "branch main (commit: abc1234)", SnippetVisibility.PRIVATE)

      result as SnippetPatchBuilder.Result.Ok
      result.payload.title shouldBe "patch: fix-login"
      result.payload.fileName shouldBe "fix-login.patch"
      result.payload.visibility shouldBe "private"
      result.payload.content shouldBe diff
    }

    it("describes how to apply the patch and names the source commit") {
      val result =
        SnippetPatchBuilder.build("fix-login", diff, "branch main (commit: abc1234)", SnippetVisibility.PRIVATE)

      val description = (result as SnippetPatchBuilder.Result.Ok).payload.description!!
      description shouldContain "branch main (commit: abc1234)"
      description shouldContain "fix-login.patch"
      description shouldContain "git apply"
    }

    it("rejects a diff that carries no content for a binary file") {
      // JGit's DiffFormatter emits this marker instead of a payload, so the patch could never be
      // applied back (design section 3). Refuse before anything is sent.
      val binary = "diff --git a/logo.png b/logo.png\nBinary files a/logo.png and b/logo.png differ\n"

      SnippetPatchBuilder.build("x", binary, "commit abc1234", SnippetVisibility.PRIVATE) shouldBe
        SnippetPatchBuilder.Result.BinaryNotSupported
    }

    it("rejects a git binary patch payload as well") {
      val binary = "diff --git a/logo.png b/logo.png\nGIT binary patch\nliteral 12\n"

      SnippetPatchBuilder.build("x", binary, "commit abc1234", SnippetVisibility.PRIVATE) shouldBe
        SnippetPatchBuilder.Result.BinaryNotSupported
    }

    it("rejects an empty diff") {
      SnippetPatchBuilder.build("x", "   \n", "commit abc1234", SnippetVisibility.PRIVATE) shouldBe
        SnippetPatchBuilder.Result.NoChanges
    }

    it("rejects a blank patch name") {
      SnippetPatchBuilder.build("  ", diff, "commit abc1234", SnippetVisibility.PRIVATE) shouldBe
        SnippetPatchBuilder.Result.InvalidName
    }
  }
})
