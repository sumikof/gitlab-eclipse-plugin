package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

private const val GIT_DIR = "/repo/.git"
private const val PROJECT = "grp%2Fproj"

/** The mocked collaborators plus a recorder that applies each update to a simulated slot, inline. */
private class FetcherRig {
  val resolver = mockk<RepositoryContextResolver>()
  val reader = mockk<CurrentBranchGitReader>()
  val mrLookup = mockk<CurrentBranchMrLookup>()
  val pipelines = mockk<PipelineService>()
  val jobs = mockk<JobService>()
  val api = mockk<GitLabApiClient>()
  val errors = mutableListOf<Pair<String, Throwable?>>()
  val warnings = mutableListOf<String>()

  /** The slot value as the view would hold it, and every value it took, in order. */
  var current: CurrentBranchSectionInput? = null
  val applied = mutableListOf<CurrentBranchSectionInput>()

  val connection = ConnectionSnapshot("https://gitlab.example.com", "tok", "fp", 1L)
  val context = RepositoryContext(
    gitDir = GIT_DIR,
    workTree = "/repo",
    namespaceWithPath = "grp/proj",
    instanceUrl = "https://gitlab.example.com",
    webUrl = "https://gitlab.example.com/grp/proj",
    remoteName = "origin",
    projectId = PROJECT,
  )

  val fetcher = CurrentBranchSectionFetcher(
    resolver,
    reader,
    mrLookup,
    pipelines,
    jobs,
    api,
    errorLog = { m, t -> errors += m to t },
    warnLog = { warnings += it },
  )

  init {
    every { resolver.resolveNonInteractive(any()) } returns context
    every { reader.read(File(GIT_DIR)) } returns branch()
    every { api.captureConnection() } returns connection
  }

  fun branch(name: String? = "feature", sha: String? = "abc123") = CurrentBranch(name, null, false, null, sha)

  /** Every collaborator answers successfully with fresh-but-equal payloads on every call. */
  fun answerSuccessfully(mrInfo: () -> CurrentBranchInfo = { mr() }) {
    every { mrLookup.lookup(any(), any()) } answers { mrInfo() }
    every { pipelines.getLatestPipelineForRef(any(), any(), any()) } answers { PIPELINE.copy() }
    every { jobs.getJobsForPipeline(any(), any(), any(), any()) } answers { JOBS.toList() }
  }

  suspend fun fetch(soft: Boolean = false, previous: CurrentBranchSectionInput? = current): TrackedHead? =
    fetcher.fetch(null, previous, soft) { update ->
      val next = update(current)
      current = next
      applied += next
    }
}

private fun mr(iid: Long = 7, updatedAt: String = "t1") = CurrentBranchInfo(
  GitLabMergeRequest(
    id = iid,
    iid = iid,
    title = "m$iid",
    projectId = 1,
    webUrl = "https://gitlab.example.com/grp/proj/-/merge_requests/$iid",
    state = "opened",
    updatedAt = updatedAt,
  ),
  emptyList(),
)

private val PIPELINE = GitLabPipeline(id = 5, status = "running", updatedAt = "t1")
private val JOBS = listOf(GitLabJob(id = 9, name = "build", status = "running", stage = "build"))

