package app.viki.sdk

import app.viki.di.AppScope
import com.android.prefs.AndroidLocationsSingleton
import com.android.repository.api.Channel
import com.android.repository.api.ProgressIndicatorAdapter
import com.android.repository.api.SettingsController
import com.android.repository.impl.installer.BasicInstallerFactory
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.legacy.LegacyDownloader
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.io.files.SystemFileSystem

data class SdkInstallProgress(
  val stageTitle: String,
  val detailMessage: String,
  val fraction: Float?,
)

@SingleIn(AppScope::class)
@Inject
class AndroidSdkInstaller(private val sdkLocator: AndroidSdkLocator) {
  private val settings =
    object : SettingsController {
      override fun getForceHttp() = false

      override fun setForceHttp(force: Boolean) {}

      override fun getChannel(): Channel = Channel.create(Channel.DEFAULT_ID)
    }
  private val downloader = LegacyDownloader(settings)

  /**
   * Installs or repairs the Android SDK at [targetSdkRoot] so that `platform-tools` and `emulator`
   * are installed and licensed.
   */
  fun installOrRepairSdk(targetSdkRoot: Path): Flow<SdkInstallProgress> = flow {
    val sdkRoot = targetSdkRoot.toAbsolutePath().normalize()
    SystemFileSystem.createDirectories(sdkRoot.toKxPath())

    val tools = sdkLocator.inspectSdkRoot(sdkRoot)
    val packagesToInstall = buildList {
      if (!tools.hasPlatformTools) add("platform-tools")
      if (!tools.hasEmulator) add("emulator")
    }
    if (packagesToInstall.isNotEmpty()) {
      emitAll(
        installPackages(sdkRoot, packagesToInstall, "Installing Android Emulator & Platform Tools")
      )
    }

    sdkLocator.setCustomSdkRoot(sdkRoot)
    val finalStatus = sdkLocator.refresh()
    if (finalStatus !is SdkStatus.Ready) {
      val missing =
        (finalStatus as? SdkStatus.Incomplete)?.tools?.missingComponents?.joinToString(", ")
          ?: "unknown components"
      error("SDK installation finished, but some components are still missing: $missing")
    }

    emit(
      SdkInstallProgress(
        stageTitle = "Android SDK Ready",
        detailMessage = "Android SDK is configured at $sdkRoot",
        fraction = 1.0f,
      )
    )
  }
    .flowOn(Dispatchers.IO)

  /** Installs specific SDK packages (e.g. `platforms;android-35` and a system image). */
  fun installSdkPackages(
    packages: List<String>,
    stageTitle: String = "Downloading Android System Image",
  ): Flow<SdkInstallProgress> {
    val sdkRoot =
      when (val status = sdkLocator.sdkStatus.value) {
        is SdkStatus.Ready -> status.tools.sdkRoot
        is SdkStatus.Incomplete -> status.tools.sdkRoot
        is SdkStatus.NotFound -> error("Android SDK is not configured.")
      }
    return installPackages(sdkRoot, packages, stageTitle)
  }

  /**
   * Checks whether a package path (such as
   * `system-images;android-35;google_apis_playstore;arm64-v8a`) is already installed.
   */
  fun isPackageInstalledOnDisk(sdkRoot: Path, packageId: String): Boolean {
    val packageDir = sdkRoot.resolve(packageId.replace(';', '/'))
    return packageDir.exists() &&
      packageDir.isDirectory() &&
      (packageDir.resolve("package.xml").isRegularFile() ||
        packageDir.resolve("source.properties").isRegularFile())
  }

  private fun installPackages(
    sdkRoot: Path,
    packages: List<String>,
    stageTitle: String,
  ): Flow<SdkInstallProgress> = channelFlow {
    var text = "Preparing ${packages.joinToString(", ")}..."
    var lastError: String? = null
    var packageIndex = 0
    val progress =
      object : ProgressIndicatorAdapter() {
        private var fraction = 0.0

        override fun setText(s: String?) {
          text = s ?: return
          report()
        }

        override fun setFraction(fraction: Double) {
          this.fraction = fraction
          report()
        }

        override fun getFraction() = fraction

        override fun logError(s: String, e: Throwable?) {
          lastError = s
        }

        private fun report() {
          val overall = (packageIndex + fraction) / packages.size
          trySend(SdkInstallProgress(stageTitle, text, overall.toFloat().coerceIn(0f, 1f)))
        }
      }
    progress.setText(text)

    val handler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, sdkRoot)
    val repoManager = handler.getRepoManager(progress)
    repoManager.loadSynchronously(0, progress, downloader, settings)
    val remotePackages = repoManager.packages.remotePackages

    for ((index, packageId) in packages.withIndex()) {
      packageIndex = index
      val remote =
        remotePackages[packageId]
          ?: error("$packageId is not available from Google's SDK repository")
      remote.license?.setAccepted(sdkRoot)
      val installer = BasicInstallerFactory().createInstaller(remote, repoManager, downloader)
      check(installer.prepare(progress) && installer.complete(progress)) {
        lastError ?: "Failed to install $packageId"
      }
    }
    repoManager.markLocalCacheInvalid()

    send(SdkInstallProgress(stageTitle, "Installed ${packages.joinToString(", ")}", 1.0f))
  }
    .flowOn(Dispatchers.IO)
}
