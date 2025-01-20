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

Use `mvn clean install -f update-site/pom.xml` to publish the latest to your local Maven repository (e.g. `~/.m2/repository`)

Learn more in the [update site project README](./gitlab-eclipse-plugin.site/README.md).

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
