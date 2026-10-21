package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.authentication.AuthState
import com.gitlab.eclipse.codesuggestions.handlers.CodeSuggestionsStatusHandler
import com.gitlab.eclipse.codesuggestions.status.DuoStatusInputsProvider
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.menus.UIElement
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.module

/**
 * The trim item and the menu label never break their render on a checked exception from the
 * inputs (Koin 4 resolution failures extend java.lang.Exception). Headless: the inputs throw before
 * any icon lookup, so no SWT Display is touched.
 */
class DuoStatusElementUpdateContainmentTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin {
      modules(
        module {
          single<DuoStatusInputsProvider> {
            DuoStatusInputsProvider(
              lifecycle = { throw NoDefinitionFoundException("koin") },
              authState = { AuthState.UNKNOWN },
              codeSuggestionsEnabled = { true },
              featureStateKnown = { true },
              primaryCheckId = { null },
              isLanguageEnabled = { true },
            )
          }
        },
      )
    }
  }

  afterSpec { stopKoin() }

  fun element(): UIElement = mockk(relaxUnitFun = true) { every { serviceLocator } returns null }

  it("the trim item keeps its icon and tooltip") {
    val element = element()
    shouldNotThrowAny { ShowPluginStatusMenu().updateElement(element, mutableMapOf()) }
    verify(exactly = 0) { element.setIcon(any()) }
    verify(exactly = 0) { element.setTooltip(any()) }
  }

  it("the Code Suggestions status menu item keeps its label") {
    val element = element()
    shouldNotThrowAny { CodeSuggestionsStatusHandler().updateElement(element, mutableMapOf()) }
    verify(exactly = 0) { element.setText(any()) }
  }
})
