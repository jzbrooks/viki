package app.viki.sdk

import app.viki.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import java.nio.file.Paths

enum class OsFamily(
  val displayName: String,
  val cmdlineToolsOsKey: String,
  val repoHostOs: String,
) {
  MACOS("macOS", "mac", "macosx"),
  WINDOWS("Windows", "win", "windows"),
  LINUX("Linux", "linux", "linux"),
}

enum class CpuArch(
  val abiName: String,
  val displayName: String,
) {
  ARM64("arm64-v8a", "ARM64 (Apple Silicon / ARM)"),
  X86_64("x86_64", "x86_64 (Intel / AMD)"),
}

data class HostPlatformInfo(
  val osFamily: OsFamily,
  val cpuArch: CpuArch,
  val userHome: Path,
  val javaHome: Path,
) {
  val defaultSdkRoot: Path
    get() =
      when (osFamily) {
        OsFamily.MACOS -> userHome.resolve("Library/Android/sdk")
        OsFamily.WINDOWS -> {
          val localAppData = System.getenv("LOCALAPPDATA")
          if (!localAppData.isNullOrBlank()) {
            Paths.get(localAppData, "Android", "Sdk")
          } else {
            userHome.resolve("AppData/Local/Android/Sdk")
          }
        }
        OsFamily.LINUX -> userHome.resolve("Android/Sdk")
      }

  val commonSdkCandidatePaths: List<Path>
    get() = buildList {
      add(defaultSdkRoot)
      when (osFamily) {
        OsFamily.MACOS -> {
          add(userHome.resolve("Android/sdk"))
          add(Paths.get("/opt/homebrew/share/android-commandlinetools"))
          add(Paths.get("/usr/local/share/android-commandlinetools"))
          add(Paths.get("/Library/Android/sdk"))
        }
        OsFamily.WINDOWS -> {
          add(userHome.resolve("AppData/Local/Android/Sdk"))
          add(Paths.get("C:\\Android\\sdk"))
        }
        OsFamily.LINUX -> {
          add(userHome.resolve("Android/sdk"))
          add(userHome.resolve(".local/share/android-sdk"))
          add(Paths.get("/opt/android-sdk"))
          add(Paths.get("/usr/lib/android-sdk"))
        }
      }
    }
      .distinct()

  val avdHomeDir: Path
    get() {
      val envAvdHome = System.getenv("ANDROID_AVD_HOME")
      if (!envAvdHome.isNullOrBlank()) return Paths.get(envAvdHome)
      val envPrefsRoot = System.getenv("ANDROID_PREFS_ROOT") ?: System.getenv("ANDROID_SDK_HOME")
      if (!envPrefsRoot.isNullOrBlank()) {
        val candidate = Paths.get(envPrefsRoot)
        return if (candidate.fileName?.toString() == ".android") {
          candidate.resolve("avd")
        } else {
          candidate.resolve(".android/avd")
        }
      }
      return userHome.resolve(".android/avd")
    }

  val vikiConfigDir: Path
    get() = userHome.resolve(".viki")

  fun executableName(baseName: String): String =
    when (osFamily) {
      OsFamily.WINDOWS -> "$baseName.exe"
      OsFamily.MACOS,
      OsFamily.LINUX -> baseName
    }

  fun scriptName(baseName: String): String =
    when (osFamily) {
      OsFamily.WINDOWS -> "$baseName.bat"
      OsFamily.MACOS,
      OsFamily.LINUX -> baseName
    }
}

@SingleIn(AppScope::class)
@Inject
class HostPlatformDetector {
  val current: HostPlatformInfo by lazy { detect() }

  fun detect(
    osNameProp: String = System.getProperty("os.name", "generic"),
    osArchProp: String = System.getProperty("os.arch", "x86_64"),
    userHomeProp: String = System.getProperty("user.home", "."),
    javaHomeProp: String = System.getProperty("java.home", "."),
  ): HostPlatformInfo {
    val osLower = osNameProp.lowercase()
    val osFamily =
      when {
        "mac" in osLower || "darwin" in osLower -> OsFamily.MACOS
        "win" in osLower -> OsFamily.WINDOWS
        else -> OsFamily.LINUX
      }

    val archLower = osArchProp.lowercase()
    val cpuArch =
      when {
        "aarch64" in archLower || "arm64" in archLower -> CpuArch.ARM64
        else -> CpuArch.X86_64
      }

    return HostPlatformInfo(
      osFamily = osFamily,
      cpuArch = cpuArch,
      userHome = Paths.get(userHomeProp),
      javaHome = Paths.get(javaHomeProp),
    )
  }
}
