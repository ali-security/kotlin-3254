plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

kotlin {
    explicitApi()
}

publish()

standardPublicJars()

sourceSets.named("test") {
    java.srcDir("src/test/kotlin")
}

projectTest {
    useJUnit()
    systemProperty("overwrite.output", System.getProperty("overwrite.output", "false"))
    systemProperty("testCasesClassesDirs", sourceSets.test.get().output.classesDirs.asPath)
    jvmArgs("-ea")
}

dependencies {
    api(project(":libraries:tools:abi-validation:abi-tools-api"))

    implementation(project(":kotlin-metadata-jvm"))
    implementation(project(":kotlin-klib-abi-reader"))

    implementation(libs.intellij.asm)
    implementation(libs.diff.utils)

    testImplementation(kotlinTest("junit"))
    testImplementation(libs.junit4)
    // using `KonanTarget` class
    testImplementation(project(":native:kotlin-native-utils"))
}

