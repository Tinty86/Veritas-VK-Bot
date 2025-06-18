plugins {
    id("java")
}

group = "com.tinty"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("com.vk.api:sdk:1.0.16")            // VK Java SDK :contentReference[oaicite:2]{index=2}
    implementation("com.google.firebase:firebase-admin:9.1.1") // Firebase Admin
    implementation("com.sparkjava:spark-core:2.9.4")     // лёгкий HTTP‑сервер
    implementation("io.github.cdimascio:java-dotenv:5.2.2")

    // Обязательное ядро SLF4J 2.x
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Реализация логгера через Log4j
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.20.0")
}

tasks.test {
    useJUnitPlatform()
}