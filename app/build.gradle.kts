plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.dacha.boiler"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.dacha.boiler"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("maven.repo.local", "${rootDir}/.tools/robolectric-m2") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/NOTICE*", "META-INF/LICENSE*") }
}
dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
