plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.simpilot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.simpilot"
        minSdk = 31
        targetSdk = 37
        versionCode = 16
        versionName = "1.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("app.netmonster:core:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")
    testImplementation("junit:junit:4.13.2")
}

val verifyLicenseInventory by tasks.registering {
    group = "verification"
    description = "Verifies that application and runtime dependency license records are packaged."
    val requiredFiles = listOf(
        rootProject.file("LICENSE"),
        rootProject.file("NOTICE"),
        rootProject.file("THIRD_PARTY_NOTICES.md"),
        rootProject.file("licenses/registry.json"),
        project.file("src/main/assets/licenses/SIM-Pilot-LICENSE.txt"),
        project.file("src/main/assets/licenses/netmonster-core-LICENSE.txt"),
        project.file("src/main/assets/licenses/shizuku-LICENSE.txt"),
    )
    inputs.files(requiredFiles)
    doLast {
        requiredFiles.forEach { licenseFile ->
            check(licenseFile.isFile && licenseFile.length() > 0L) {
                "Required license record is missing: ${licenseFile.relativeTo(rootProject.projectDir)}"
            }
        }
        check(project.file("src/main/assets/licenses/netmonster-core-LICENSE.txt").readText().contains("Apache License"))
        check(project.file("src/main/assets/licenses/shizuku-LICENSE.txt").readText().contains("MIT License"))
    }
}

tasks.named("preBuild").configure { dependsOn(verifyLicenseInventory) }
