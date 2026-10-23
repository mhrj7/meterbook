import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.jooq.codegen)
}

group = "com.meterbook"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

// Every resolved version is recorded in gradle.lockfile; refresh with ./gradlew dependencies --write-locks.
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    jooqCodegen(libs.jooq.meta.extensions)

    errorprone(libs.errorprone.core)

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// jOOQ classes are generated from the Flyway migrations (no database needed at build time).
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
                inputSchema = "PUBLIC"
                outputSchemaToDefault = true
            }
            generate {
                isJavaTimeTypes = true
                isDeprecated = false
                isRecords = true
                isFluentSetters = false
            }
            target {
                packageName = "com.meterbook.identity.db"
                directory = "build/generated-sources/jooq"
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.files(fileTree("src/main/resources/db/migration"))
}
// A plain directory plus an explicit dependency: passing the task itself as a source directory makes
// Gradle resolve the source set while it is being modified (ConcurrentModificationException in JaCoCo).
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated-sources/jooq")) }
tasks.compileJava { dependsOn("jooqCodegen") }

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.excludedPaths = ".*/build/generated-sources/.*"
}

tasks.bootJar { archiveFileName = "identity.jar" }
tasks.jar { enabled = false }

tasks.test {
    useJUnitPlatform()
    // Required at start-up (no default); tests that talk to Keycloak override it.
    systemProperty("identity.keycloak.client-secret", "unused-in-tests")
    finalizedBy(tasks.jacocoTestReport)
}

jacoco { toolVersion = libs.versions.jacoco.get() }

// Coverage counts our code only, not the jOOQ classes generated from the migrations.
val ownClasses =
    sourceSets.main.map { main ->
        main.output.classesDirs.asFileTree
            .matching { exclude("com/meterbook/identity/db/**") }
    }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true }
    classDirectories.setFrom(ownClasses)
}

// Coverage gates from docs/standards/testing-standards.md.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(ownClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

spotless {
    java {
        // Our sources only; generated jOOQ code is not formatted.
        target("src/**/*.java")
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        )
        removeUnusedImports()
    }
    kotlinGradle { ktlint() }
}
