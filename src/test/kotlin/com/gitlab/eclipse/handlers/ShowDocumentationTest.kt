package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.BrowserLauncher
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent

class ShowDocumentationTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  it("opens the Eclipse documentation through the BrowserLauncher gate, once") {
    val browser = mockk<BrowserLauncher>(relaxUnitFun = true)

    val result = ShowDocumentation(browser).execute(mockk<ExecutionEvent>())

    result shouldBe null
    verify(exactly = 1) { browser.open("https://docs.gitlab.com/editor_extensions/eclipse/") }
    verify(exactly = 1) { browser.open(ShowDocumentation.URL) }
  }
})
