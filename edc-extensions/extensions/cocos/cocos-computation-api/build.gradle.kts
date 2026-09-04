plugins {
    `java-library`
}

dependencies {
    api(project(":extensions:cocos:cocos-spi"))
    api(libs.edc.web.spi)
    implementation(libs.edc.core.spi)
    implementation(libs.jakarta.rsApi)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly(libs.jersey.common)
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj)
}

tasks.test {
    useJUnitPlatform()
}
