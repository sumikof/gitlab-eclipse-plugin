# update-site

Creates a P2 update site (e.g. Eclipse repository) which can be used to install the GitLab for Eclipse plug-in.

This project uses the [tycho-p2-repository-plugin](https://tycho.eclipseprojects.io/doc/latest/tycho-p2-repository-plugin/plugin-info.html).

Using `mvn clean install` will:

A successful build will include these artifacts among other intermediate artifacts:

```plaintext
target/
├── repository
│   ├── artifacts.jar
│   ├── artifacts.xml.xz
│   ├── content.jar
│   ├── content.xml.xz
│   ├── p2.index
│   └── plugins
│       └── gitlab-eclipse-plugin_0.1.0.202411061450.jar
└── update-site-0.1.0-SNAPSHOT.zip
```

- Generate a local update site/p2 repository under `target/repository` which can be added as a local Update Site.
- Assemble a ZIP archive `target/update-site-0.1.0-SNAPSHOT.zip` Maven artifact which can be deployed for usage.
