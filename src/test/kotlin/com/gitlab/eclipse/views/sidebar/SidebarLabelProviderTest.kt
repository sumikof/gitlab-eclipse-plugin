package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Image

private fun job(status: String?, allowFailure: Boolean? = null) = JobNode(
  GitLabJob(id = 1, name = "j", status = status, allowFailure = allowFailure),
  1L,
  "https://gitlab.example.com",
  "fp0",
)

private fun file(type: ChangeType) = ChangedFileNode("dir/file.kt", "dir/file.kt", type, "sha")

private fun commentedFile(commented: Boolean, type: ChangeType = ChangeType.NEW): ChangedFileNode {
  val paths = if (commented) setOf("dir/file.kt") else emptySet()
  val badges = MrCommentBadges("base", "head")
  badges.index = CommentIndex.Complete(CommentedSides(emptySet(), paths))
  return ChangedFileNode("dir/file.kt", "dir/file.kt", type, "sha", commentBadges = badges)
}

/** Records the icon names and color roles the provider asks for, answering with fixed SWT stand-ins. */
private class Recorder {
  val image = mockk<Image>()
  val color = mockk<Color>()
  val imageCalls = mutableListOf<String>()
  val colorCalls = mutableListOf<ChangeColorRole>()
  val errors = mutableListOf<String>()

  fun provider(failImage: Boolean = false) =
    SidebarLabelProvider(
      image = { name ->
        imageCalls += name
        if (failImage) error("broken png") else image
      },
      color = { role ->
        colorCalls += role
        color
      },
      onImageError = { name, _ -> errors += name },
    )
}

class SidebarLabelProviderTest : StringSpec({
  "text is the display text: a changed file carries its badge letter" {
    val provider = Recorder().provider()
    provider.getText(file(ChangeType.NEW)) shouldBe sidebarDisplayText(file(ChangeType.NEW))
    provider.getText(file(ChangeType.NEW)) shouldBe "dir/file.kt · A"
    provider.getText(MessageNode("hello")) shouldBe "hello"
  }

  "a non-node element keeps the old empty text, no image and no color" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getText("not a node") shouldBe ""
    provider.getText(null) shouldBe ""
    provider.getImage("not a node") shouldBe null
    provider.getForeground("not a node") shouldBe null
    recorder.imageCalls.shouldBeEmpty()
    recorder.colorCalls.shouldBeEmpty()
  }

  "a CI node's image is created from its status icon name" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getImage(job("failed", allowFailure = true)) shouldBeSameInstanceAs recorder.image
    recorder.imageCalls shouldContainExactly listOf("pipeline_warning")
  }

  "a node without a status icon has no image and never asks for one" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getImage(MessageNode("x")) shouldBe null
    provider.getImage(file(ChangeType.NEW)) shouldBe null
    recorder.imageCalls.shouldBeEmpty()
  }

  "an icon is created once per name across repaints" {
    val recorder = Recorder()
    val provider = recorder.provider()
    repeat(3) { provider.getImage(job("success")) }
    provider.getImage(job("failed"))
    recorder.imageCalls shouldContainExactly listOf("pipeline_success", "pipeline_failed")
  }

  "a failing icon is reported once, drawn without an image and not retried" {
    val recorder = Recorder()
    val provider = recorder.provider(failImage = true)
    provider.getImage(job("running")) shouldBe null
    provider.getImage(job("running")) shouldBe null
    recorder.imageCalls shouldContainExactly listOf("pipeline_running")
    recorder.errors shouldContainExactly listOf("pipeline_running")
  }

  "dispose forgets the cached icons without touching them" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getImage(job("success"))
    provider.dispose()
    provider.getImage(job("success"))
    recorder.imageCalls shouldContainExactly listOf("pipeline_success", "pipeline_success")
  }

  "an added, deleted or renamed file takes its role's color, asked on every paint" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getForeground(file(ChangeType.NEW)) shouldBeSameInstanceAs recorder.color
    provider.getForeground(file(ChangeType.DELETED)) shouldBeSameInstanceAs recorder.color
    provider.getForeground(file(ChangeType.RENAMED)) shouldBeSameInstanceAs recorder.color
    provider.getForeground(file(ChangeType.NEW))
    recorder.colorCalls shouldContainExactly
      listOf(ChangeColorRole.ADDED, ChangeColorRole.DELETED, ChangeColorRole.RENAMED, ChangeColorRole.ADDED)
  }

  "a modified file and a non-file node keep the default foreground" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getForeground(file(ChangeType.MODIFIED)) shouldBe null
    provider.getForeground(job("failed")) shouldBe null
    recorder.colorCalls.shouldBeEmpty()
  }

  "a file with a comment gets the comment badge image, created once across repaints" {
    val recorder = Recorder()
    val provider = recorder.provider()
    repeat(3) { provider.getImage(commentedFile(true)) shouldBeSameInstanceAs recorder.image }
    recorder.imageCalls shouldContainExactly listOf(COMMENT_BADGE_ICON)
  }

  "a file without a comment has no image and never asks for one" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getImage(commentedFile(false)) shouldBe null
    recorder.imageCalls.shouldBeEmpty()
  }

  "a comment badge leaves a file's text and color unchanged" {
    val recorder = Recorder()
    val provider = recorder.provider()
    provider.getText(commentedFile(true)) shouldBe "dir/file.kt · A"
    provider.getForeground(commentedFile(true)) shouldBeSameInstanceAs recorder.color
    provider.getForeground(commentedFile(true, ChangeType.MODIFIED)) shouldBe null
    recorder.colorCalls shouldContainExactly listOf(ChangeColorRole.ADDED)
  }

  "the defaults draw no image and no color" {
    val provider = SidebarLabelProvider()
    provider.getImage(job("success")) shouldBe null
    provider.getForeground(file(ChangeType.NEW)) shouldBe null
  }
})
