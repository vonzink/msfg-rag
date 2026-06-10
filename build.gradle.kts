plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.msfg"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.7"

dependencies {
    // --- Spring core ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Spring AI: Anthropic chat, OpenAI embeddings, pgvector store, Tika document reader ---
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")

    // --- Database ---
    // Maps pgvector VECTOR columns to float[] in entities.
    // Version intentionally tracks Boot's managed Hibernate version (see below).
    implementation("org.hibernate.orm:hibernate-vector")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Domain pack loading (YAML -> records) ---
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // --- Rate limiting (public endpoint protection) ---
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // --- Token counting for chunk sizing ---
    implementation("com.knuddels:jtokkit:1.1.0")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
    dependencies {
        // hibernate-vector is not in Boot's BOM; pin it to Boot's Hibernate version.
        dependency("org.hibernate.orm:hibernate-vector:${importedProperties["hibernate.version"]}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
