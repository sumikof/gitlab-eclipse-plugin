package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.contexts.IContextActivation
import org.eclipse.ui.contexts.IContextService
import org.eclipse.ui.internal.Workbench

class CodeSuggestionsCommandContextTest : DescribeSpec({
  val platformUtils = mockk<PlatformUtils>(relaxed = true)
  val workbench = mockk<Workbench>(relaxed = true)

  val contextService = mockk<IContextService>(relaxed = true)
  val contextActivation = mockk<IContextActivation>(relaxed = true)

  val context = CodeSuggestionsCommandContext(platformUtils)

  beforeEach {
    every { platformUtils.getWorkbench() } returns workbench
    every { workbench.getService(IContextService::class.java) } returns contextService

    every { contextService.activateContext("com.gitlab.eclipse.codesuggestions.context") } returns contextActivation
  }

  afterEach {
    clearAllMocks()
  }

  it("should activate code suggestion context") {
    context.activate()

    verify { contextService.activateContext("com.gitlab.eclipse.codesuggestions.context") }
  }

  it("should deactivate existing context before activating a new one") {
    context.activate()
    context.activate()

    verify {
      contextService.activateContext("com.gitlab.eclipse.codesuggestions.context")
      contextService.deactivateContext(contextActivation)
      contextService.activateContext("com.gitlab.eclipse.codesuggestions.context")
    }
  }

  it("should deactivate code suggestion context") {
    context.activate()
    context.deactivate()

    verify { contextService.deactivateContext(contextActivation) }
  }

  it("should not deactivate if context is not activated") {
    context.deactivate()

    verify(exactly = 0) { contextService.deactivateContext(any()) }
  }
})
