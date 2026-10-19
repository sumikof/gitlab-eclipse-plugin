package com.gitlab.eclipse.mergerequests.diff

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.eclipse.compare.IEditableContent
import org.eclipse.compare.IEncodedStreamContentAccessor
import org.eclipse.compare.ITypedElement

class MrCompareElementTest : DescribeSpec({
  val bytes = "héllo\r\nworld".toByteArray(Charsets.UTF_8)

  it("returns a fresh stream with the same bytes on every call") {
    val element = MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", "UTF-8"))

    val first = element.contents
    val second = element.contents

    first shouldNotBeSameInstanceAs second
    first.readBytes() shouldBe bytes
    second.readBytes() shouldBe bytes
  }

  it("exposes the name, the classified type and no image") {
    val element = MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", "UTF-8"))

    element.name shouldBe "A.kt"
    element.type shouldBe "kt"
    element.image.shouldBeNull()
  }

  it("uses the classified charset when there is one") {
    val element = MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", "UTF-8")) { "ISO-8859-1" }

    element.charset shouldBe "UTF-8"
  }

  it("falls back to the workspace default encoding when the charset is unknown") {
    val element = MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", null)) { "windows-31j" }

    element.charset shouldBe "windows-31j"
  }

  it("the production fallback answers headless without throwing") {
    MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", null)).charset shouldNotBe null
  }

  it("is a read-only typed, encoded stream element and never editable content") {
    val element: Any = MrCompareElement("A.kt", ClassifiedContent(bytes, "kt", "UTF-8"))

    (element is ITypedElement) shouldBe true
    (element is IEncodedStreamContentAccessor) shouldBe true
    element.shouldNotBeInstanceOf<IEditableContent>()
  }
})
