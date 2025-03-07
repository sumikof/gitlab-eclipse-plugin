package com.gitlab.eclipse.codesuggestions.annotation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.theming.ThemeUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.jface.text.source.Annotation
import org.eclipse.swt.graphics.Image

class CodeSuggestionsAnnotationImageProviderTest : DescribeSpec({
  val duoLoadImage = mockk<Image>()
  val duoReadyImage = mockk<Image>()

  val imageProvider = CodeSuggestionsAnnotationImageProvider()

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkObject(ThemeUtils)
  }

  beforeEach {
    every { ThemeUtils.getThemedIcon("duo_load_edit")?.createImage() } returns duoLoadImage
    every { ThemeUtils.getThemedIcon("duo_on_edit")?.createImage() } returns duoReadyImage
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    unmockkAll()
  }

  describe("getManagedImage") {
    it("should return no annotation image for a non code suggestions annotation") {
      val annotation = Annotation("some.other.type", false, "Not a code suggestion")

      val image = imageProvider.getManagedImage(annotation)

      image shouldBe null
    }

    it("should provide a duo_load_edit image for loading code suggestions") {
      val annotation = CodeSuggestionAnnotation(CodeSuggestionAnnotationType.LOADING)

      val image = imageProvider.getManagedImage(annotation)

      image shouldBe ThemeUtils.getThemedIcon("duo_load_edit")?.createImage()
    }

    it("should provide a duo_on_edit image for code suggestions ready") {
      val annotation = CodeSuggestionAnnotation(CodeSuggestionAnnotationType.READY)

      val image = imageProvider.getManagedImage(annotation)

      image shouldBe ThemeUtils.getThemedIcon("duo_on_edit")?.createImage()
    }

    it("should return null if an exception is thrown when loading the image") {
      val annotation = CodeSuggestionAnnotation(CodeSuggestionAnnotationType.READY)
      every { ThemeUtils.getThemedIcon(any()) } throws RuntimeException("Failed to load image")

      val image = imageProvider.getManagedImage(annotation)

      image shouldBe null
    }
  }

  describe("getImageDescriptorId") {
    it("should return null for a non code suggestions annotation") {
      val annotation = Annotation("some.other.type", false, "Not a code suggestion")

      val imageDescriptorId = imageProvider.getImageDescriptorId(annotation)

      imageDescriptorId shouldBe null
    }

    it("should return duo_load_edit for loading code suggestions") {
      val annotation = CodeSuggestionAnnotation(CodeSuggestionAnnotationType.LOADING)

      val imageDescriptorId = imageProvider.getImageDescriptorId(annotation)

      imageDescriptorId shouldBe "duo_load_edit"
    }

    it("should return duo_on_edit for code suggestions ready") {
      val annotation = CodeSuggestionAnnotation(CodeSuggestionAnnotationType.READY)

      val imageDescriptorId = imageProvider.getImageDescriptorId(annotation)

      imageDescriptorId shouldBe "duo_on_edit"
    }
  }

  describe("getImageDescriptor") {
    it("should return null for null descriptor id") {
      val imageDescriptor = imageProvider.getImageDescriptor(null)

      imageDescriptor shouldBe null
    }

    it("should return themed icon for duo_load_edit") {
      val duoLoadIcon = mockk<ImageDescriptor>()
      every { ThemeUtils.getThemedIcon("duo_load_edit") } returns duoLoadIcon

      val imageDescriptor = imageProvider.getImageDescriptor("duo_load_edit")

      imageDescriptor shouldBe duoLoadIcon
    }

    it("should return themed icon for duo_on_edit") {
      val duoOnIcon = mockk<ImageDescriptor>()
      every { ThemeUtils.getThemedIcon("duo_on_edit") } returns duoOnIcon

      val imageDescriptor = imageProvider.getImageDescriptor("duo_on_edit")

      imageDescriptor shouldBe duoOnIcon
    }

    it("should return null for unknown descriptor id") {
      val imageDescriptor = imageProvider.getImageDescriptor("unknown_id")

      imageDescriptor shouldBe null
    }
  }
})
