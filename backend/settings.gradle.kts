rootProject.name = "erika-backend"

// invoice-core lives in the same repository and is built from source (ADR 0020):
// the declared module de.erika:invoice-core is substituted by the included build.
includeBuild("../invoice-core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
