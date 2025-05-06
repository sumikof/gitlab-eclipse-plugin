package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import org.eclipse.swt.widgets.Display

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationStateServiceTest : DescribeSpec({

  val testScope = TestScope(StandardTestDispatcher())
  val notifDelay = 1000L

  fun getAuthStateService() = AuthenticationStateService(testScope, notifDelay)
  var authStateService = getAuthStateService()

  beforeTest {
    authStateService = getAuthStateService()

    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")
    val displayMock = mockk<Display>(relaxed = true)
    every { currentDisplay } returns displayMock
    every { currentDisplay.asyncExec(any()) } just runs
  }

  afterTest {
    unmockkAll()
  }

  describe("update") {
    it("when update is called, should wait full notifDelay before showing notification") {
      val featureStateChange = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = true
          )
        )
      )

      authStateService.update(
        featureStateChange = featureStateChange
      )

      for (time in listOf(0L, notifDelay / 3, notifDelay / 2, notifDelay)) {
        testScope.advanceTimeBy(time - testScope.currentTime)
        verify(exactly = 0) { currentDisplay.asyncExec(any()) }
      }

      testScope.advanceTimeBy(1)
      verify(exactly = 1) { currentDisplay.asyncExec(any()) }
    }

    it("when multiple updates occur within notifDelay period, should only process the most recent update") {
      val featureStateChange1 = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = false
          )
        )
      )

      val featureStateChange2 = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = true
          )
        )
      )

      authStateService.update(featureStateChange = featureStateChange1)

      testScope.advanceTimeBy(notifDelay / 2)
      verify(exactly = 0) { currentDisplay.asyncExec(any()) }

      authStateService.update(featureStateChange = featureStateChange2)

      testScope.advanceTimeBy(notifDelay / 2 + 1)
      verify(exactly = 0) { currentDisplay.asyncExec(any()) }

      testScope.advanceTimeBy(notifDelay)
      verify(exactly = 1) { currentDisplay.asyncExec(any()) }
    }

    it("when authentication is required, should display notification after delay") {
      val featureStateChange = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = true
          )
        )
      )

      authStateService.update(featureStateChange = featureStateChange)
      testScope.advanceTimeBy(notifDelay + 1)
      verify(exactly = 1) { currentDisplay.asyncExec(any()) }
    }

    it("when authentication is not required, should not display any notification") {
      val featureStateChange = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = false
          )
        )
      )

      authStateService.update(featureStateChange = featureStateChange)
      testScope.advanceTimeBy(notifDelay + 1)
      verify(exactly = 0) { currentDisplay.asyncExec(any()) }
    }
  }

  describe("resetAuthenticatedState") {
    it("when authentication state is reset, should cause subsequent unauthenticated updates to show notification") {
      val unauthenticatedStateChange = FeatureStateChange(
        featureId = "test-feature",
        allChecks = listOf(
          FeatureStateChangeCheck(
            checkId = "authentication-required",
            engaged = true
          )
        )
      )

      authStateService.update(featureStateChange = unauthenticatedStateChange)
      testScope.advanceTimeBy(notifDelay + 1)
      verify(exactly = 1) { currentDisplay.asyncExec(any()) }

      authStateService.update(featureStateChange = unauthenticatedStateChange)
      testScope.advanceTimeBy(notifDelay + 1)
      verify(exactly = 1) { currentDisplay.asyncExec(any()) }

      authStateService.resetAuthenticatedState()

      authStateService.update(featureStateChange = unauthenticatedStateChange)
      testScope.advanceTimeBy(notifDelay + 1)
      verify(exactly = 2) { currentDisplay.asyncExec(any()) }
    }
  }
})
