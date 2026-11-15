plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":product"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.apollographql.federation:federation-graphql-java-support:6.2.1")
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation(platform("software.amazon.awssdk:bom:2.59.1"))
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:sqs")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-graphql-test")
}

// The console is built by npm into web/dist; when it is there, the jar serves it.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("web/dist")) {
        into("static")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("cashback-claims-ledger.jar")
}
