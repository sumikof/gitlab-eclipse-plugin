package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.isActive

class QuickChatRuntimeLifecycleTest : DescribeSpec({
  afterEach { QuickChatRuntimeLifecycle.closeIfCreated() }

  it("closeIfCreated closes the created runtime once and forgets it") {
    val runtime = QuickChatRuntimeLifecycle.create()
    QuickChatRuntimeLifecycle.current shouldBeSameInstanceAs runtime
    QuickChatRuntimeLifecycle.closeIfCreated()
    runtime.scope.isActive shouldBe false
    QuickChatRuntimeLifecycle.current.shouldBeNull()
    QuickChatRuntimeLifecycle.closeIfCreated() // nothing left: no-op
  }

  it("closeIfCreated with nothing created creates nothing") {
    QuickChatRuntimeLifecycle.closeIfCreated()
    QuickChatRuntimeLifecycle.current.shouldBeNull()
  }

  it("closeIfCreated does not reset the abandoned-job count") {
    val before = QuickChatDetachedJobs.count
    QuickChatRuntimeLifecycle.create()
    QuickChatRuntimeLifecycle.closeIfCreated()
    QuickChatDetachedJobs.count shouldBe before
  }
})
