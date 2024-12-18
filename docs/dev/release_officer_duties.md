# Eclipse Release Officer Duties

This documentation exists to describe the responsibilities of a Release Officer for the Eclipse plugin.

An Eclipse Release Officer has three main duties:

1. [Creating the Release Schedule](#creating-the-release-schedule)
1. [Creating Releases](#creating-releases)
1. [Establishing the Next Release Officer](#establishing-the-next-release-officer)

## Creating the Release Schedule

The Release Officer is responsible for creating the release schedule and including the release schedule in the planning
issue for the milestone.

A stable Eclipse release will ideally be created every 2nd and 4th Tuesday of the milestone.

If a Release Officer is unable to create a release in line with the schedule that is created for the milestone, post in
the `#f_eclipse_plugin` to assign someone. Make sure the schedule reflects the reassignment of responsibilities.

The format for the schedule looks like (this example is taken from [planning issue for 17.7](https://gitlab.com/gitlab-org/editor-extensions/meta/-/issues/181#eclipse-1)):

| Date | Release Officer | Shadow | Status | Comments |
|------|-----------------|--------|--------|----------|
| 2024-12-12  | @erran | @kjamoralin  | Released | [v0.3.0](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/releases/v0.3.0)  |

## Creating Releases

With a release officer and release cadence established, it is time to create a release.

1. Follow the steps for the [Release Process](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/blob/main/docs/dev/release_process.md)
1. Update the Slack thread with the release.
1. Update the milestone release schedule with the required details.

## Establishing the Next Release Officer

Each milestone a Release Officer will be chosen to create releases for the Eclipse plugin. The release officer can be
a volunteer or selected by the Release Officer from the previous milestone.

A Release Officer should:

- be involved with a prior release as a shadow release officer
- be a maintainer of the project (or have a maintainer available at the time of creating the release to merge)
- have contributed to the Eclipse plugin during the previous milestone

Once a Release Officer has been selected, point them to this documentation for learning their responsibilities.