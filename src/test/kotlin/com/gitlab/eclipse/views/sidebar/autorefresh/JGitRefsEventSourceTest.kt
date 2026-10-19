package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files

/**
 * The refs adapter against real JGit (CI-04 design §2.2/§7): JGit's process-wide listener list
 * reports a ref change made through ANY Repository instance (as EGit's would be), the adapter
 * matches it against the tracked git dir off the UI thread, and only a match is hopped. The hop
 * runs inline here.
 */
class JGitRefsEventSourceTest : StringSpec({
  val created = mutableListOf<File>()
  afterSpec { created.forEach { dir -> dir.deleteRecursively() } }

  /** A repository with one commit on main and a second branch, b2; returns its git dir. */
  fun repo(): File {
    val dir = Files.createTempDirectory("ci04-refs").toFile().also { created += it }
    Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
      File(dir, "a.txt").writeText("a")
      git.add().addFilepattern(".").call()
      git.commit().setMessage("c").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
      git.branchCreate().setName("b2").call()
    }
    return File(dir, ".git")
  }

  /** Checks out [branch] through a fresh Repository instance, the way another tool would. */
  fun checkout(gitDir: File, branch: String) =
    FileRepositoryBuilder().setGitDir(gitDir).build().use { Git(it).checkout().setName(branch).call() }

  "a branch switch made through another Repository instance is delivered with the tracked git dir" {
    val gitDir = repo()
    val tracked = File(gitDir.parentFile, "./.git").path // not canonical: matched by canonical path
    val delivered = mutableListOf<String>()
    val source = JGitRefsEventSource({ tracked }, { it() }, {})
    val registration = source.register { delivered += it }
    try {
      checkout(gitDir, "b2")
    } finally {
      registration.unregister()
    }
    delivered.shouldNotBeEmpty()
    delivered.toSet() shouldBe setOf(tracked)
  }

  "a change in another repository is not delivered" {
    val tracked = repo()
    val other = repo()
    val delivered = mutableListOf<String>()
    val registration = JGitRefsEventSource({ tracked.path }, { it() }, {}).register { delivered += it }
    try {
      checkout(other, "b2")
    } finally {
      registration.unregister()
    }
    delivered shouldBe emptyList()
  }

  "nothing is delivered while no repository is tracked" {
    val gitDir = repo()
    val delivered = mutableListOf<String>()
    val registration = JGitRefsEventSource({ null }, { it() }, {}).register { delivered += it }
    try {
      checkout(gitDir, "b2")
    } finally {
      registration.unregister()
    }
    delivered shouldBe emptyList()
  }

  "nothing is delivered after unregister" {
    val gitDir = repo()
    val delivered = mutableListOf<String>()
    JGitRefsEventSource({ gitDir.path }, { it() }, {}).register { delivered += it }.unregister()
    checkout(gitDir, "b2")
    delivered shouldBe emptyList()
  }

  "a git dir that cannot be canonicalised is treated as a match (the HEAD comparison decides)" {
    val gitDir = repo()
    val delivered = mutableListOf<String>()
    val source = JGitRefsEventSource({ gitDir.path }, { it() }, {}, canonical = { error("no canonical path for $it") })
    val registration = source.register { delivered += it }
    try {
      checkout(gitDir, "b2")
    } finally {
      registration.unregister()
    }
    delivered.shouldNotBeEmpty()
  }

  "a failing hop is contained and logged by class name only; the checkout itself is unaffected" {
    val gitDir = repo()
    val logs = mutableListOf<String>()
    val source = JGitRefsEventSource({ gitDir.path }, { error("hop failed for ${gitDir.path}") }, { logs += it })
    val registration = source.register { }
    try {
      checkout(gitDir, "b2")
    } finally {
      registration.unregister()
    }
    FileRepositoryBuilder().setGitDir(gitDir).build().use { it.branch } shouldBe "b2"
    logs.shouldNotBeEmpty()
    logs.forEach {
      it shouldEndWith "java.lang.IllegalStateException"
      it shouldNotContain gitDir.path
    }
  }
})
