package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GitLabMrVersionTest : StringSpec({
  "parses snake_case versions/:id json with diffs" {
    val json = """{"id":42,"head_commit_sha":"headsha","base_commit_sha":"basesha",""" +
      """"start_commit_sha":"startsha","diffs":[""" +
      """{"old_path":"a.txt","new_path":"b.txt","new_file":false,""" +
      """"deleted_file":false,"renamed_file":true},""" +
      """{"old_path":"c.txt","new_path":"c.txt","new_file":true,""" +
      """"deleted_file":false,"renamed_file":false}""" +
      """]}"""

    val version = Gson().fromJson(json, GitLabMrVersion::class.java)

    version.id shouldBe 42L
    version.headCommitSha shouldBe "headsha"
    version.baseCommitSha shouldBe "basesha"
    version.startCommitSha shouldBe "startsha"
    version.diffs.size shouldBe 2
    version.diffs[0].oldPath shouldBe "a.txt"
    version.diffs[0].newPath shouldBe "b.txt"
    version.diffs[0].newFile shouldBe false
    version.diffs[0].deletedFile shouldBe false
    version.diffs[0].renamedFile shouldBe true
    version.diffs[1].newFile shouldBe true
  }
})
