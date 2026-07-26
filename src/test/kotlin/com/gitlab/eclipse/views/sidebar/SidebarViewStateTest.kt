package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SidebarViewStateTest : StringSpec({
  "fires listener on mode change only" {
    val state = SidebarViewState(SidebarViewMode.LIST)
    var count = 0
    state.addListener { count++ }
    state.mode = SidebarViewMode.TREE
    count shouldBe 1
    state.mode = SidebarViewMode.TREE
    count shouldBe 1
    state.mode = SidebarViewMode.LIST
    count shouldBe 2
  }
})