class CurrentBranchSectionFetcherTest : StringSpec({
  extensions(LoggingKotestExtension)
  "1a. no repository settles NoRepository only and tracks nothing" {
    val rig = FetcherRig()
    every { rig.resolver.resolveNonInteractive(any()) } returns null
    rig.fetch() shouldBe null
    rig.applied shouldContainExactly listOf(NoRepository)
    verify(exactly = 0) { rig.reader.read(any()) }
  }

  "1b. full path: placeholder, then the MR side, then the pipeline side; returns the tracked head" {
    val rig = FetcherRig()
    every { rig.mrLookup.lookup(rig.context, rig.branch()) } returns mr()
    every { rig.pipelines.getLatestPipelineForRef(PROJECT, "feature", rig.connection) } returns PIPELINE
    every { rig.jobs.getJobsForPipeline(PROJECT, 5, any(), rig.connection) } returns JOBS

    rig.fetch() shouldBe TrackedHead(GIT_DIR, HeadKey.Branch("feature"))

    val snapshot = PipelineSnapshot(PIPELINE, Result.success(JOBS), "https://gitlab.example.com", "fp")
    rig.applied shouldContainExactly listOf(
      Resolved(null, null),
      Resolved(Result.success(mr()), null),
      Resolved(Result.success(mr()), Result.success(snapshot)),
    )
    rig.errors shouldBe emptyList()
  }

  "1c. full path failures render as failed Results and log the same Error Log entries as before" {
    val rig = FetcherRig()
    val mrError = IllegalStateException("mr down")
    val pipelineError = IllegalStateException("pipeline down")
    every { rig.mrLookup.lookup(any(), any()) } throws mrError
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws pipelineError

    rig.fetch()

    rig.current shouldBe Resolved(Result.failure(mrError), Result.failure(pipelineError))
    rig.errors shouldContainExactly listOf(
      "Failed to load current-branch merge request." to mrError,
      "Failed to load current-branch pipeline." to pipelineError,
    )
  }

  "1d. full path jobs failure keeps the pipeline row and logs the jobs entry" {
    val rig = FetcherRig()
    val jobsError = IllegalStateException("jobs down")
    every { rig.mrLookup.lookup(any(), any()) } returns mr()
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } returns PIPELINE
    every { rig.jobs.getJobsForPipeline(any(), any(), any(), any()) } throws jobsError

    rig.fetch()

    val pipeline = (rig.current as Resolved).pipeline!!.getOrThrow()!!
    pipeline.pipeline shouldBeSameInstanceAs PIPELINE
    pipeline.jobsResult shouldBe Result.failure(jobsError)
    rig.errors shouldContainExactly listOf("Failed to load jobs for pipeline #5." to jobsError)
  }

  "1e. full path detached HEAD settles 'no pipeline' without querying pipelines" {
    val rig = FetcherRig()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")
    every { rig.mrLookup.lookup(any(), any()) } returns mr()

    rig.fetch() shouldBe TrackedHead(GIT_DIR, HeadKey.Detached("abc123"))

    rig.current shouldBe Resolved(Result.success(mr()), Result.success(null))
    verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
  }

  "1f. each side's update preserves the other side, whichever order they reach the UI thread" {
    val rig = FetcherRig()
    every { rig.mrLookup.lookup(any(), any()) } returns mr()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null)
    val updates = mutableListOf<(CurrentBranchSectionInput?) -> CurrentBranchSectionInput>()
    rig.fetcher.fetch(null, null, soft = false) { updates += it }

    val (placeholder, mrSide, pipelineSide) = updates
    val settled = mrSide(pipelineSide(placeholder(null)))
    settled shouldBe Resolved(Result.success(mr()), Result.success(null))
  }

  "2. soft with unchanged data keeps both previous Result instances and applies nothing" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()
    val before = rig.current as Resolved
    val appliedBefore = rig.applied.size

    rig.fetch(soft = true) shouldBe TrackedHead(GIT_DIR, HeadKey.Branch("feature"))

    rig.applied.size shouldBe appliedBefore
    rig.current shouldBeSameInstanceAs before
  }

  "3. soft with a changed MR applies a new MR Result only; the pipeline Result instance is kept" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()
    val before = rig.current as Resolved
    rig.answerSuccessfully { mr(updatedAt = "t2") }

    rig.fetch(soft = true)

    rig.applied.size shouldBe 4
    val after = rig.current as Resolved
    after.mr shouldBe Result.success(mr(updatedAt = "t2"))
    after.pipeline shouldBeSameInstanceAs before.pipeline
  }

  "4a. soft after NoRepository paints the placeholder first" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.resolver.resolveNonInteractive(any()) } returns null
    rig.fetch()
    every { rig.resolver.resolveNonInteractive(any()) } returns rig.context

    rig.fetch(soft = true)

    rig.applied[1] shouldBe Resolved(null, null)
    rig.applied.size shouldBe 4
  }

  "4b. soft on a different repository paints the placeholder and both sides, even when equal" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()
    val other = rig.context.copy(gitDir = "/other/.git")
    every { rig.resolver.resolveNonInteractive(any()) } returns other
    every { rig.reader.read(File("/other/.git")) } returns rig.branch()

    rig.fetch(soft = true) shouldBe TrackedHead("/other/.git", HeadKey.Branch("feature"))

    rig.applied.drop(3).first() shouldBe Resolved(null, null)
    rig.applied.size shouldBe 6
  }

  "5a. soft failures log one WARN line per failure streak, class name only, and nothing to the Error Log" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()
    every { rig.mrLookup.lookup(any(), any()) } throws IllegalStateException("secret mr detail")

    rig.fetch(soft = true)
    rig.fetch(soft = true)

    rig.warnings shouldContainExactly listOf("Current-branch mr refresh failed: java.lang.IllegalStateException")
    rig.errors shouldBe emptyList()
  }

  "5b. a success ends the streak, so the next soft failure is logged again" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws IllegalArgumentException("x")
    rig.fetch(soft = true)
    rig.answerSuccessfully()
    rig.fetch(soft = true)
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws IllegalArgumentException("y")
    rig.fetch(soft = true)

    rig.warnings shouldContainExactly listOf(
      "Current-branch pipeline refresh failed: java.lang.IllegalArgumentException",
      "Current-branch pipeline refresh failed: java.lang.IllegalArgumentException",
    )
  }

  "5c. a soft jobs failure is its own stage, logged once per streak" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.jobs.getJobsForPipeline(any(), any(), any(), any()) } throws IllegalStateException("jobs")
    rig.fetch(soft = true)
    rig.fetch(soft = true)

    rig.warnings shouldContainExactly listOf("Current-branch jobs refresh failed: java.lang.IllegalStateException")
    rig.errors shouldBe emptyList()
  }

  "5d. a failure the full path already logged is not logged again by the soft path in the same streak" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.mrLookup.lookup(any(), any()) } throws IllegalStateException("down")
    rig.fetch()
    rig.fetch(soft = true)

    rig.errors.map { it.first } shouldContainExactly listOf("Failed to load current-branch merge request.")
    rig.warnings shouldBe emptyList()
  }

  "6a. HeadKey: a branch name wins, else the detached SHA, else Unavailable" {
    headKeyOf(CurrentBranch("main", null, false, null, "abc")) shouldBe HeadKey.Branch("main")
    headKeyOf(CurrentBranch(null, null, false, null, "abc")) shouldBe HeadKey.Detached("abc")
    headKeyOf(CurrentBranch(null, null, false, null, null)) shouldBe HeadKey.Unavailable
  }

  "6b. HeadKey from real repositories: unborn is its branch, detached is its SHA, a deleted repository is Unavailable" {
    val dir = Files.createTempDirectory("ci04-head").toFile()
    try {
      Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
        val gitDir = File(dir, ".git")
        headKeyOf(CurrentBranchGitReader().read(gitDir)) shouldBe HeadKey.Branch("main")

        File(dir, "a.txt").writeText("a")
        git.add().addFilepattern(".").call()
        val sha = git.commit().setMessage("c").setAuthor("t", "t@e").setCommitter("t", "t@e").call().name
        git.checkout().setName(sha).call()
        headKeyOf(CurrentBranchGitReader().read(gitDir)) shouldBe HeadKey.Detached(sha)
      }
      dir.deleteRecursively()
      headKeyOf(CurrentBranchGitReader().read(File(dir, ".git"))) shouldBe HeadKey.Unavailable
    } finally {
      dir.deleteRecursively()
    }
  }
})
