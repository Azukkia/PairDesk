// Optional, opt-in: resolves Maven Central through Google's mirror when
// repo.maven.apache.org answers HTTP 429 (rate limiting on shared CI or
// sandbox networks). The build itself only declares google() and
// mavenCentral(); use it explicitly:
//   ./gradlew --init-script gradle/maven-central-mirror.init.gradle.kts :core:test
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun RepositoryHandler.useMirror() = all {
    if (this is MavenArtifactRepository && url.toString().contains("repo.maven.apache.org")) url = uri(mirror)
}

settingsEvaluated {
    pluginManagement.repositories.useMirror()
    dependencyResolutionManagement.repositories.useMirror()
}
