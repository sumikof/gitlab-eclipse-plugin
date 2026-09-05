package com.gitlab.eclipse.publish

import com.gitlab.eclipse.api.ProjectCreationService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.BranchPushService
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.mergerequests.PushOutcome
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.time.Instant

/** What the user asked to publish. [namespacePath] blank means their personal namespace. */
data class PublishRequest(
  val folder: File,
  val namespacePath: String,
  val projectPath: String,
  val visibility: String,
  val useSsh: Boolean,
)

/** Every way publishing can end. Exactly one is returned; the service never throws. */
sealed interface PublishOutcome {
  data class Published(val webUrl: String) : PublishOutcome

  /**
   * §12: the project exists but the push did not land. Nothing is undone — not the project, not
   * the remote, not the record — and [remoteUrl] IS shown to the user, deliberately: it is what
   * they need to finish by hand, and §12 requires disclosing it.
   */
  data class PushFailed(val webUrl: String, val remoteUrl: String, val rejected: Boolean) : PublishOutcome

  data class Blocked(val reason: Preflight.Stop) : PublishOutcome

  data object Busy : PublishOutcome

  /** The intent could not be persisted, so no project was created (design §9.6-6). */
  data object RecordPersistenceFailed : PublishOutcome

  /** The connection gate rejected the instance; nothing was sent. */
  data object InstanceMismatch : PublishOutcome

  /** [type] is the exception's class name — never its message, which quotes remote urls (A9). */
  data class Failed(val type: String) : PublishOutcome
}

/**
 * Publishes a folder as a new GitLab project (design F6, phases 2 and 3).
 *
 * The destructive order is fixed by the design and each step exists for a failure it prevents:
 *
 * 1. Local preparation (`init` + initial commit) happens only after the caller has confirmed.
 * 2. **The intent is persisted BEFORE `POST /projects`** (§9.6-6). Recording afterwards leaves
 *    "the project exists on GitLab but nothing here knows" whenever the response is lost, and the
 *    next run would create a second one.
 * 3. The response turns the intent into a state in **one** write (§11 R3-4).
 * 4. The push happens **outside** [GitOperationGuard], because [BranchPushService] takes the guard
 *    itself and a re-entrant acquisition of the same key returns [PushOutcome.Busy].
 *
 * A failed push is not rolled back (§12 / A16): the project and remote stay, the record stays, and
 * the next run resumes at the push.
 *
 * Blocking git and network I/O — call from a background thread. Never throws.
 */
