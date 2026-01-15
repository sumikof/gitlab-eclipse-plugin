<!--
# Read me first!

Create this issue under https://gitlab.com/gitlab-org/security/editor-extensions/gitlab-eclipse-plugin

Set the title to: `Title of the original issue`
-->

## Prior to starting the security work

- [ ] Read the [security process for engineers] if you are not familiar with it.
- [ ] Add a `~severity::x` label to the issue and all associated merge requests.
- [ ] Mark this [issue as linked] to the `gitlab-org/gitlab` issue that describes the security vulnerability.
- Fill out the [Links section](#links):
    - [ ] Next to **Issue on GitLab**, add a link to the `gitlab-org/gitlab` issue that describes the security vulnerability.

## Development

- [ ] Create a new branch prefixing it with `security-`.
- [ ] Create a merge request targeting `main` on `gitlab.com/gitlab-org/security/editor-extensions/gitlab-eclipse-plugin`


After your merge request has been approved according to our [approval guidelines] and by a team member of the AppSec team, you're ready to prepare the release


## Publishing a release

- [ ] Once the MR in the security repo is merged, you should communicate it to the stake holders
- [ ] Follow [security process for engineers] to publish the release


## Documentation and final details

- [ ] To avoid release delays, please nominate a developer in a different timezone who will be able to respond to any pipeline or merge failures in your absence `@gitlab-username`
- [ ] Ensure `~severity::x` label is on this issue, all associated issues, and merge requests
- [ ] Ensure the [Links section](#links) is completed.
- [ ] Fill in any upgrade notes that users may need to take into account in the [details section](#details)
- [ ] Add the nickname of the external user who found the issue (and/or HackerOne profile) to the Thanks row in the [details section](#details)

## Summary

### Links

| Description                                                    | Link   |
| -------------------------------------------------------------- | ------ |
| Issue on [GitLab for Eclipse](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/work_items) | #TODO  |
| CVE ID request on [`gitlab-org/cves`](https://gitlab.com/gitlab-org/cves/-/issues?sort=created_date&state=opened) | #TODO for PSIRT  |

### Details

| Description                         | Details    | Further details                                           |
|-------------------------------------|------------|-----------------------------------------------------------|
| First Version affected              | X.Y        | This is used to tell customers the first version of the product that has this vulnerability through our CVEs |
| Date introduced on .com             | YYYY-MM-DD | #TODO for Engineering - please follow the format          |
| MR that introduced the bug          |            | #TODO for Engineering - Link to the MR that introduced the bug|
| Date detected                       | YYYY-MM-DD | #TODO for PSIRT - please follow the format               |
| Thanks                              |            |                                                           |

[security process for engineers]: https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/blob/main/docs/dev/security_releases.md
[backport fix to stable branch documentation]: https://gitlab.com/gitlab-org/release/docs/-/blob/master/general/security/utilities/backport_fix_to_stable_branch.md
[security merge request template]: https://gitlab.com/gitlab-org/security/gitlab/blob/master/.gitlab/merge_request_templates/Security%20Fix.md
[approval guidelines]: https://docs.gitlab.com/development/code_review/#approval-guidelines
[issue as linked]: https://docs.gitlab.com/user/project/issues/related_issues/#add-a-linked-issue
[Security Tracking Issue]: https://gitlab.com/gitlab-org/gitlab/-/issues/?label_name%5B%5D=upcoming%20security%20release

/label ~security ~"security-notifications" ~"devops::create" ~"group::editor extensions" ~"Category:Editor Extensions" ~"Editor Extensions::Eclipse"