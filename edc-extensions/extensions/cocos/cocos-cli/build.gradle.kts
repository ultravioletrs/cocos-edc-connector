plugins {
    `java-library`
}

dependencies {
    api(project(":extensions:cocos:cocos-spi"))
    implementation(libs.edc.core.spi)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj)
}

tasks.test {
    useJUnitPlatform()
}
