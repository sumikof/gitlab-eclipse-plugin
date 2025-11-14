plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gitlab-eclipse-plugin"

include(
    "gitlab-language-server",
    "gitlab-language-server.cocoa.macosx.aarch64",
    "gitlab-language-server.cocoa.macosx.x86_64",
    "gitlab-language-server.gtk.linux.x86_64",
    "gitlab-language-server.win32.win32.aarch64",
    "gitlab-language-server.win32.win32.x86_64",
)