class PublishToGitLabService(
  private val guard: GitOperationGuard = service(),
  private val records: PublishRecordStore = PublishRecordStore(),
  private val projects: ProjectCreationService = service(),
  private val pushService: BranchPushService = BranchPushService(),
  private val now: () -> Instant = Instant::now,
) {
  private val logger by lazy { logger<PublishToGitLabService>() }

  fun publish(request: PublishRequest, instanceUrl: String): PublishOutcome =
    try {
      val prepared = guard.withRepo(gitDirOf(request.folder).path) {
        prepareAndCreate(request, instanceUrl)
      } ?: return PublishOutcome.Busy
      when (prepared) {
        is Prepared.Ready -> pushOutsideGuard(prepared, request.folder, instanceUrl)
        is Prepared.Stopped -> prepared.outcome
      }
    } catch (e: Exception) {
      logger.error("Publishing failed: ${e.javaClass.name}")
      PublishOutcome.Failed(e.javaClass.name)
    }

  /**
   * A24: the project is already there. Re-add the recorded remote when it is gone, then push. No
   * `POST /projects` on this path, ever.
   */
  fun resumePush(state: PublishRecord.State, folder: File, instanceUrl: String): PublishOutcome =
    try {
      val prepared = guard.withRepo(gitDirOf(folder).path) {
        openRepository(folder).use { repo ->
          if (repo.config.getSubsections(REMOTE_SECTION).none { it == state.remoteName }) {
            // The normalised url keeps everything that addresses the project; a `.git` suffix is
            // optional for both https and scp-like remotes, so re-adding it is lossless in practice.
            addRemote(repo, state.remoteName, state.normalizedRemoteUrl)
          }
          Prepared.Ready(
            remoteName = state.remoteName,
            remoteUrl = state.normalizedRemoteUrl,
            branch = repo.branch,
            webUrl = state.projectWebUrl,
            projectId = state.projectId,
            namespaceWithPath = "${state.namespacePath}/${state.projectPath}".trimStart('/'),
          )
        }
      } ?: return PublishOutcome.Busy
      pushOutsideGuard(prepared as Prepared.Ready, folder, instanceUrl)
    } catch (e: Exception) {
      logger.error("Resuming the push failed: ${e.javaClass.name}")
      PublishOutcome.Failed(e.javaClass.name)
    }

  /** Runs inside the guard: everything up to and including adding the remote. */
  private fun prepareAndCreate(request: PublishRequest, instanceUrl: String): Prepared {
    prepareLocalRepository(request.folder)
    val key = request.folder.canonicalPath
    val namespaceWithPath = listOf(request.namespacePath, request.projectPath)
      .filter { it.isNotBlank() }
      .joinToString("/")

    // Before the POST, always (§9.6-6). If it cannot be written, do not create anything.
    val intent = PublishRecord.Intent(key, instanceUrl, request.namespacePath, request.projectPath, now())
    if (!records.put(intent)) return Prepared.Stopped(PublishOutcome.RecordPersistenceFailed)

    val accept: (String) -> Boolean = { candidate -> candidate == instanceUrl }
    val namespaceId = if (request.namespacePath.isBlank()) {
      null
    } else {
      projects.resolveNamespaceId(request.namespacePath, accept)
        ?: return Prepared.Stopped(PublishOutcome.InstanceMismatch)
    }
    val created = projects.createProject(request.projectPath, namespaceId, request.visibility, accept)
      ?: return Prepared.Stopped(PublishOutcome.InstanceMismatch)

    val remoteUrl = if (request.useSsh) created.sshUrl else created.httpUrl
    return openRepository(request.folder).use { repo ->
      val remoteName = newRemoteName(repo)
      // One write turns the intent into the state (§11 R3-4). If it fails the intent stays, which
      // is the right degradation: the next run enters the recovery protocol, finds this project
      // and can adopt it after the ownership check, instead of creating a second one.
      val stored = records.put(
        PublishRecord.State(
          repositoryRootPath = key,
          instanceUrl = instanceUrl,
          namespacePath = request.namespacePath,
          projectPath = request.projectPath,
          projectId = created.id,
          normalizedRemoteUrl = RemoteUrlNormalizer.normalize(remoteUrl),
          remoteName = remoteName,
          projectWebUrl = created.webUrl,
        ),
      )
      if (!stored) {
        logger.warn("The publish state could not be stored; the intent was kept for recovery.")
      }
      addRemote(repo, remoteName, remoteUrl)
      Prepared.Ready(remoteName, remoteUrl, repo.branch, created.webUrl, created.id, namespaceWithPath)
    }
  }

  /**
   * Phase 3's push. Outside the guard on purpose: [BranchPushService.push] acquires it for the
   * same repository, and holding it here would make every publish end in [PushOutcome.Busy].
   */
  private fun pushOutsideGuard(
    prepared: Prepared.Ready,
    folder: File,
    instanceUrl: String,
  ): PublishOutcome {
    val context = RepositoryContext(
      gitDir = gitDirOf(folder).path,
      workTree = folder.path,
      namespaceWithPath = prepared.namespaceWithPath,
      instanceUrl = instanceUrl,
      webUrl = prepared.webUrl,
      remoteName = prepared.remoteName,
      projectId = prepared.projectId.toString(),
    )
    return when (val outcome = pushService.push(context, prepared.branch)) {
      PushOutcome.Ok -> {
        records.remove(folder.canonicalPath)
        PublishOutcome.Published(prepared.webUrl)
      }
      PushOutcome.Busy -> PublishOutcome.Busy
      // §12 / A16: keep the project, the remote and the record so the next run resumes here.
      is PushOutcome.Rejected -> PublishOutcome.PushFailed(prepared.webUrl, prepared.remoteUrl, true)
      is PushOutcome.Failed -> PublishOutcome.PushFailed(prepared.webUrl, prepared.remoteUrl, false)
    }
  }

  /**
   * `init` when the folder is not a repository, and an initial commit when there is no commit yet
   * (design §9.6-5). `add(".")` honours `.gitignore`, which is why the confirmation dialog warns
   * when there is none.
   */
  private fun prepareLocalRepository(folder: File) {
    if (!gitDirOf(folder).exists()) {
      Git.init().setDirectory(folder).call().close()
    }
    openRepository(folder).use { repo ->
      if (repo.resolve(Constants.HEAD) != null) return
      Git(repo).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call()
      }
    }
  }

  /** `origin`, or `origin2`, `origin3`, … — the reference extension's numbering, which skips 1. */
  private fun newRemoteName(repo: Repository): String {
    val existing = repo.config.getSubsections(REMOTE_SECTION)
    if (DEFAULT_REMOTE_NAME !in existing) return DEFAULT_REMOTE_NAME
    var index = 2
    while ("$DEFAULT_REMOTE_NAME$index" in existing) index++
    return "$DEFAULT_REMOTE_NAME$index"
  }

  private fun addRemote(repo: Repository, name: String, url: String) {
    Git(repo).remoteAdd().setName(name).setUri(URIish(url)).call()
  }

  private fun openRepository(folder: File): Repository =
    FileRepositoryBuilder().setGitDir(gitDirOf(folder)).setMustExist(true).build()

  private fun gitDirOf(folder: File) = File(folder, Constants.DOT_GIT)

  /** Everything phase 3 needs, carried out of the guarded section. */
  private sealed interface Prepared {
    data class Ready(
      val remoteName: String,
      val remoteUrl: String,
      val branch: String,
      val webUrl: String,
      val projectId: Long,
      val namespaceWithPath: String,
    ) : Prepared

    data class Stopped(val outcome: PublishOutcome) : Prepared
  }

  private companion object {
    const val REMOTE_SECTION = "remote"
    const val DEFAULT_REMOTE_NAME = "origin"

    /** Verbatim from the reference extension (`gitlab_remote_source.ts:33`). */
    const val INITIAL_COMMIT_MESSAGE = "Initial commit"
  }
}
