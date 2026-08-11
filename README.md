# GitLab for Eclipse

This project hosts the GitLab for Eclipse plug-in and related projects.

## Projects

### gitlab-eclipse-plugin

The root Gradle project hosts the GitLab for Eclipse plug-in source.

Use `./gradlew publishToMavenLocal` to publish the latest artifact to your local Maven repository (e.g. `~/.m2/repository`).

Use `./gradlew equoIde` to start a Equo instance with GitLab for Eclipse installed.

### Debug Equo Ide

1. Use `./gradlew equoIde --debug-ide` or the `Debug Equo Ide` run configuration to start a Equo instance with GitLab for Eclipse installed.
1. Run the `Attach IDE Debugger` run configuration or attach your debugger to `localhost:8000` to debug the plugin.

## update-site

Creates a P2 update site (e.g. Eclipse repository) which can be used to install the GitLab for Eclipse plug-in.

This project uses the [tycho-p2-repository-plugin](https://tycho.eclipseprojects.io/doc/latest/tycho-p2-repository-plugin/plugin-info.html).

Use `mvn clean install -f pom.xml` to publish the latest to your local Maven repository (e.g. `~/.m2/repository`)

A successful build will include these artifacts among other intermediate artifacts:

```plaintext
target/
├── repository
│   ├── artifacts.jar
│   ├── artifacts.xml.xz
│   ├── content.jar
│   ├── content.xml.xz
│   ├── p2.index
│   ├── features
│   │   └── com.gitlab.eclipse.feature_<version>.<qualifier>.jar
│   └── plugins
│       └── com.gitlab.eclipse.gitlab-eclipse-plugin_<version>.<qualifier>.jar
└── com.gitlab.eclipse.update-site-<version>.zip
```

`<version>` is the project version declared in the root `pom.xml` (kept in step with `allprojects.version`
in `build.gradle.kts`), so these names follow the current version instead of a fixed one. `<qualifier>` is
the build timestamp Tycho substitutes for `.qualifier`.

- Generate a local update site/p2 repository under `target/repository` which can be added as a local Update Site.
- Assemble a ZIP archive `target/com.gitlab.eclipse.update-site-<version>.zip` Maven artifact which can be
  deployed for usage. The name follows the Tycho `finalName` default, `<artifactId>-<version>`.

### Add the GitLab Releases software site

> [!note]
> For local builds, run `./gradlew publishToMavenLocal && mvn clean install` once from the project root before adding the software site.

1. Open your Eclipse IDE.
1. In your IDE, select **Eclipse > Settings...**.
1. On the left sidebar, expand **Install/Update**, then select **Available Software Sites**.
1. On the right, select **Add...** to configure a new software site.
1. To build either a **local build** or a **release**:
    - For a **local build**:
        1. For **Name:**, use `GitLab Duo Local`.
        1. For **Location:**, select **Local..." and choose your local update site located at:

           ```plaintext
           <your-gitlab-for-eclipse-project-path>/update-site/target/repository
           ```

    - For a **release**:
        1. For **Name:**, use `GitLab Releases`.
        1. For **Location:**, copy and paste this URL:

           ```plaintext
           https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/releases/permalink/latest/downloads/
           ```

1. Select **Add**.
1. Select **Apply and Close**.

## Install the GitLab for Eclipse plugin using software site

Prerequisites:

- Eclipse **4.33** and later.
- GitLab version 16.8 or later.

> [!note]
> For local builds, run `./gradlew publishToMavenLocal && mvn clean install` from the project root before installing the plugin.

To install GitLab for Eclipse:

1. In your IDE, select the **Help** menu.
1. Select **Install New Software...**.
1. Expand **Work with:**, then select your chosen software site.
    - For a local build, use `GitLab Duo Local`
    - For a release, use `GitLab Releases`
1. Select the **GitLab** category to install the GitLab for Eclipse plugin and dependencies.
1. Select **Next >**, then select **Finish**.
1. Select **Restart Now**.

## Releasing

Follow the release process issue template to create and distribute a new plugin release.

### Certificate errors

If your machine connects to your GitLab instance through a proxy, you might encounter
certificate errors. If you see errors from the Language Server
about certificates, try manually specifying a CA certificate:

To do this:

1. In your IDE, select **Eclipse > Settings**.
1. On the left sidebar, select **GitLab**.
1. For **CA certificate** enter the absolute path of your CA certificate file.
You can also use the **Browse...** button to search and select the file on your computer.
1. Under the GitLab settings, select **Apply**.
1. Select **Apply and Close**.
1. Restart your IDE.
