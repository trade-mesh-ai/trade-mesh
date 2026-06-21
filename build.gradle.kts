plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "com.trademeshai"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    // Kite Connect SDK is hosted on JitPack
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // WebFlux
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // R2DBC + PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.postgresql:r2dbc-postgresql:1.0.5.RELEASE")
    implementation("org.postgresql:postgresql:42.7.3")

    // Liquibase (schema migrations)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.liquibase:liquibase-core")

    // Redis Reactive
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("io.lettuce:lettuce-core")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")

    // SpringDoc OpenAPI (WebFlux)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.5.0")

    // Kite Connect Java SDK (KiteTicker WebSocket + REST)
    // Bundled locally from https://github.com/zerodhatech/javakiteconnect (kite3 branch)
    // JitPack builds an empty JAR for this repo; the pre-built dist/kiteconnect.jar is used instead.
    implementation(files("libs/kiteconnect.jar"))

    // Jackson (JSON deserialization for Kite Connect API responses)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Spring Boot Configuration Processor (for @ConfigurationProperties metadata)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-Amapstruct.defaultComponentModel=spring",
        "-parameters"
    ))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
