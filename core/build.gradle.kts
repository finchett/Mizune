plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Shared by the Android and desktop apps and the browser extension, so nothing here may touch
// android.* or java.awt. What every one of them runs is in commonMain; jvmMain has what only
// the apps need (files, video containers, JDBC) and the JVM's side of the platform pieces, and
// jsMain the browser's.
kotlin {
    jvmToolchain(17)
    jvm()
    js {
        // the extension's worker; the tests run under Node, which needs no browser installed
        browser { testTask { enabled = false } }
        nodejs()
        binaries.library()
        // the extension's build imports it (extension/build.mjs), types and all
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jsMain.dependencies {
            // dictionary zips, read in the extension's worker
            implementation(npm("fflate", "0.8.3"))
        }
        jsTest.dependencies {
            // the extension's SQLite, for the engine's tests (the extension brings its own)
            implementation(npm("@sqlite.org/sqlite-wasm", "3.54.1-build1"))
        }
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
