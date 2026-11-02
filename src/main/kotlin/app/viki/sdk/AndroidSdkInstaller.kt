package app.viki.sdk

import app.viki.di.AppScope
import com.android.prefs.AndroidLocationsSingleton
import com.android.repository.api.Channel
import com.android.repository.api.ProgressIndicatorAdapter
import com.android.repository.api.SettingsController
import com.android.repository.api.UpdatablePackage
import com.android.repository.impl.installer.BasicInstallerFactory
import com.android.repository.impl.meta.RepositoryPackages
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.legacy.LegacyDownloader
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
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
  private val updateMutex = Mutex()

  val updateProgress: StateFlow<SdkInstallProgress?>
    field = MutableStateFlow<SdkInstallProgress?>(null)

  val updateError: StateFlow<String?>
    field = MutableStateFlow<String?>(null)

  fun dismissUpdateError() {
    updateError.value = null
  }

  internal fun setUpdateStateForTesting(
    progress: SdkInstallProgress? = null,
    error: String? = null,
  ) {
    updateProgress.value = progress
    updateError.value = error
  }

  internal fun isVikiManagedPackage(packageId: String): Boolean =
    packageId == "platform-tools" ||
      packageId == "emulator" ||
      packageId.startsWith("system-images;")

  internal fun findUpdatablePackages(packages: RepositoryPackages): List<UpdatablePackage> =
    packages.updatedPkgs
      .filter { it.isUpdate && isVikiManagedPackage(it.path) }
      .sortedBy { it.path }

  /**
   * Checks Google's Android SDK repository for updates to Viki-managed packages (`platform-tools`,
   * `emulator`, and installed `system-images;*`) and installs any newer revisions.
   *
   * @return `true` if one or more SDK packages were updated.
   */
  suspend fun checkAndInstallUpdates(silentCheckFailure: Boolean = true): Boolean {
    if (!updateMutex.tryLock()) return false
    var installedAnyUpdate = false
    try {
      val status = sdkLocator.sdkStatus.value
      val sdkRoot = (status as? SdkStatus.Ready)?.tools?.sdkRoot ?: return false
      if (!silentCheckFailure) {
        updateError.value = null
      }
      var startedInstalling = false
      runCatching {
        updateInstalledPackages(sdkRoot = sdkRoot, emitCheckProgress = !silentCheckFailure)
          .collect { progress ->
            if (progress.stageTitle == "Updating Android SDK") {
              startedInstalling = true
              if (progress.fraction == 1.0f) {
                installedAnyUpdate = true
              }
            }
            updateProgress.value = progress
          }
      }
        .onFailure { err ->
          if (err is CancellationException) throw err
          if (!silentCheckFailure || startedInstalling) {
            updateError.value = err.message ?: "Failed to update Android SDK."
          }
        }
      return installedAnyUpdate
    } finally {
      updateProgress.value = null
      updateMutex.unlock()
    }
  }

  /**
   * Checks for and installs updates to Viki-managed SDK packages at [sdkRoot]. When
   * [emitCheckProgress] is false, this flow emits nothing if all packages are already up to date.
   */
  fun updateInstalledPackages(
    sdkRoot: Path,
    emitCheckProgress: Boolean = false,
  ): Flow<SdkInstallProgress> = channelFlow {
    val checkStageTitle = "Checking for SDK Updates"
    val updateStageTitle = "Updating Android SDK"
    var lastError: String? = null

    val metadataProgress =
      object : ProgressIndicatorAdapter() {
        override fun setText(s: String?) {
          val detail = s ?: return
          if (emitCheckProgress) {
            trySend(SdkInstallProgress(checkStageTitle, detail, null))
          }
        }

        override fun logError(s: String, e: Throwable?) {
          lastError = s
        }
      }

    if (emitCheckProgress) {
      send(
        SdkInstallProgress(
          stageTitle = checkStageTitle,
          detailMessage = "Checking Google's Android SDK repository for updates...",
          fraction = null,
        )
      )
    }

    val normalizedRoot = sdkRoot.toAbsolutePath().normalize()
    val handler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, normalizedRoot)
    val repoManager = handler.getRepoManager(metadataProgress)
    if (emitCheckProgress) {
      repoManager.markLocalCacheInvalid()
    }
    repoManager.loadSynchronously(0, metadataProgress, downloader, settings)

    val checkError = lastError
    if (repoManager.packages.remotePackages.isEmpty() && checkError != null) {
      if (emitCheckProgress) {
        error(checkError)
      }
      return@channelFlow
    }

    val updatable = findUpdatablePackages(repoManager.packages)
    if (updatable.isEmpty()) {
      if (emitCheckProgress) {
        send(
          SdkInstallProgress(
            stageTitle = "Android SDK Up to Date",
            detailMessage = "All installed SDK tools and system images are up to date.",
            fraction = 1.0f,
          )
        )
      }
      return@channelFlow
    }

    var text = "Preparing SDK updates..."
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
          val overall = (packageIndex + fraction) / updatable.size
          trySend(SdkInstallProgress(updateStageTitle, text, overall.toFloat().coerceIn(0f, 1f)))
        }
      }

    for ((index, pkg) in updatable.withIndex()) {
      val remote = pkg.remote ?: continue
      packageIndex = index
      progress.setFraction(0.0)
      val localVersion = pkg.local?.version?.toShortString()
      val remoteVersion = remote.version.toShortString()
      val versionLabel =
        if (localVersion != null) "$localVersion -> $remoteVersion" else remoteVersion
      progress.setText("Updating ${remote.displayName} ($versionLabel)...")

      remote.license?.setAccepted(normalizedRoot)
      val installer = BasicInstallerFactory().createInstaller(remote, repoManager, downloader)
      check(installer.prepare(progress) && installer.complete(progress)) {
        lastError ?: "Failed to update ${pkg.path}"
      }
    }

    repoManager.markLocalCacheInvalid()
    repoManager.reloadLocalIfNeeded(metadataProgress)

    val updatedNames = updatable.joinToString(", ") { it.remote?.displayName ?: it.path }
    send(SdkInstallProgress(updateStageTitle, "Updated $updatedNames", 1.0f))
  }
    .flowOn(Dispatchers.IO)

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
    val metadataProgress =
      object : ProgressIndicatorAdapter() {
        override fun setText(s: String?) {
          text = s ?: return
          trySend(SdkInstallProgress(stageTitle, text, null))
        }

        override fun logError(s: String, e: Throwable?) {
          lastError = s
        }
      }
    metadataProgress.setText(text)

    val handler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, sdkRoot)
    val repoManager = handler.getRepoManager(metadataProgress)
    repoManager.loadSynchronously(0, metadataProgress, downloader, settings)
    val remotePackages = repoManager.packages.remotePackages

    for ((index, packageId) in packages.withIndex()) {
      packageIndex = index
      progress.setFraction(0.0)
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
    repoManager.reloadLocalIfNeeded(metadataProgress)

    send(SdkInstallProgress(stageTitle, "Installed ${packages.joinToString(", ")}", 1.0f))
  }
    .flowOn(Dispatchers.IO)
}
