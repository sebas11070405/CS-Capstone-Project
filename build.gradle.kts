plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.sentencebuilder"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("io.github.cdimascio:dotenv-java:3.2.0")

    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainModule = "com.sentencebuilder"
    mainClass = "com.sentencebuilder.app.Main"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
