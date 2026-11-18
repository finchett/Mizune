plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Shared by the Android and desktop apps, so nothing here may touch android.* or java.awt.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            // Android ships org.json in the platform; the desktop app supplies the same API from Maven.
            compileOnly("org.json:json:20250517")
        }
        jvmTest.dependencies {
            implementation("org.json:json:20250517")
            implementation("junit:junit:4.13.2")
            implementation("org.xerial:sqlite-jdbc:3.50.3.0")
        }
    }
}
