package com.gitlab.eclipse.api

import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [DiscussionService]'s protocol constants and identifier construction (task 4).
 * The paging loop and deadline logic (task 5) are not exercised here.
 */
class DiscussionServiceTest : DescribeSpec({
  val gson = Gson()

  describe("mrGid") {
    it("builds the gid://gitlab/MergeRequest/{id} form") {
      DiscussionService.mrGid(1234L) shouldBe "gid://gitlab/MergeRequest/1234"
    }

    it("uses the merge request's id, NOT its iid, even when a similarly-shaped iid exists") {
      val id = 9001L
      val unrelatedIidLikeNumber = 42L

      DiscussionService.mrGid(id) shouldBe "gid://gitlab/MergeRequest/9001"
      DiscussionService.mrGid(unrelatedIidLikeNumber) shouldBe "gid://gitlab/MergeRequest/42"
    }

    it("tracks whatever argument it is given") {
      DiscussionService.mrGid(1L) shouldBe "gid://gitlab/MergeRequest/1"
      DiscussionService.mrGid(999999L) shouldBe "gid://gitlab/MergeRequest/999999"
    }
  }

  describe("namespaceWithPath") {
    it("splits on '!' and keeps the part before it") {
      DiscussionService.namespaceWithPath("group/project!42") shouldBe "group/project"
    }

    it("splits on '#' and keeps the part before it") {
      DiscussionService.namespaceWithPath("group/project#7") shouldBe "group/project"
    }

    it("keeps every namespace segment for a nested group") {
      DiscussionService.namespaceWithPath("group/subgroup/project!1") shouldBe "group/subgroup/project"
    }

    it("returns the input unchanged when there is no '#' or '!' separator") {
      DiscussionService.namespaceWithPath("group/project") shouldBe "group/project"
    }
  }

  describe("queryVariables") {
    it("returns exactly the three expected keys") {
      val variables = DiscussionService.queryVariables("group/project", 42L, null)

      variables.keys shouldBe setOf("namespaceWithPath", "iid", "afterCursor")
    }

    it("encodes iid as a String, not a number, because the GraphQL variable is String!") {
      val variables = DiscussionService.queryVariables("group/project", 42L, null)

      variables["iid"] shouldBe "42"
      variables["iid"].shouldBeInstanceOf<String>()
    }

    it("carries a non-null afterCursor through unchanged") {
      val variables = DiscussionService.queryVariables("group/project", 42L, "cursor-1")

      variables["afterCursor"] shouldBe "cursor-1"
    }

    it("keeps the afterCursor key present with a null value on the first page") {
      val variables = DiscussionService.queryVariables("group/project", 42L, null)

      variables.containsKey("afterCursor") shouldBe true
      variables["afterCursor"] shouldBe null
    }
  }

  describe("GET_MR_DISCUSSIONS_QUERY") {
    it("declares \$iid as String! and \$namespaceWithPath as ID!") {
      val query = DiscussionService.GET_MR_DISCUSSIONS_QUERY

      query.contains("\$namespaceWithPath: ID!, \$iid: String!, \$afterCursor: String") shouldBe true
    }

    it("requests mergeRequest.userPermissions { createNote }") {
      DiscussionService.GET_MR_DISCUSSIONS_QUERY.contains("userPermissions { createNote }") shouldBe true
    }

    it("requests discussions(after: \$afterCursor)") {
      DiscussionService.GET_MR_DISCUSSIONS_QUERY.contains("discussions(after: \$afterCursor)") shouldBe true
    }

    it("does not request bodyHtml, avatarUrl, or diffRefs") {
      val query = DiscussionService.GET_MR_DISCUSSIONS_QUERY

      query.contains("bodyHtml") shouldBe false
      query.contains("avatarUrl") shouldBe false
      query.contains("diffRefs") shouldBe false
    }
  }

  describe("DiscussionsQueryData Gson parsing") {
    it("parses a realistic full response payload") {
      val json = """
        {
          "project": {
            "id": "gid://gitlab/Project/1",
            "mergeRequest": {
              "userPermissions": { "createNote": true },
              "discussions": {
                "pageInfo": { "hasNextPage": false, "endCursor": null },
                "nodes": [
                  {
                    "replyId": "reply-1",
                    "createdAt": "2026-01-01T00:00:00Z",
                    "resolved": false,
                    "resolvable": true,
                    "notes": {
                      "pageInfo": { "hasNextPage": false, "endCursor": null },
                      "nodes": [
                        {
                          "id": "note-1",
                          "createdAt": "2026-01-01T00:00:00Z",
                          "system": false,
                          "author": { "username": "alice" },
                          "body": "hello",
                          "userPermissions": { "resolveNote": true, "adminNote": false, "createNote": true },
                          "position": null
                        }
                      ]
                    }
                  }
                ]
              }
            }
          }
        }
      """.trimIndent()

      val data = gson.fromJson(json, DiscussionsQueryData::class.java)

      data.project?.mergeRequest?.userPermissions?.createNote shouldBe true
      data.project?.mergeRequest?.discussions?.pageInfo?.hasNextPage shouldBe false
      val node = data.project?.mergeRequest?.discussions?.nodes?.get(0)
      node?.replyId shouldBe "reply-1"
      node?.resolved shouldBe false
      node?.notes?.nodes?.get(0)?.id shouldBe "note-1"
    }

    it("parses a payload with project == null without throwing") {
      val json = """{"project": null}"""

      val data = gson.fromJson(json, DiscussionsQueryData::class.java)

      data shouldBe DiscussionsQueryData(project = null)
    }

    it("parses a payload with mergeRequest absent into a non-null project with mergeRequest == null") {
      val json = """{"project": {"id": "gid://gitlab/Project/1"}}"""

      val data = gson.fromJson(json, DiscussionsQueryData::class.java)

      data.project shouldBe ProjectDto(id = "gid://gitlab/Project/1", mergeRequest = null)
    }
  }
})
