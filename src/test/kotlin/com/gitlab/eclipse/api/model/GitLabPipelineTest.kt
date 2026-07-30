package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GitLabPipelineTest : StringSpec({
  "parses snake_case pipeline json with all keys" {
    val json = """{"id":1,"iid":2,"project_id":9,"status":"success",""" +
      """"ref":"main","sha":"abc123","web_url":"http://h/g/p/-/pipelines/1",""" +
      """"source":"push","created_at":"2026-01-01","updated_at":"2026-01-02"}"""

    val pipeline = Gson().fromJson(json, GitLabPipeline::class.java)

    pipeline.id shouldBe 1L
    pipeline.iid shouldBe 2L
    pipeline.projectId shouldBe 9L
    pipeline.status shouldBe "success"
    pipeline.ref shouldBe "main"
    pipeline.sha shouldBe "abc123"
    pipeline.webUrl shouldBe "http://h/g/p/-/pipelines/1"
    pipeline.source shouldBe "push"
    pipeline.createdAt shouldBe "2026-01-01"
    pipeline.updatedAt shouldBe "2026-01-02"
  }

  "missing keys yield null, not the Kotlin default value" {
    val json = """{"id":1}"""

    val pipeline = Gson().fromJson(json, GitLabPipeline::class.java)

    pipeline.id shouldBe 1L
    pipeline.iid.shouldBeNull()
    pipeline.projectId.shouldBeNull()
    pipeline.status.shouldBeNull()
    pipeline.ref.shouldBeNull()
    pipeline.sha.shouldBeNull()
    pipeline.webUrl.shouldBeNull()
    pipeline.source.shouldBeNull()
    pipeline.createdAt.shouldBeNull()
    pipeline.updatedAt.shouldBeNull()
  }

  "parses snake_case job json with all keys" {
    val json = """{"id":5,"name":"build","status":"success","stage":"build",""" +
      """"web_url":"http://h/g/p/-/jobs/5","allow_failure":true}"""

    val job = Gson().fromJson(json, GitLabJob::class.java)

    job.id shouldBe 5L
    job.name shouldBe "build"
    job.status shouldBe "success"
    job.stage shouldBe "build"
    job.webUrl shouldBe "http://h/g/p/-/jobs/5"
    job.allowFailure shouldBe true
  }

  "missing job keys yield null including allow_failure, not the Kotlin default value" {
    val json = """{"id":5}"""

    val job = Gson().fromJson(json, GitLabJob::class.java)

    job.id shouldBe 5L
    job.name.shouldBeNull()
    job.status.shouldBeNull()
    job.stage.shouldBeNull()
    job.webUrl.shouldBeNull()
    job.allowFailure.shouldBeNull()
  }
})
