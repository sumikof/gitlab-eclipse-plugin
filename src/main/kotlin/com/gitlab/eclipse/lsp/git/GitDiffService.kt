package com.gitlab.eclipse.lsp.git

import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

class GitDiffService {
  private val logger by lazy { logger<GitDiffService>() }

  fun getDiff(repositoryUri: String, revision: String? = "HEAD"): String? {
    val repository = try {
      FileRepositoryBuilder()
        .setGitDir(File(URI("$repositoryUri/.git")))
        .setMustExist(true)
        .build()
    } catch (e: RepositoryNotFoundException) {
      logger.warn("Could not open repository $repositoryUri.", e)
      return null
    }

    val git = Git(repository)
    val reader = repository.newObjectReader()

    val revisionTree = RevWalk(repository).let { walk ->
      val commit = walk.parseCommit(repository.resolve(revision))
      val tree = walk.parseTree(commit.tree.id)

      CanonicalTreeParser()
        .apply { reset(reader, tree.id) }
        .also { walk.dispose() }
    }

    val localTree = FileTreeIterator(repository)

    val stream = ByteArrayOutputStream()
    git.diff().setOldTree(revisionTree).setNewTree(localTree).setOutputStream(stream).call()

    return String(stream.toByteArray())
  }
}
