package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GitLabBridgeJsonTest : StringSpec({
  val gson = Gson()

  "downstream_pipeline null yields null" {
    val b = gson.fromJson("""{"id":1,"downstream_pipeline":null}""", GitLabBridge::class.java)
    b.id shouldBe 1L
    b.downstreamPipeline.shouldBeNull()
  }

  "absent downstream_pipeline key yields null" {
    gson.fromJson("""{"id":1,"name":"trigger"}""", GitLabBridge::class.java).downstreamPipeline.shouldBeNull()
  }

  "downstream object without project_id and source leaves those fields null" {
    val b = gson.fromJson("""{"id":1,"downstream_pipeline":{"id":77,"status":"running"}}""", GitLabBridge::class.java)
    val d = b.downstreamPipeline!!
    d.id shouldBe 77L
    d.status shouldBe "running"
    d.projectId.shouldBeNull()
    d.source.shouldBeNull()
  }

  "full bridge is parsed" {
    val json = """{"id":5,"name":"deploy-trigger","stage":"deploy","status":"success",""" +
      """"web_url":"http://h/j/5","allow_failure":true,"downstream_pipeline":{"id":77,"iid":3,""" +
      """"project_id":9,"status":"success","source":"parent_pipeline","web_url":"http://h/p/77"}}"""
    val b = gson.fromJson(json, GitLabBridge::class.java)
    b.name shouldBe "deploy-trigger"
    b.stage shouldBe "deploy"
    b.status shouldBe "success"
    b.webUrl shouldBe "http://h/j/5"
    b.allowFailure shouldBe true
    b.downstreamPipeline!!.projectId shouldBe 9L
    b.downstreamPipeline!!.source shouldBe "parent_pipeline"
    b.downstreamPipeline!!.webUrl shouldBe "http://h/p/77"
  }

  "missing optional keys yield null" {
    val b = gson.fromJson("""{"id":1}""", GitLabBridge::class.java)
    b.name.shouldBeNull()
    b.allowFailure.shouldBeNull()
  }
})
