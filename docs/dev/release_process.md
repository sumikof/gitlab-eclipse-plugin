# GitLab Plugin for Eclipse releases

This process describes the steps for a successful release for the GitLab Plugin for Eclipse. If any questions come
up during this process, reach out in the `#f_eclipse_plugin` Slack channel.

1. [Determine the patch type](#determine-the-patch-type)
1. [Create the branch and merge request](#create-the-branch-and-merge-request).
1. [Release a new version](#release-a-new-version)

## Determine the patch type

1. Determine if the release is a `major`, `minor`, or `patch` release. Use this command
   to check all commit titles after the last release:

   ```shell
   git log --format='%s' $(git describe --abbrev=0 --tags HEAD)..HEAD | grep -v 'Merge branch'
   ```

    - Releasing a new major feature like Code Suggestions or GitLab Duo Chat? Perform a `major` version increment.
    - Do any commit messages contain `feat:`? Perform a `minor` version increment.
    - If none of the above, perform a `patch` version increment.

## Create the branch and merge request

Create a branch with a name like `yourname/prepare-0.5.5-release`. It should contain these changes:

1. [ ] Run `update_version.sh` with the version number as the parameter e.g. `./update_version.sh --prepare-release 0.5.5`
    - The version number be the current snapshot version.
1. [ ] Move the changes in `CHANGELOG.md` from the `[Unreleased]` section to the section for the new version.
1. [ ] Review the changes to `CHANGELOG.md`.
    - Do the changes look sensible?
    - Are words spelled correctly?
    - Do the links work?
    - Is the versioning correct for the intended release. See
      [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) for format examples.
1. [ ] Were any major features released that you should feature in the release?
1. [ ] Create a merge request containing these changes, get it reviewed, and merge it.

Optional:

1. [ ] Ask the Editor Extensions PM if you should include anything else in the changelog.
1. [ ] Request a documentation review from the Editor Extensions Tech Writing
   [stable counterpart](https://handbook.gitlab.com/handbook/engineering/development/dev/create/editor-extensions/#stable-counterparts). If they're unavailable, request a review in the `#docs` Slack channel.


## Release a new version

To complete these steps, you must be a code owner or maintainer for the
[GitLab for Eclipse](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin) project. If you are not, 
ask in `#f_eclipse_plugin` for help.

After the merge request merges:

1. [ ] Tag the merged commit with the new version:

   ```shell
   # Tag the release version with the same version as in the gradle.properties file
   git tag v0.5.5 <COMMIT-HASH>

   # Push tags:
   git push origin v0.5.5
   ```

These commands send a Slack message to the `#f_eclipse_plugin` channel, saying that the release
is now in progress.

1. [ ] In the GitLab UI, confirm the tag was added correctly:
    1. On the left sidebar, select **Search or go to** and find this project.
    1. Select **Code > Tags**, and confirm your new tag exists.
    1. Confirm that your tag has a pipeline in progress, or completed.
1. [ ] After the tag pipeline finishes, check the [software site](https://main-ee-175586.docs.gitlab-review.app/ee/editor_extensions/eclipse/setup.html#add-the-gitlab-releases-software-site)
   for the latest release.
1. [ ] Update the release schedule with the results ([example](https://gitlab.com/gitlab-org/editor-extensions/meta/-/issues/188#eclipse)).

## Eclipse Marketplace

Publish a new version of the plugin on the [Eclipse Marketplace](https://marketplace.eclipse.org/)

1. [ ] Authenticate as GitLab Inc.
1. [ ] Navigate to [My Marketplace](https://marketplace.eclipse.org/user/gitlab/listings)
1. [ ] Click on [GitLab for Eclipse](https://marketplace.eclipse.org/content/gitlab-eclipse)
1. [ ] Next to the `View` tab, you will find the `Edit` tab. Click it.
1. [ ] In `Revision log message`, add the changelog entries related to the new version.
1. [ ] In `Solution Version(s)`, update the version number to match the new version.
1. [ ] Scroll down until the `Update` button is on screen. Click it.
1. [ ] Go back to [GitLab for Eclipse](https://marketplace.eclipse.org/content/gitlab-eclipse) and install the plugin.

## Post-release

Create a branch with a name like `yourname/prepare-0.5.5-snapshot`. It should contain these changes:

1. [ ] Run `update_version.sh` with the version number as the parameter e.g. `./update_version.sh 0.5.6`
   1. The version number should be above the latest released version and be composed of 3 digits.
1. [ ] Create a merge request containing these changes, get it reviewed, and merge it.
