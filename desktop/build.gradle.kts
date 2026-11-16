plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "br.app.assinador"
version = "1.0.0"

dependencies {
    implementation("org.apache.pdfbox:pdfbox:2.0.31")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

application {
    mainClass.set("br.app.assinador.desktop.MainKt")
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}

// Copia só as dependências de runtime para o jpackage montar o app (build/jpackage-input).
tasks.register<Sync>("jpackageInput") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage-input"))
}
