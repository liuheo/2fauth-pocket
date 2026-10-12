plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

allprojects {
    providers.gradleProperty("pocketBuildRoot").orNull?.let { path ->
        layout.buildDirectory.set(file("$path/${project.name}"))
    }
}
