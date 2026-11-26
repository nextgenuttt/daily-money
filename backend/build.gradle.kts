plugins {
    java
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.dailymoney"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.google.firebase:firebase-admin:9.12.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.1")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // Real PostgreSQL for integration tests, started from a jar (no Docker needed).
    testImplementation("io.zonky.test:embedded-postgres:2.2.3")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.12.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew bootRun` reads secrets from backend/.env (git-ignored). Tests never see them; hosts set real env vars.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line -> environment(line.substringBefore("=").trim(), line.substringAfter("=").trim()) }
    }
}

// Only the runnable jar is needed (keeps the Docker copy unambiguous).
tasks.named<Jar>("jar") { enabled = false }
