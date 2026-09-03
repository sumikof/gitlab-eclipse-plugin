package com.gitlab.eclipse.clone

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.IProgressMonitor
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Mixed case and a percent-encoded segment on purpose: the entry inspection compares `origin` by
 * string equality, so any normalisation between the inspection and the clone would silently turn
 * the (a0) adoption check into a no-op on a real server. Both calls must see this exact spelling.
 */
private const val CLONE_URL = "https://GitLab.Example.com/group/sub%20group/project.wiki.git"

/** The instance url the caller's lookup returned; never re-read from configuration by the flow. */
private const val INSTANCE_URL = "https://GitLab.Example.com"

/** Never created on disk: the inspector and the cloner are mocks, so only the path is ever read. */
private val destinationDir = File("/tmp/clone-flow-spec/project.wiki")

/**
 * The flow with all six collaborators replaced. The three UI hops and the import are recording
 * lambdas so "how many times" is asserted directly, and the inspector and the cloner are STRICT
 * mocks: an unstubbed call throws instead of quietly answering, which is what makes the
 * `verify(exactly = 0)` rows real rather than vacuous.
 *
 * No SWT is touched here — that is the whole point of the extraction under test.
 */
private class FlowFixture {
  val inspector = mockk<CloneDestinationInspector>()
  val cloner = mockk<RepositoryCloner>()
  val monitor = mockk<IProgressMonitor>(relaxed = true)

  /** What the destination prompt answers. Null is the user cancelling it. */
  var destination: File? = destinationDir

  /** When set, the monitor only reports cancellation once the prompt has returned. */
  var cancelOnPrompt = false

  /** The answer to both consent questions the flow can ask. */
  var confirmAnswer = true

  /** Stands in for `ClonedProjectImporter.import`; may also throw. */
  var importOutcome: (File, RepositorySource) -> CloneOutcome =
    { directory, source -> CloneOutcome.Imported(directory, directory.name, source) }

  val promptArgs = mutableListOf<String>()
  val confirmArgs = mutableListOf<String>()
  val notifications = mutableListOf<String>()
  val importArgs = mutableListOf<Pair<File, RepositorySource>>()

  private var cancelled = false

  init {
    every { monitor.isCanceled } answers { cancelled }
  }

  fun verdict(verdict: CloneDestinationInspector.Verdict) {
    every { inspector.inspect(any(), any()) } returns verdict
  }

  fun cloneOutcome(outcome: RepositoryCloner.Outcome) {
    every { cloner.clone(any(), any(), any(), any()) } returns outcome
  }

  fun run(cloneUrl: String = CLONE_URL): CloneFlow.Result = flow().run(cloneUrl, INSTANCE_URL, monitor)

  private fun flow(): CloneFlow = CloneFlow(
    prompt = { suggested ->
      promptArgs += suggested
      if (cancelOnPrompt) {
        cancelled = true
      }
      destination
    },
    confirm = { question ->
      confirmArgs += question
      confirmAnswer
    },
    notify = { message -> notifications += message },
    inspector = inspector,
    cloner = cloner,
    importProject = { directory, source ->
      importArgs += directory to source
      importOutcome(directory, source)
    },
  )
}

/** Runs step 4 alone: the prompt cancels, so the suggestion it was offered is the observation. */
private fun suggestedFor(cloneUrl: String): String {
  val fixture = FlowFixture()
  fixture.destination = null
  fixture.run(cloneUrl) shouldBe CloneFlow.Result.CANCELLED
  return fixture.promptArgs.single()
}

/**
 * Steps 4-8 of the shared clone flow, headless. Until this spec existed the ordering, the url
 * identity and the notification choices below could only be checked by clicking through a real
 * Eclipse — and F5 shipped without that ever being done.
 */
