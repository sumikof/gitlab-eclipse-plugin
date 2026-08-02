package com.gitlab.eclipse.ci.lint

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MergedYamlEditorInputTest : DescribeSpec({
  val key = MergedYamlKey("https://gitlab.example.com", "10", "source-a")

  describe("MergedYamlKey equality") {
    it("same instanceUrl/projectId/sourceId are equal and hash equal") {
      val other = MergedYamlKey("https://gitlab.example.com", "10", "source-a")
      other shouldBe key
      other.hashCode() shouldBe key.hashCode()
    }

    it("different sourceId is NOT equal (distinct unsaved inputs stay distinct tabs)") {
      val other = MergedYamlKey("https://gitlab.example.com", "10", "source-b")
      other shouldNotBe key
    }

    it("different instanceUrl is NOT equal") {
      val other = MergedYamlKey("https://other.example.org", "10", "source-a")
      other shouldNotBe key
    }

    it("different projectId is NOT equal") {
      val other = MergedYamlKey("https://gitlab.example.com", "99", "source-a")
      other shouldNotBe key
    }
  }

  describe("MergedYamlEditorInput equals/hashCode") {
    it("inputs with equal keys are equal even with different content instances and text") {
      val a = MergedYamlEditorInput(key, MergedYamlContent("first text"))
      val b = MergedYamlEditorInput(key, MergedYamlContent("completely different"))
      (a == b) shouldBe true
      a.hashCode() shouldBe b.hashCode()
    }

    it("different key is NOT equal") {
      val otherKey = MergedYamlKey("https://gitlab.example.com", "10", "source-b")
      val a = MergedYamlEditorInput(key, MergedYamlContent("same"))
      val b = MergedYamlEditorInput(otherKey, MergedYamlContent("same"))
      (a == b) shouldBe false
    }
  }

  describe("transient input contract") {
    val input = MergedYamlEditorInput(key, MergedYamlContent(""))

    it("exists() is false (kept out of EditorHistory)") {
      input.exists() shouldBe false
    }

    it("is not persistable") {
      input.persistable shouldBe null
    }

    it("getAdapter returns null for any adapter type") {
      input.getAdapter(String::class.java) shouldBe null
    }

    it("name is the fixed literal .gitlab-ci (Merged).yml") {
      input.name shouldBe ".gitlab-ci (Merged).yml"
    }

    it("tooltip text matches the name") {
      input.toolTipText shouldBe ".gitlab-ci (Merged).yml"
    }
  }

  describe("getStorage") {
    it("reflects the current content.text on a fresh storage read") {
      val content = MergedYamlContent("before")
      val input = MergedYamlEditorInput(key, content)
      input.storage.contents.readBytes().toString(Charsets.UTF_8) shouldBe "before"
      content.text = "after refresh"
      input.storage.contents.readBytes().toString(Charsets.UTF_8) shouldBe "after refresh"
    }
  }

  describe("MergedYamlStorage") {
    fun readAll(storage: MergedYamlStorage): String =
      storage.contents.readBytes().toString(Charsets.UTF_8)

    it("returns the current text as UTF-8 bytes") {
      val content = MergedYamlContent("stages:\n  - build")
      val storage = MergedYamlStorage(content, ".gitlab-ci (Merged).yml")
      readAll(storage) shouldBe "stages:\n  - build"
    }

    it("round-trips non-ASCII Japanese text through UTF-8") {
      val content = MergedYamlContent("ジョブ: 成功")
      val storage = MergedYamlStorage(content, ".gitlab-ci (Merged).yml")
      readAll(storage) shouldBe "ジョブ: 成功"
    }

    it("pins charset to UTF-8") {
      MergedYamlStorage(MergedYamlContent(""), "n").charset shouldBe "UTF-8"
    }

    it("is read-only") {
      MergedYamlStorage(MergedYamlContent(""), "n").isReadOnly shouldBe true
    }

    it("has no full path") {
      MergedYamlStorage(MergedYamlContent(""), "n").fullPath shouldBe null
    }

    it("returns the passed name") {
      MergedYamlStorage(MergedYamlContent(""), ".gitlab-ci (Merged).yml").name shouldBe ".gitlab-ci (Merged).yml"
    }
  }
})
