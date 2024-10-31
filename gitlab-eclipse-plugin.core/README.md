# gitlab-eclipse-plugin.core

The core GitLab for Eclipse plug-in project.

## Development

### Pre-requisites

The following should be installed:

- Maven 3.9.9
- Eclipse Temurin or OpenJDK 21

Consider [parent pom.xml](../pom.xml) and [project CI/CD configuration](../.gitlab-ci.yml) as the source of truth for target Java runtime and latest verified Maven version.

### Command-line

You can use `mvn verify` to compile and run all tests for this project.

### Eclipse

Inside of Eclipse you can use the `GitLab Eclipse Plugin` run configuration to launch the Eclipse IDE with this plugin installed.
