package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.HeadTagReader
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
  val bridges = mockk<BridgeService>()
  val mrService = mockk<MergeRequestService>()
  val tags = mockk<HeadTagReader>()
  val api = mockk<GitLabApiClient>()
  val errors = mutableListOf<Pair<String, Throwable?>>()
  val warnings = mutableListOf<String>()

  /** The slot value as the view would hold it, and every value it took, in order. */
  var current: CurrentBranchSectionInput? = null
  var currentGitDir: String? = null
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
    bridges,
    mrService,
    tags,
    api,
    errorLog = { m, t -> errors += m to t },
    warnLog = { warnings += it },
  )

  init {
    every { resolver.resolveNonInteractive(any()) } returns context
    every { reader.read(File(GIT_DIR)) } returns branch()
    every { api.captureConnection() } returns connection
    stubMr { mr() }
    every { mrService.getMrPipelines(any(), any(), any()) } returns emptyList()
    every { bridges.getBridgesForPipeline(any(), any(), any(), any()) } returns emptyList()
    every { tags.tagsAt(any(), any()) } returns emptyList()
  }

  /** The shared MR search finds [info]'s MR and its closes_issues request answers [info]'s issues. */
  fun stubMr(info: () -> CurrentBranchInfo) {
    every { mrLookup.findMr(any(), any(), any()) } answers { info().mr }
    every { mrLookup.closesIssues(any(), any()) } answers { info().closesIssues }
  }

  /** The bare value of the settled pipeline side. */
  fun pipelineSide(): PipelineSide = (current as Resolved).pipeline!!.getOrThrow()

  /** The settled pipeline side's snapshot (fails the test when no pipeline row is shown). */
  fun shownSnapshot(): PipelineSnapshot = (pipelineSide() as PipelineShown).snapshot

  /** Every log line, full (Error Log) and soft (WARN), as text. */
  fun logLines(): List<String> = errors.map { it.first } + warnings

  fun branch(name: String? = "feature", sha: String? = "abc123") = CurrentBranch(name, null, false, null, sha)

  /** Every collaborator answers successfully with fresh-but-equal payloads on every call. */
  fun answerSuccessfully(mrInfo: () -> CurrentBranchInfo = { mr() }) {
    stubMr(mrInfo)
    every { pipelines.getLatestPipelineForRef(any(), any(), any()) } answers { PIPELINE.copy() }
    every { jobs.getJobsForPipeline(any(), any(), any(), any(), any()) } answers { JOBS.toList() }
    every { bridges.getBridgesForPipeline(any(), any(), any(), any()) } answers { BRIDGES.toList() }
  }

  suspend fun fetch(soft: Boolean = false, previous: CurrentBranchSectionInput? = current): TrackedHead? =
    fetcher.fetch(null, previous, currentGitDir, soft) { gitDir, update ->
      val next = update(current)
      current = next
      currentGitDir = gitDir
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
private val BRIDGES = listOf(GitLabBridge(id = 11, name = "trigger", status = "running", stage = "deploy"))
private const val URL = "https://gitlab.example.com"

/** The snapshot the fetcher builds for [pipeline] with default (empty) bridges. */
private fun snapshotOf(
  pipeline: GitLabPipeline = PIPELINE,
  kind: PipelineKind = PipelineKind.BRANCH,
  jobs: Result<List<GitLabJob>> = Result.success(JOBS),
  bridges: Result<List<GitLabBridge>> = Result.success(emptyList()),
) = PipelineSnapshot(pipeline, kind, jobs, bridges, URL, "fp")

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
    every { rig.mrLookup.findMr(rig.context, rig.branch(), rig.connection) } returns mr().mr
    every { rig.mrLookup.closesIssues(mr().mr!!, rig.connection) } returns emptyList()
    every { rig.pipelines.getLatestPipelineForRef(PROJECT, "feature", rig.connection) } returns PIPELINE
    every { rig.jobs.getJobsForPipeline(PROJECT, 5, any(), rig.connection, includeRetried = true) } returns JOBS

    rig.fetch() shouldBe TrackedHead(GIT_DIR, HeadKey.Branch("feature"))

    rig.applied shouldContainExactly listOf(
      Resolved(null, null),
      Resolved(Result.success(mr()), null),
      Resolved(Result.success(mr()), Result.success(PipelineShown(snapshotOf()))),
    )
    rig.errors shouldBe emptyList()
  }

  "1c. full path failures render as failed Results and log the same Error Log entries as before" {
    val rig = FetcherRig()
    val mrError = IllegalStateException("mr down")
    val pipelineError = IllegalStateException("pipeline down")
    every { rig.mrLookup.findMr(any(), any(), any()) } throws mrError
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
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } returns PIPELINE
    every { rig.jobs.getJobsForPipeline(any(), any(), any(), any(), any()) } throws jobsError

    rig.fetch()

    val pipeline = rig.shownSnapshot()
    pipeline.pipeline shouldBeSameInstanceAs PIPELINE
    pipeline.jobsResult shouldBe Result.failure(jobsError)
    rig.errors shouldContainExactly listOf("Failed to load jobs for pipeline #5." to jobsError)
  }

  "1e. full path detached HEAD with no tag settles 'no pipeline' without capturing or querying pipelines" {
    val rig = FetcherRig()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")

    rig.fetch() shouldBe TrackedHead(GIT_DIR, HeadKey.Detached("abc123"))

    rig.current shouldBe Resolved(Result.success(mr()), Result.success(NoPipeline))
    verify(exactly = 1) { rig.tags.tagsAt(File(GIT_DIR), "abc123") }
    verify(exactly = 0) { rig.api.captureConnection() }
    verify(exactly = 0) { rig.mrLookup.findMr(any(), any(), rig.connection) }
    verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
  }

  "1f. each side's update preserves the other side, whichever order they reach the UI thread" {
    val rig = FetcherRig()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null)
    val updates = mutableListOf<(CurrentBranchSectionInput?) -> CurrentBranchSectionInput>()
    rig.fetcher.fetch(null, null, null, soft = false) { _, update -> updates += update }

    val (placeholder, mrSide, pipelineSide) = updates
    val settled = mrSide(pipelineSide(placeholder(null)))
    settled shouldBe Resolved(Result.success(mr()), Result.success(NoPipeline))
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
    every { rig.mrLookup.findMr(any(), any(), any()) } throws IllegalStateException("secret mr detail")

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
    every { rig.jobs.getJobsForPipeline(any(), any(), any(), any(), any()) } throws IllegalStateException("jobs")
    rig.fetch(soft = true)
    rig.fetch(soft = true)

    rig.warnings shouldContainExactly listOf("Current-branch jobs refresh failed: java.lang.IllegalStateException")
    rig.errors shouldBe emptyList()
  }

  "5d. a failure the full path already logged is not logged again by the soft path in the same streak" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.mrLookup.findMr(any(), any(), any()) } throws IllegalStateException("down")
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

  "4c. a fetch for B whose applies were all dropped does not make a later soft fetch for B treat A's payload as shown" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()
    val other = rig.context.copy(gitDir = "/other/.git")
    every { rig.resolver.resolveNonInteractive(any()) } returns other
    every { rig.reader.read(File("/other/.git")) } returns rig.branch()
    rig.fetcher.fetch(null, null, null, soft = false) { _, _ -> } // superseded: nothing it settles is applied

    rig.fetch(soft = true)

    rig.applied.drop(3).first() shouldBe Resolved(null, null)
  }

  "1g. the full path always paints the placeholder and applies both sides, even when given an equal previous" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    rig.fetch()

    rig.fetch(soft = false)

    rig.applied.size shouldBe 6
    rig.applied[3] shouldBe Resolved(null, null)
  }

  "8a. cancelled during the pipeline GET: no jobs fetch, no pipeline-side apply, nothing logged, fetch ends cancelled" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    lateinit var fetchJob: Job
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } answers {
      fetchJob.cancel()
      PIPELINE
    }

    coroutineScope {
      val fetch = async { rig.fetch() }
      fetchJob = fetch
      shouldThrow<CancellationException> { fetch.await() }
    }

    verify(exactly = 0) { rig.jobs.getJobsForPipeline(any(), any(), any(), any(), any()) }
    verify(exactly = 0) { rig.bridges.getBridgesForPipeline(any(), any(), any(), any()) }
    rig.applied shouldBe listOf(Resolved(null, null), Resolved(Result.success(mr()), null))
    rig.errors shouldBe emptyList()
    rig.warnings shouldBe emptyList()
  }

  "8b. the jobs fetch's isActive turns false once the fetch is cancelled" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    lateinit var fetchJob: Job
    val isActive = slot<() -> Boolean>()
    val seen = mutableListOf<Boolean>()
    every { rig.jobs.getJobsForPipeline(any(), any(), capture(isActive), any(), any()) } answers {
      seen += isActive.captured()
      fetchJob.cancel()
      seen += isActive.captured()
      JOBS
    }

    coroutineScope {
      val fetch = async { rig.fetch() }
      fetchJob = fetch
      runCatching { fetch.await() }
    }

    seen shouldBe listOf(true, false)
    // Cancelled during the jobs GET: the bridges GET is never started.
    verify(exactly = 0) { rig.bridges.getBridgesForPipeline(any(), any(), any(), any()) }
  }

  "8c. a CancellationException from the jobs fetch is not rendered or logged as a failure" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.jobs.getJobsForPipeline(any(), any(), any(), any(), any()) } throws CancellationException("between pages")

    runCatching { rig.fetch() }

    rig.applied shouldBe listOf(Resolved(null, null), Resolved(Result.success(mr()), null))
    rig.errors shouldBe emptyList()
    rig.warnings shouldBe emptyList()
  }

  "8d. a CancellationException from the shared MR search paints and logs nothing on EITHER side, on either path" {
    listOf(false, true).forEach { soft ->
      val rig = FetcherRig()
      rig.answerSuccessfully()
      every { rig.mrLookup.findMr(any(), any(), any()) } throws CancellationException("cancelled")
      every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } returns null

      runCatching { rig.fetch(soft = soft) }

      // The pipeline side awaits the same search, so it settles nothing either (CI-05 §4.1).
      rig.applied shouldBe listOf(Resolved(null, null))
      verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
      rig.errors shouldBe emptyList()
      rig.warnings shouldBe emptyList()
    }
  }

  // ---- CI-05 / CI-06 (design §4.1-§4.3, §4.7): MR → pipeline selection, tag pipelines, bridges ----

  "9a. the MR search runs exactly once per fetch; both sides consume it, all pinned to the one captured connection" {
    val rig = FetcherRig()
    rig.answerSuccessfully()

    rig.fetch()

    verify(exactly = 1) { rig.api.captureConnection() }
    verify(exactly = 1) { rig.mrLookup.findMr(rig.context, rig.branch(), rig.connection) }
    verify(exactly = 1) { rig.mrLookup.closesIssues(mr().mr!!, rig.connection) }
    // MR pipelines are listed on the MR's own project (mr.projectId = 1, iid 7), not context.projectId.
    verify(exactly = 1) { rig.mrService.getMrPipelines(1, 7, rig.connection) }
    rig.shownSnapshot() shouldBe snapshotOf(bridges = Result.success(BRIDGES))
  }

  "9b. an MR search failure renders the MR failure row AND still shows the branch pipeline" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    val mrError = IllegalStateException("mr down")
    every { rig.mrLookup.findMr(any(), any(), any()) } throws mrError

    rig.fetch()

    (rig.current as Resolved).mr shouldBe Result.failure(mrError)
    rig.shownSnapshot() shouldBe snapshotOf(bridges = Result.success(BRIDGES))
    verify(exactly = 0) { rig.mrService.getMrPipelines(any(), any(), any()) }
    rig.errors.map { it.first } shouldContainExactly listOf("Failed to load current-branch merge request.")
  }

  "9c. an MR-pipelines 403 falls back to the branch pipeline and logs the mr-pipelines stage only" {
    listOf(false, true).forEach { soft ->
      val rig = FetcherRig()
      rig.answerSuccessfully()
      val forbidden = GitLabApiException(403, "forbidden body")
      every { rig.mrService.getMrPipelines(any(), any(), any()) } throws forbidden

      rig.fetch(soft = soft)

      rig.shownSnapshot() shouldBe snapshotOf(bridges = Result.success(BRIDGES))
      (rig.current as Resolved).mr shouldBe Result.success(mr())
      if (soft) {
        rig.warnings shouldContainExactly listOf(
          "Current-branch mr-pipelines refresh failed: com.gitlab.eclipse.api.GitLabApiException",
        )
      } else {
        rig.errors shouldContainExactly listOf("Failed to load merge request pipelines." to forbidden)
      }
    }
  }

  "9d. a branch-pipeline failure with an MR pipeline shows the MR pipeline (kind MERGE_REQUEST) and logs the branch failure" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    val mrPipeline = GitLabPipeline(id = 4, status = "success", projectId = 1)
    val branchError = IllegalStateException("branch down")
    every { rig.mrService.getMrPipelines(any(), any(), any()) } returns listOf(mrPipeline)
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws branchError

    rig.fetch()

    rig.shownSnapshot() shouldBe
      snapshotOf(pipeline = mrPipeline, kind = PipelineKind.MERGE_REQUEST, bridges = Result.success(BRIDGES))
    rig.errors shouldContainExactly listOf("Failed to load current-branch pipeline." to branchError)
  }

  "9e. a newer MR pipeline wins over the branch pipeline (compared by id)" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    val mrPipeline = GitLabPipeline(id = 6, status = "success", projectId = 1)
    every { rig.mrService.getMrPipelines(any(), any(), any()) } returns listOf(mrPipeline)

    rig.fetch()

    rig.shownSnapshot().pipeline shouldBe mrPipeline
    rig.shownSnapshot().kind shouldBe PipelineKind.MERGE_REQUEST
  }

  "9f. fork: jobs and bridges are requested on the SELECTED pipeline's project, never context or MR project" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    // The MR targets project 1, the repository is PROJECT, but the selected pipeline ran in project 99.
    val forkPipeline = GitLabPipeline(id = 50, status = "running", projectId = 99)
    every { rig.mrService.getMrPipelines(any(), any(), any()) } returns listOf(forkPipeline)

    rig.fetch()

    verify(exactly = 1) { rig.jobs.getJobsForPipeline("99", 50, any(), rig.connection, includeRetried = true) }
    verify(exactly = 1) { rig.bridges.getBridgesForPipeline("99", 50, any(), rig.connection) }
    verify(exactly = 0) { rig.jobs.getJobsForPipeline(PROJECT, any(), any(), any(), any()) }
    verify(exactly = 0) { rig.jobs.getJobsForPipeline("1", any(), any(), any(), any()) }
  }

  "9g. a branch pipeline without projectId (old GitLab) falls back to context.projectId for jobs and bridges" {
    val rig = FetcherRig()
    rig.answerSuccessfully()

    rig.fetch()

    verify(exactly = 1) { rig.jobs.getJobsForPipeline(PROJECT, 5, any(), rig.connection, includeRetried = true) }
    verify(exactly = 1) { rig.bridges.getBridgesForPipeline(PROJECT, 5, any(), rig.connection) }
  }

  "9h. connection capture failure: the MR side runs unpinned and renders; the pipeline side fails with that exception" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    val captureError = IllegalStateException("unstable")
    every { rig.api.captureConnection() } throws captureError

    rig.fetch()

    rig.current shouldBe Resolved(Result.success(mr()), Result.failure(captureError))
    verify(exactly = 1) { rig.mrLookup.findMr(rig.context, rig.branch(), null) }
    verify(exactly = 1) { rig.mrLookup.closesIssues(mr().mr!!, null) }
    verify(exactly = 0) { rig.mrService.getMrPipelines(any(), any(), any()) }
    verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
    rig.errors shouldContainExactly listOf("Failed to load current-branch pipeline." to captureError)
  }

  "9i. a bridges failure keeps the pipeline row with its jobs, and is its own logged stage" {
    listOf(false, true).forEach { soft ->
      val rig = FetcherRig()
      rig.answerSuccessfully()
      val bridgesError = GitLabApiException(403, "body")
      every { rig.bridges.getBridgesForPipeline(any(), any(), any(), any()) } throws bridgesError

      rig.fetch(soft = soft)
      rig.fetch(soft = true)

      rig.shownSnapshot().jobsResult shouldBe Result.success(JOBS)
      rig.shownSnapshot().bridgesResult shouldBe Result.failure(bridgesError)
      if (soft) {
        rig.warnings shouldContainExactly listOf(
          "Current-branch bridges refresh failed: com.gitlab.eclipse.api.GitLabApiException",
        )
      } else {
        rig.errors shouldContainExactly listOf("Failed to load bridges for pipeline #5." to bridgesError)
        rig.warnings shouldBe emptyList()
      }
    }
  }

  "10a. detached HEAD with two or more tags: AmbiguousTags, no capture and no pipeline GET; an equal soft refresh applies nothing" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")
    every { rig.tags.tagsAt(File(GIT_DIR), "abc123") } returns listOf("v1", "v1-rc")

    rig.fetch()
    rig.pipelineSide() shouldBe AmbiguousTags
    val appliedBefore = rig.applied.size
    rig.fetch(soft = true)

    rig.applied.size shouldBe appliedBefore
    verify(exactly = 0) { rig.api.captureConnection() }
    verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
  }

  "10b. detached HEAD with exactly one tag: the tag pipeline on context.projectId, kind TAG, with jobs and bridges" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")
    every { rig.tags.tagsAt(File(GIT_DIR), "abc123") } returns listOf("v1.0")
    val tagPipeline = GitLabPipeline(id = 8, status = "success", projectId = 42)
    every { rig.pipelines.getLatestPipelineForRef(PROJECT, "v1.0", rig.connection) } returns tagPipeline

    rig.fetch()

    rig.shownSnapshot() shouldBe
      snapshotOf(pipeline = tagPipeline, kind = PipelineKind.TAG, bridges = Result.success(BRIDGES))
    verify(exactly = 1) { rig.api.captureConnection() }
    verify(exactly = 1) { rig.jobs.getJobsForPipeline("42", 8, any(), rig.connection, includeRetried = true) }
    verify(exactly = 0) { rig.mrService.getMrPipelines(any(), any(), any()) }
  }

  "10c. a failed tag-pipeline GET renders the failure row and logs the tag stage" {
    listOf(false, true).forEach { soft ->
      val rig = FetcherRig()
      rig.answerSuccessfully()
      every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")
      every { rig.tags.tagsAt(any(), any()) } returns listOf("v1.0")
      val tagError = IllegalStateException("tag down")
      every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws tagError

      rig.fetch(soft = soft)

      (rig.current as Resolved).pipeline shouldBe Result.failure(tagError)
      if (soft) {
        rig.warnings shouldContainExactly listOf("Current-branch tag refresh failed: java.lang.IllegalStateException")
      } else {
        rig.errors shouldContainExactly listOf("Failed to load the tag pipeline." to tagError)
      }
    }
  }

  "10d. no tag pipeline for the single tag settles NoPipeline" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = null, sha = "abc123")
    every { rig.tags.tagsAt(any(), any()) } returns listOf("v1.0")
    every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } returns null

    rig.fetch()

    rig.pipelineSide() shouldBe NoPipeline
  }

  "11a. cancelled during the shared MR search: neither side applies and nothing further is requested" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    lateinit var fetchJob: Job
    every { rig.mrLookup.findMr(any(), any(), any()) } answers {
      fetchJob.cancel()
      mr().mr
    }

    coroutineScope {
      val fetch = async { rig.fetch() }
      fetchJob = fetch
      shouldThrow<CancellationException> { fetch.await() }
    }

    rig.applied shouldBe listOf(Resolved(null, null))
    verify(exactly = 0) { rig.mrLookup.closesIssues(any(), any()) }
    verify(exactly = 0) { rig.mrService.getMrPipelines(any(), any(), any()) }
    verify(exactly = 0) { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) }
    rig.logLines() shouldBe emptyList()
  }

  "11b. soft refresh with an unchanged MR-kind snapshot (jobs and bridges included) applies nothing" {
    val rig = FetcherRig()
    rig.answerSuccessfully()
    every { rig.mrService.getMrPipelines(any(), any(), any()) } answers
      { listOf(GitLabPipeline(id = 6, status = "running", projectId = 1)) }
    rig.fetch()
    val before = rig.current as Resolved
    val appliedBefore = rig.applied.size

    rig.fetch(soft = true)

    rig.applied.size shouldBe appliedBefore
    rig.current shouldBeSameInstanceAs before
  }

  "12. log lines never carry the branch, the ref, the tag, or a URL — full and soft, every stage failing" {
    val branchName = "secret-branch"
    val tagName = "secret-tag"
    listOf(false, true).forEach { soft ->
      // Branch path: every stage of the branch pipeline side fails.
      val rig = FetcherRig()
      every { rig.reader.read(File(GIT_DIR)) } returns rig.branch(name = branchName)
      val boom = { IllegalStateException("$branchName $URL") }
      every { rig.mrLookup.findMr(any(), any(), any()) } returns mr().mr
      every { rig.mrLookup.closesIssues(any(), any()) } throws boom()
      every { rig.mrService.getMrPipelines(any(), any(), any()) } throws boom()
      every { rig.pipelines.getLatestPipelineForRef(any(), any(), any()) } returns PIPELINE
      every { rig.jobs.getJobsForPipeline(any(), any(), any(), any(), any()) } throws boom()
      every { rig.bridges.getBridgesForPipeline(any(), any(), any(), any()) } throws boom()
      rig.fetch(soft = soft)

      // Tag path: the tag pipeline GET fails.
      val tagRig = FetcherRig()
      every { tagRig.reader.read(File(GIT_DIR)) } returns tagRig.branch(name = null, sha = "abc123")
      every { tagRig.tags.tagsAt(any(), any()) } returns listOf(tagName)
      every { tagRig.pipelines.getLatestPipelineForRef(any(), any(), any()) } throws boom()
      tagRig.fetch(soft = soft)

      val lines = rig.logLines() + tagRig.logLines()
      lines.size shouldBe 5 // mr, mr-pipelines, jobs, bridges + tag
      lines.forEach { line ->
        line shouldNotContain branchName
        line shouldNotContain tagName
        line shouldNotContain "gitlab.example.com"
        line shouldNotContain PROJECT
        line shouldNotContain "grp/proj"
      }
    }
  }
})
