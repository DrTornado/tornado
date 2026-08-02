pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Tornado"
include(":app")

/*
 * مجلد البناء خارج المجلد المتزامن.
 *
 * هذا المشروع يقع داخل OneDrive، وعميل المزامنة يفتح ملفات البناء أثناء إنشائها
 * فيفشل Gradle بـ "Unable to delete directory" بشكل متكرر وعشوائي.
 * نقل مخرجات البناء وحدها إلى قرص محلي غير متزامن يزيل السبب من جذره.
 *
 * الشرط يجعل التغيير بلا أثر على أي جهاز أو خادم تكامل لا يستخدم OneDrive.
 */
val insideSyncedFolder = rootDir.absolutePath.contains("OneDrive", ignoreCase = true)
if (insideSyncedFolder) {
    val localRoot = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "tornado-build/${rootProject.name}"
    )
    gradle.beforeProject {
        layout.buildDirectory.set(File(localRoot, path.replace(':', '_').trim('_').ifEmpty { "root" }))
    }
}
