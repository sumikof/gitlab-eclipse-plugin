package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.mergerequests.diff.MrFileDiffRequest.InvalidReason
import com.gitlab.eclipse.mergerequests.diff.MrFileDiffRequest.Validated
import com.gitlab.eclipse.views.sidebar.ChangeType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val SHA40_BASE = "a".repeat(40)
private val SHA40_HEAD = "b".repeat(40)
private val SHA64 = "c".repeat(64)

private fun source(
  projectId: Long = 42L,
  mrIid: Long = 7L,
  baseSha: String? = SHA40_BASE,
  headSha: String? = SHA40_HEAD,
) = MrDiffSource(
  instanceUrl = "https://gl.example",
  authFingerprint = "fp",
  projectId = projectId,
  mrIid = mrIid,
  namespaceWithPath = "group/project",
  baseSha = baseSha,
  startSha = "d".repeat(40),
  headSha = headSha,
)

private fun from(
  source: MrDiffSource? = source(),
  oldPath: String? = "src/A.kt",
  newPath: String? = "src/A.kt",
  changeType: ChangeType = ChangeType.MODIFIED,
) = MrFileDiffRequest.from(source, oldPath, newPath, changeType, aMode = "100644", bMode = "100644")

private fun Validated.reason(): InvalidReason = shouldBeInstanceOf<Validated.Invalid>().reason

class MrFileDiffRequestTest : DescribeSpec({
  describe("from — valid input") {
    it("builds a request from a source with 40-hex SHAs, copying every field") {
      val ok = from().shouldBeInstanceOf<Validated.Ok>().request

      ok shouldBe MrFileDiffRequest(
        instanceUrl = "https://gl.example",
        authFingerprint = "fp",
        projectId = 42L,
        mrIid = 7L,
        namespaceWithPath = "group/project",
        baseSha = SHA40_BASE,
        headSha = SHA40_HEAD,
        oldPath = "src/A.kt",
        newPath = "src/A.kt",
        changeType = ChangeType.MODIFIED,
        aMode = "100644",
        bMode = "100644",
      )
    }

    it("accepts 64-hex (SHA-256) SHAs") {
      from(source(baseSha = SHA64, headSha = SHA64)).shouldBeInstanceOf<Validated.Ok>()
    }

    it("accepts a path with spaces, Japanese and URL-special characters") {
      from(oldPath = "dir/日本語 file#?.kt", newPath = "dir/日本語 file#?.kt").shouldBeInstanceOf<Validated.Ok>()
    }

    it("key and fileIdentity carry the snapshot and file facts") {
      val request = from(oldPath = "old.kt", newPath = "new.kt", changeType = ChangeType.RENAMED)
        .shouldBeInstanceOf<Validated.Ok>().request

      request.key shouldBe MrFileDiffKey("https://gl.example", 42L, 7L, SHA40_BASE, SHA40_HEAD, "old.kt", "new.kt")
      request.fileIdentity shouldBe MrFileIdentity("https://gl.example", 42L, 7L, "old.kt", "new.kt")
    }
  }

  describe("from — source, project and iid") {
    it("rejects a missing source") {
      from(source = null).reason() shouldBe InvalidReason.NO_SOURCE
    }

    it("rejects a non-positive project id") {
      from(source(projectId = 0L)).reason() shouldBe InvalidReason.BAD_PROJECT
    }

    it("rejects a non-positive iid") {
      from(source(mrIid = 0L)).reason() shouldBe InvalidReason.BAD_IID
    }
  }

  describe("from — SHA validation") {
    it("rejects an uppercase SHA instead of lowercasing it") {
      from(source(baseSha = "A".repeat(40))).reason() shouldBe InvalidReason.BAD_SHA
    }

    it("rejects a missing base or head SHA") {
      from(source(baseSha = null)).reason() shouldBe InvalidReason.BAD_SHA
      from(source(headSha = null)).reason() shouldBe InvalidReason.BAD_SHA
    }

    it("rejects SHAs of the wrong length or alphabet") {
      from(source(headSha = "a".repeat(39))).reason() shouldBe InvalidReason.BAD_SHA
      from(source(headSha = "a".repeat(41))).reason() shouldBe InvalidReason.BAD_SHA
      from(source(headSha = "g".repeat(40))).reason() shouldBe InvalidReason.BAD_SHA
    }
  }

  describe("from — which paths are required") {
    it("NEW needs only newPath and ignores a bad oldPath") {
      from(oldPath = "../x", newPath = "added.kt", changeType = ChangeType.NEW).shouldBeInstanceOf<Validated.Ok>()
      from(oldPath = null, newPath = null, changeType = ChangeType.NEW).reason() shouldBe InvalidReason.BAD_PATH
    }

    it("DELETED needs only oldPath and ignores a bad newPath") {
      from(oldPath = "gone.kt", newPath = null, changeType = ChangeType.DELETED).shouldBeInstanceOf<Validated.Ok>()
      from(oldPath = null, newPath = "x.kt", changeType = ChangeType.DELETED).reason() shouldBe InvalidReason.BAD_PATH
    }

    it("RENAMED and MODIFIED need both paths") {
      from(oldPath = null, newPath = "n.kt", changeType = ChangeType.RENAMED).reason() shouldBe InvalidReason.BAD_PATH
      from(oldPath = "o.kt", newPath = null, changeType = ChangeType.RENAMED).reason() shouldBe InvalidReason.BAD_PATH
      from(oldPath = "o.kt", newPath = null, changeType = ChangeType.MODIFIED).reason() shouldBe InvalidReason.BAD_PATH
      from(oldPath = "a//b", newPath = "a.kt").reason() shouldBe InvalidReason.BAD_PATH
    }
  }

  describe("from — path validation") {
    listOf("", "/a", "a//b", "a/./b", "a/../b", "a/", "..", "a\u0000b", "a\nb", "a\rb", "a".repeat(4097))
      .forEach { bad ->
        it("rejects the path ${bad.take(12).replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r")}") {
          from(oldPath = bad, newPath = bad).reason() shouldBe InvalidReason.BAD_PATH
        }
      }

    mapOf(
      "TAB" to "a\tb",
      "ESC" to "a\u001Bb",
      "unit separator" to "a\u001Fb",
      "DEL" to "a\u007Fb",
      "C1 NEL" to "a\u0085b",
      "C1 APC" to "a\u009Fb",
      "LRE U+202A" to "a\u202Ab",
      "RLO U+202E" to "evil\u202Etxt.exe",
      "LRI U+2066" to "a\u2066b",
      "PDI U+2069" to "a\u2069b",
      "backslash" to "dir\\file.kt",
    ).forEach { (name, bad) ->
      it("rejects a path containing $name (R7)") {
        from(oldPath = bad, newPath = bad).reason() shouldBe InvalidReason.BAD_PATH
      }
    }

    it("still accepts characters just outside the rejected ranges") {
      listOf("a\u00A0b", "a\u2029b", "a\u206Ab", "a\u202Fb", "日本語/ファイル.kt").forEach { ok ->
        from(oldPath = ok, newPath = ok).shouldBeInstanceOf<Validated.Ok>()
      }
    }

    it("accepts a path of exactly 4096 characters") {
      val longest = "a".repeat(4096)
      from(oldPath = longest, newPath = longest).shouldBeInstanceOf<Validated.Ok>()
    }
  }
})
