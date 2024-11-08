<!--
Please fill out the version changes below.
Then, check off the list as items are completed to ensure all steps are completed correctly.
-->

This merge request updates the plugin version from `vX.X.X` to `vX.X.X`.

It should contain these changes:

1. [ ] In `gradle.properties`, update the `plugin.version` number above the last stable version
   according to the [Semantic Version 2.0](https://semver.org/) guidelines.
1. [ ] Move the changes in `CHANGELOG.md` from the `[Unreleased]` section, to
   the section for the new version, with this command:

   ```shell
   ./gradlew patchChangelog
   ```

1. [ ] Review the changes to `CHANGELOG.md`.

   - Do the changes look sensible?
   - Are words spelled correctly?
   - Do the links work?
   - Is the versioning correct for the intended release. See
     [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) for format examples.

1. [ ] Ensure the Version Compatibility table in `README.md` is up-to-date.
1. [ ] Create a merge request containing these changes, get it reviewed, and merge it.
1. [ ] Merge this merge request and push a new git tag.

Optional:

1. [ ] Ask the Editor Extensions PM if you should include anything else in the changelog.
1. [ ] Request a documentation review from the Editor Extensions Tech Writing
   [stable counterpart](https://handbook.gitlab.com/handbook/engineering/development/dev/create/editor-extensions/#stable-counterparts). If they're unavailable, request a review in the `#docs` Slack channel.

<!-- Do not edit below this line -->

/label ~"Editor Extensions::Eclipse" ~"Category:Editor Extensions" ~"devops::create" ~"group::editor extensions" ~"section::dev"

/label ~"type::maintenance" ~"maintenance::release"

/assign me
