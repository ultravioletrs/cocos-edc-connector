plugins {
    `java-library`
}

dependencies {
    api(project(":extensions:cocos:cocos-spi"))
    api(libs.edc.decentralized.claims.spi)
    implementation(libs.edc.core.spi)
    implementation(libs.edc.http.spi)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj)
}

tasks.test {
    useJUnitPlatform()
}
