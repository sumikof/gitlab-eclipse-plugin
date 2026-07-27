package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/**
 * A GitLab merge request diff version. The list endpoint
 * (`/merge_requests/:iid/versions`) returns summaries only (no [diffs]); the single
 * endpoint (`/merge_requests/:iid/versions/:versionId`) includes [diffs].
 */
data class GitLabMrVersion(
  @SerializedName("id") val id: Long,
  @SerializedName("head_commit_sha") val headCommitSha: String? = null,
  @SerializedName("base_commit_sha") val baseCommitSha: String? = null,
  @SerializedName("start_commit_sha") val startCommitSha: String? = null,
  @SerializedName("diffs") val diffs: List<Diff> = emptyList(),
) {
  data class Diff(
    @SerializedName("old_path") val oldPath: String? = null,
    @SerializedName("new_path") val newPath: String? = null,
    @SerializedName("new_file") val newFile: Boolean = false,
    @SerializedName("deleted_file") val deletedFile: Boolean = false,
    @SerializedName("renamed_file") val renamedFile: Boolean = false,
  )
}
