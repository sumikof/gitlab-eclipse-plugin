package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore

class CodeSuggestionsLanguageServiceTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>()

  val codeSuggestionsLanguageService = CodeSuggestionsLanguageService(preferenceStore)

  describe("getAdditionalLanguages") {
    it("returns empty list when preference is blank") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES) } returns ""

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe emptyList()
    }

    it("returns empty list when preference is only whitespace") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES) } returns "   "

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe emptyList()
    }

    it("splits comma-separated languages and trims whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "java, kotlin,  python "

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }

    it("ignores empty entries") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      } returns "java,,kotlin, ,python"

      val result = codeSuggestionsLanguageService.getAdditionalLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }
  }

  describe("getDisabledLanguages") {
    it("returns empty list when preference is blank") {
      every { preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES) } returns ""

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe emptyList()
    }

    it("returns empty list when preference is only whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "   "

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe emptyList()
    }

    it("splits comma-separated languages and trims whitespace") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java, kotlin,  python "

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }

    it("ignores empty entries") {
      every {
        preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      } returns "java,,kotlin, ,python"

      val result = codeSuggestionsLanguageService.getDisabledLanguages()

      result shouldBe listOf("java", "kotlin", "python")
    }
  }
})
