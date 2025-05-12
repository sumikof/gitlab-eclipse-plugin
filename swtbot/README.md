# GitLab for Eclipse SWTBot E2E Tests

A Tycho Maven project to run E2E test on an Eclipse application using [SWTBot](https://wiki.eclipse.org/SWTBot/).

## Local development

IntelliJ won't automatically detect the dependencies due to its lack of support for Tycho Maven project. To enable E2E tests development, you will need to:

1. Run `./gradlew publishToMavenLocal` in the plugin root folder to publish the latest artifact to your local Maven repository (e.g. `~/.m2/repository`).
1. Add the `swtbot` folder as a Maven module of the current project.
   1. Locate `gitlab-eclipse-plugin` in your project tree.
   1. Right-click the folder and select `Open Module Settings`.
   1. Click on the `+` at the top and select `Import Module`.
   1. Navigate to the `swtbot` folder and click `Open`.
   1. Select `Maven` and then click `Create`.
   1. The folder should appear as a module in the project tree. Otherwise, try to `Reload All Maven Projects` using the `Maven` tool window usually located on the right of the screen.
1. In the `swtbot` folder, run `make sync-dependencies` to download the required dependencies in `p2-libs`.
1. Right click the `p2-libs` folder and select `Add as Library...`.
   1. Enter `p2-libs` as name.
   1. Select `Module Library` as level.
   1. Select `com.gitlab.eclipse.swtbot` as target.

Now you should be able to edit and write new test with code completion and all the language features!

## Run the E2E tests

1. Ensure the plugin is published to your local Maven repository (e.g. `~/.m2/repository`). If not, run `./gradlew publishToMavenLocal`.
1. Run `make e2e-tests` in the `swtbot` folder. 
