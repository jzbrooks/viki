import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
  alias(libs.plugins.ktfmt)
}

group = "app.viki"

version = "1.0.0"

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

metro {
  enableCircuitCodegen.set(true)
  enableTopLevelFunctionInjection.set(true)
}

ktfmt { googleStyle() }

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.materialIconsExtended)
  implementation(compose.components.resources)

  implementation(libs.circuit.foundation)
  implementation(libs.circuit.codegen.annotations)
  implementation(libs.circuit.overlay)
  implementation(libs.circuitx.overlays)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.swing)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.circuit.test)
}

tasks.test { useJUnitPlatform() }

compose.desktop {
  application {
    mainClass = "app.viki.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "Viki"
      packageVersion = "1.0.0"
      description = "Designer-First Android Virtual Device Manager"
      vendor = "Viki"
    }
  }
}
