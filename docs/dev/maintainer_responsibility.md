# Maintainer responsibility

Maintainers of GitLab for Eclipse are responsible for:

1. Reviewing and merging merge requests for the project:
    - **For small merge requests** (low complexity/one-liners/easy-to-follow) -
      You can review and merge without prior reviewer approval. (one maintainer)
    - **For medium or large requests** (medium/high complexity) -
      Make sure that the merge request has already been approved by another reviewer,
      and all the comments have been addressed. (1 reviewer, 1 maintainer approval required)
    - **Unsure or unclear** - Always ask for a second reviewer, even if it's a small merge request.
1. Joining the `#f_eclipse_plugin` Slack channel, to stay up-to-date with the project.

## Code review

Maintainers are pinged and assigned merge requests to review and merge. For example:

```plaintext
Hey @maintainer! Can you please review and merge this MR? Thanks!

/assign_reviewer @maintainer
```

When reviewing a merge request, use your best discretion to ensure that a merge request meets our functional
and internal quality criteria. Some questions to ask yourself:

### Review the feature

1. Is the user-facing change something we actually want to do? You can
   check this by viewing issues referenced in the merge request description.
1. Does the user-facing change work as expected? Are there any edge cases
   that are accidentally or intentionally over-looked?
1. Does the pre-existing functionality still work as expected?
1. Does the merge request pass a reasonable smoke test?

As a Maintainer, you do not have to verify everything yourself. Consider delegating to the contributor.

### Review the maintainability

1. Are any linting rules explicitly disabled? Why?
1. Is there a simpler approach? Why was this approach not pursued?
1. Are there any parts that are hard to follow?
1. Does this change hurt any pre-existing cohesion and responsibilities?
1. Does this change add any undesirable coupling?

## Merge

When a merge request is ready to merge:

1. Approve the merge request.
1. Ensure that the last pipeline started no more than 24 hours ago. If the pipeline is older, start a new one.
1. Select **Set to auto-merge**, which should enqueue the merge request for merging after the pipeline succeeds.

## Release new versions of the plugin

Some of the steps required to release the plugin require the Maintainer role. You might be asked to help release a
new version of the plugin to the `Stable` or `Alpha` release channel. For detailed instructions on how to create a new
release, see [release process documentation](release_process.md).

## Related topics

- [Code Review Values](https://about.gitlab.com/handbook/engineering/workflow/reviewer-values/) for how we balance priorities and
  communication during code review.

- [Project members page](https://gitlab.com/gitlab-org/gitlab-eclipse-plugin/-/project_members?with_inherited_permissions=exclude&sort=access_level_desc) to find the current list of active maintainers.
- [Reviewer Roulette](https://gitlab-org.gitlab.io/gitlab-roulette/?currentProject=gitlab-eclipse-plugin) to find available reviewers.