class CloneFlowTest : StringSpec({
  // CloneFlow logs on the contained-import-failure path, which goes through Platform.getLog and
  // needs an OSGi bundle; this extension stubs it the way every headless spec here does.
  extensions(LoggingKotestExtension)

  "cancelling the destination prompt ends the flow with zero side effects" {
    val fixture = FlowFixture()
    fixture.destination = null

    fixture.run() shouldBe CloneFlow.Result.CANCELLED

    verify(exactly = 0) { fixture.inspector.inspect(any(), any()) }
    verify(exactly = 0) { fixture.inspector.hasLeftovers(any()) }
    verify(exactly = 0) { fixture.cloner.clone(any(), any(), any(), any()) }
    fixture.importArgs.shouldBeEmpty()
    fixture.notifications.shouldBeEmpty()
  }

  "a monitor cancelled while the prompt was open stops before the inspection and the clone" {
    val fixture = FlowFixture()
    fixture.cancelOnPrompt = true

    fixture.run() shouldBe CloneFlow.Result.CANCELLED

    // The prompt still ran: the cancellation check belongs after step 4, not instead of it.
    fixture.promptArgs.size shouldBe 1
    verify(exactly = 0) { fixture.inspector.inspect(any(), any()) }
    verify(exactly = 0) { fixture.cloner.clone(any(), any(), any(), any()) }
    fixture.importArgs.shouldBeEmpty()
    fixture.notifications.shouldBeEmpty()
  }

  "an occupied destination is reported, with nothing cloned and nothing imported" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Occupied)

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.notifications shouldContainExactly listOf(CloneMessages.occupied)
    verify(exactly = 0) { fixture.cloner.clone(any(), any(), any(), any()) }
    fixture.importArgs.shouldBeEmpty()
  }

  "an adopted same-repository destination is imported without cloning again" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.SameRepository)
    fixture.confirmAnswer = true

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.confirmArgs shouldContainExactly listOf(CloneMessages.adoptConsent)
    fixture.importArgs shouldContainExactly listOf(destinationDir to RepositorySource.ADOPTED_EXISTING)
    verify(exactly = 0) { fixture.cloner.clone(any(), any(), any(), any()) }
  }

  "declining adoption reports the occupied wording and imports nothing" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.SameRepository)
    fixture.confirmAnswer = false

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.notifications shouldContainExactly listOf(CloneMessages.occupied)
    fixture.importArgs.shouldBeEmpty()
    verify(exactly = 0) { fixture.cloner.clone(any(), any(), any(), any()) }
  }

  "a succeeded clone imports as CLONED_NOW and notifies with the composed import wording" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Succeeded)
    val imported = CloneOutcome.Imported(destinationDir, "project.wiki", RepositorySource.CLONED_NOW)
    fixture.importOutcome = { _, _ -> imported }

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.importArgs shouldContainExactly listOf(destinationDir to RepositorySource.CLONED_NOW)
    val expected = CloneNotifications.importNotification(imported, RepositorySource.CLONED_NOW, destinationDir)
    fixture.notifications shouldContainExactly listOf(expected)
  }

  "a busy clone reports the busy wording, imports nothing and still completes" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Busy)

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.notifications shouldContainExactly listOf(CloneMessages.busy)
    fixture.importArgs.shouldBeEmpty()
  }

  "a cancelled clone with leftovers names the destination and returns CANCELLED" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Cancelled)
    every { fixture.inspector.hasLeftovers(destinationDir) } returns true

    fixture.run() shouldBe CloneFlow.Result.CANCELLED

    fixture.notifications shouldContainExactly listOf(CloneMessages.cloneIncompleteLeftovers(destinationDir))
    fixture.importArgs.shouldBeEmpty()
  }

  "a failed clone that left nothing behind says so and still returns COMPLETED" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Failed("org.eclipse.jgit.api.errors.TransportException"))
    every { fixture.inspector.hasLeftovers(destinationDir) } returns false

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    fixture.notifications shouldContainExactly listOf(CloneMessages.cloneIncompleteNothingLeft)
    fixture.importArgs.shouldBeEmpty()
  }

  "hands the very same url string to the entry inspection and to the clone" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Succeeded)

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    verify(exactly = 1) { fixture.inspector.inspect(destinationDir, CLONE_URL) }
    verify(exactly = 1) { fixture.cloner.clone(CLONE_URL, destinationDir, any(), any()) }
  }

  "passes the caller's instance url and this job's monitor straight through to the clone" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Succeeded)

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    verify(exactly = 1) { fixture.cloner.clone(CLONE_URL, destinationDir, INSTANCE_URL, fixture.monitor) }
  }

  "an import that throws is contained: the import-failed wording, and the flow still completes" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Succeeded)
    fixture.importOutcome = { _, _ -> throw IllegalStateException("broken .project name") }

    fixture.run() shouldBe CloneFlow.Result.COMPLETED

    val expected =
      CloneMessages.importSkipped(ImportSkipReason.IMPORT_FAILED, RepositorySource.CLONED_NOW, destinationDir.name)
    fixture.notifications shouldContainExactly listOf(expected)
  }

  "a CancellationException from the import is rethrown instead of becoming a notification" {
    val fixture = FlowFixture()
    fixture.verdict(CloneDestinationInspector.Verdict.Empty)
    fixture.cloneOutcome(RepositoryCloner.Outcome.Succeeded)
    fixture.importOutcome = { _, _ -> throw CancellationException("job cancelled") }

    shouldThrow<CancellationException> { fixture.run() }

    fixture.notifications.shouldBeEmpty()
  }

  "derives the suggested folder name from the clone url's last segment" {
    suggestedFor("https://h/g/p.wiki.git") shouldBe "p.wiki"
    suggestedFor("https://h/g/p.git") shouldBe "p"
    suggestedFor("https://h/g/p.wiki.git/") shouldBe "p.wiki"
    suggestedFor("https://h/g/p.git/") shouldBe "p"
  }
})
