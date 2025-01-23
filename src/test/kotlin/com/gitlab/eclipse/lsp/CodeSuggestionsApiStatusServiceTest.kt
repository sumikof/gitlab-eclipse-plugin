package com.gitlab.eclipse.lsp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CodeSuggestionsApiStatusServiceTest : DescribeSpec({
  lateinit var service: CodeSuggestionsApiStatusService

  beforeEach {
    service = CodeSuggestionsApiStatusService(TestScope(UnconfinedTestDispatcher()))
  }

  it("should have initial status as null") {
    service.apiStatus.value shouldBe null
  }

  it("should emit Error status when reportError is called") {
    runTest {
      service.reportError()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Error
    }
  }

  it("should emit Recovery status when reportRecovery is called") {
    runTest {
      service.reportRecovery()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Recovery
    }
  }

  it("should change status from Error to Recovery") {
    runTest {
      service.reportError()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Error

      service.reportRecovery()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Recovery
    }
  }

  it("should change status from Recovery to Error") {
    runTest {
      service.reportRecovery()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Recovery

      service.reportError()
      service.apiStatus.value shouldBe CodeSuggestionsApiStatusService.ApiStatus.Error
    }
  }

  it("should not trigger collection for duplicate status") {
    runTest {
      service.reportRecovery()

      val currentValue = service.apiStatus.value
      currentValue shouldBe CodeSuggestionsApiStatusService.ApiStatus.Recovery

      service.reportRecovery()
      service.apiStatus.value shouldBe currentValue
    }
  }
})
