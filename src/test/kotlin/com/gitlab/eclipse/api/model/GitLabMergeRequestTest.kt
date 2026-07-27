package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GitLabMergeRequestTest : StringSpec({
  "parses snake_case MR json" {
    val json = """{"id":1,"iid":2,"title":"t","project_id":9,""" +
      """"web_url":"http://h/g/p/-/merge_requests/2","state":"opened",""" +
      """"draft":false,"source_project_id":9,"target_project_id":9,""" +
      """"source_branch":"feat","updated_at":"2026-01-01",""" +
      """"references":{"full":"g/p!2"}}"""
    val mr = Gson().fromJson(json, GitLabMergeRequest::class.java)
    mr.iid shouldBe 2L
    mr.projectId shouldBe 9L
    mr.sourceBranch shouldBe "feat"
    mr.references?.full shouldBe "g/p!2"
  }
})
