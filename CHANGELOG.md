# Changelog

## [Unreleased]

### Added

### Changed

### Removed

### Fixed

## 0.8.2 (2026-03-31)

### Changed

- Bumped Language Server version to 8.80.0 ([!446](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/446))

### Fixed

- Fix plugin failing to activate on Eclipse 2026-03 due to duplicate `textDocument/inlineCompletion` LSP4J method ([!446](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/446))

## 0.8.1 (2025-11-20)

### Fixed

- Execute UI feature state updates asynchronously ([!397](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/397))

## 0.8.0 (2025-11-14)

### Added

- Added Windows ARM64 support ([!389](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/389))

### Changed

- Improved Language Server log file location ([!391](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/391))
- Bumped Language Server version to 8.41.0 ([!390](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/390))

### Removed

### Fixed

## 0.7.8 (2025-11-10)

### Changed

- Bumped Language Server version to 8.36.1 ([!382](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/382))

## 0.7.7 (2025-09-11)

### Changed

- Bumped Language Server version to 8.9.2 ([!339](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/339))

## 0.7.6 (2025-08-26)

### Changed

- Bumped Language Server version to 8.5.2 ([!322](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/322))

## 0.7.5 (2025-07-28)

### Fixed

- Bumped Language Server version to 8.0.1 to include several security fixes ([!314](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/314))

## [0.7.3 (2025-06-23)]

### Added

### Changed

### Removed

### Fixed

- Increase authentication notification delay ([!289](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/289))

## [0.7.2 (2025-06-06)]

### Added

### Changed

### Removed

### Fixed

- Support platform specific line endings when partially accepting a suggestion ([!281](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/281))
- Re-use the same instance of the line spacing provider ([!280](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/280))
- Using non-existent java fields in preference page font on Windows ([!275](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/275))

## 0.7.1 (2025-05-23)

### Added

- Added a user notification when authentication is not set ([!229](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/229))
- Added a button to generate PAT ([!235](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/235))
- Added keyboard shortcut to open Duo Chat window ([!236](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/236))
- Added a verify setup button in preferences to perform health checks ([!238](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/238))
- Added the ability to accept a code suggestion word by word ([!249](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/249))

### Changed

- Grouped related settings in preferences ([!231](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/231))

### Fixed

- Avoid not displaying a code suggestion when its formatting fails ([!241](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/241))

## 0.6.0 (2025-05-01)

### Added

- Accept code suggestions line by line ([!200](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/200))

### Changed

- Changed the log level field in the preferences page from a text field into a dropdown. ([!216](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/216))

### Removed

### Fixed

## 0.5.0 (2025-04-07)

### Added

- Deployed the initial version of Code Suggestions feature ([!198](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/198))

### Changed

### Removed

### Fixed

## 0.4.1 (2025-03-25)

### Added

### Changed

### Removed

### Fixed

## 0.4.0 (2025-02-27)

### Added

### Changed

### Removed

### Fixed

## 0.3.3 (2025-01-31)

### Added

- Add support for HTTP and HTTPS proxies ([!31](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/31))
- Allow manually specifying a CA certificate ([!33](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/33))
- Add GitLab status popup menu icon to trim ([!50](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/50))

### Changed

- Improved dark/light Eclipse theme support for Duo Chat ([!45](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/45))

### Removed

### Fixed

- Fixed bug preventing use of PATs that include underscores ([!104](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/68))

## 0.3.0 (2024-12-12)

### Added

- Add GitLab for Eclipse plugin preferences ([!6](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/6))
- Add GitLab Language Server platform bundles ([!9](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/9))
- Add GitLab hosted Eclipse update site ([!11](https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/merge_requests/11))

### Changed

### Removed

### Fixed
