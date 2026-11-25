package app.viki.sdk

import app.viki.di.AppScope
import com.android.tools.analytics.CommonMetricsData
import com.google.wireless.android.sdk.stats.ProductDetails
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import java.nio.file.Paths

/** Bridges NIO paths, which we keep for normalize/startsWith, to kotlinx-io for actual file IO. */
internal fun Path.toKxPath(): kotlinx.io.files.Path = kotlinx.io.files.Path(toString())

enum class OsFamily(val displayName: String) {
  MACOS("macOS"),
  WINDOWS("Windows"),
  LINUX("Linux"),
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
  private val envProvider: (String) -> String? = System::getenv,
) {
  /** The value of an environment variable, treating blank as unset. */
  fun env(name: String): String? = envProvider(name)?.takeIf { it.isNotBlank() }

  /**
   * An environment variable as a path. Tolerates the quotes that `set VAR="C:\..."` leaves in the
   * value on Windows, and treats values the filesystem can't represent as unset.
   */
  fun envPath(name: String): Path? =
    env(name)
      ?.trim()
      ?.trim('"')
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { Paths.get(it) }.getOrNull() }

  val defaultSdkRoot: Path
    get() =
      when (osFamily) {
        OsFamily.MACOS -> userHome.resolve("Library/Android/sdk")
        OsFamily.WINDOWS ->
          (envPath("LOCALAPPDATA") ?: userHome.resolve("AppData/Local")).resolve("Android/Sdk")
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
          // Visual Studio / .NET default
          envPath("ProgramFiles(x86)")?.let { add(it.resolve("Android/android-sdk")) }
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

  /**
   * Where the emulator looks for AVDs. sdklib's `AndroidLocationsSingleton` ignores
   * `ANDROID_EMULATOR_HOME`, and falls back to a legacy `XDG_CONFIG_HOME/.android` the emulator
   * never reads, so we resolve this ourselves.
   */
  val avdHomeDir: Path
    get() {
      envPath("ANDROID_AVD_HOME")?.let {
        return it
      }
      val dotAndroid =
        envPath("ANDROID_EMULATOR_HOME")
          ?: envPath("ANDROID_USER_HOME")
          ?: (envPath("ANDROID_PREFS_ROOT") ?: envPath("ANDROID_SDK_HOME"))?.let {
            if (it.fileName?.toString() == ".android") it else it.resolve(".android")
          }
          ?: userHome.resolve(".android")
      return dotAndroid.resolve("avd")
    }

  val vikiConfigDir: Path
    get() = userHome.resolve(".viki")

  fun executableName(baseName: String): String =
    when (osFamily) {
      OsFamily.WINDOWS -> "$baseName.exe"
      OsFamily.MACOS,
      OsFamily.LINUX -> baseName
    }
}

@SingleIn(AppScope::class)
@Inject
class HostPlatformDetector() {
  private var overridePlatform: HostPlatformInfo? = null

  internal constructor(platform: HostPlatformInfo) : this() {
    this.overridePlatform = platform
  }

  val current: HostPlatformInfo by lazy { overridePlatform ?: detect() }

  fun detect(
    osNameProp: String = System.getProperty("os.name", "generic"),
    osArchProp: String = hostArch(),
    userHomeProp: String = System.getProperty("user.home", "."),
    envProvider: (String) -> String? = System::getenv,
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
      envProvider = envProvider,
    )
  }

  /**
   * `os.arch` is the JVM's architecture. Under Rosetta or Windows-on-ARM emulation that differs
   * from the host's, which is what sdklib uses to choose an emulator build, so ask sdklib.
   */
  private fun hostArch(): String =
    if (CommonMetricsData.osArchitecture == ProductDetails.CpuArchitecture.X86_ON_ARM) "aarch64"
    else System.getProperty("os.arch", "x86_64")
}
