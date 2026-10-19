package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk

class SidebarDoubleClickTest : DescribeSpec({
  it("a changed file opens its diff, never the browser") {
    val mrUrl = "https://gl/x/-/merge_requests/1"
    val node = ChangedFileNode("a.kt", "a.kt", ChangeType.MODIFIED, diffHeadSha = null, mrWebUrl = mrUrl)

    val action = sidebarDoubleClick(node)

    action.shouldBeInstanceOf<SidebarDoubleClick.OpenDiff>().node shouldBeSameInstanceAs node
  }

  it("a node with an activation URL opens it in the browser") {
    val node = mockk<SidebarNode> { every { activationUrl } returns "https://gl/x" }

    sidebarDoubleClick(node).shouldBeInstanceOf<SidebarDoubleClick.OpenUrl>().url shouldBe "https://gl/x"
  }

  it("anything else does nothing") {
    val node = mockk<SidebarNode> { every { activationUrl } returns null }

    sidebarDoubleClick(node) shouldBe SidebarDoubleClick.None
    sidebarDoubleClick(null) shouldBe SidebarDoubleClick.None
  }
})
