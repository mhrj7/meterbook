import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.jooq.codegen)
    alias(libs.plugins.pitest)
    alias(libs.plugins.spotbugs)
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
    testImplementation(libs.playwright)
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
                    // Policies, functions and data fixes are PostgreSQL-only; the code generator skips them.
                    property {
                        key = "parseIgnoreComments"
                        value = "true"
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
                // Queries name their joins; navigation methods would also clash on self-references
                // (api_keys.rotated_to).
                isImplicitJoinPathsToMany = false
                isImplicitJoinPathsToOne = false
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
    // Browser end-to-end tests run in their own task (e2eTest) and CI job.
    useJUnitPlatform { excludeTags("e2e") }
    // Required at start-up (no default); tests that talk to Keycloak override it.
    systemProperty("identity.keycloak.client-secret", "unused-in-tests")
    finalizedBy(tasks.jacocoTestReport)
}

val e2eTest by tasks.registering(Test::class) {
    description = "SSO end-to-end: a real browser signs in through a SAML IdP (TC-PLT-001)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    systemProperty("identity.keycloak.client-secret", "unused-in-tests")
    shouldRunAfter(tasks.test)
}

val playwrightInstall by tasks.registering(JavaExec::class) {
    description = "Downloads the Chromium build Playwright drives."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    // On CI the runner also needs Chromium's system libraries.
    args(if (System.getenv("CI") == "true") listOf("install", "--with-deps", "chromium") else listOf("install", "chromium"))
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

// Mutation testing (PIT): do the tests notice when the code is changed? Coverage only shows lines ran. Target from the
// tracker's Coverage sheet: 75% of mutants killed. Too slow for every PR; runs in its own CI job (mutation.yml).
pitest {
    pitestVersion =
        libs.versions.pitest.core
            .get()
    junit5PluginVersion =
        libs.versions.pitest.junit5
            .get()
    targetClasses = setOf("com.meterbook.identity.*")
    excludedClasses = setOf("com.meterbook.identity.db.*") // generated by jOOQ
    excludedTestClasses = setOf("com.meterbook.identity.e2e.*")
    threads = 4
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    mutationThreshold = 75
    timeoutConstInMillis = 10000
}

// SpotBugs (TC-PLT-013) looks at bytecode for bugs Error Prone does not see at compile time. Any finding fails
// `check`; tests and jOOQ's generated classes are not analysed.
spotbugs {
    toolVersion =
        libs.versions.spotbugs.core
            .get()
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    excludeFilter = file("config/spotbugs-exclude.xml")
}
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") { enabled = false }
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    reports.create("xml") { required = true }
    reports.create("html") { required = true }
}
