pluginManagement { repositories { if (providers.gradleProperty("useMirror").orNull == "true") maven("https://maven.aliyun.com/repository/google"); google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { if (providers.gradleProperty("useMirror").orNull == "true") maven("https://maven.aliyun.com/repository/google"); google(); mavenCentral() } }
rootProject.name = "Diary"
include(":app")
