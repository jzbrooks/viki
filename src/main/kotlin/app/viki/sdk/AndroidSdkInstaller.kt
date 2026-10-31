package app.viki.sdk

import app.viki.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element

data class CmdlineToolsArchiveMetadata(
  val url: String,
  val expectedSizeBytes: Long,
  val expectedChecksumHex: String,
  val checksumAlgorithm: String,
)

data class SdkInstallProgress(
  val stageTitle: String,
  val detailMessage: String,
  val fraction: Float?,
)

@SingleIn(AppScope::class)
@Inject
class AndroidSdkInstaller(
  private val platformDetector: HostPlatformDetector,
  private val sdkLocator: AndroidSdkLocator,
) {
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      // Match the old JDK client's Redirect.NORMAL: never follow https -> http.
      .followSslRedirects(false)
      .connectTimeout(20, TimeUnit.SECONDS)
      .build()

  /**
   * Installs or repairs the Android SDK at [targetSdkRoot] so that `cmdline-tools`,
   * `platform-tools`, and `emulator` are all installed and licensed.
   */
  fun installOrRepairSdk(targetSdkRoot: Path): Flow<SdkInstallProgress> = flow {
    val sdkRoot = targetSdkRoot.toAbsolutePath().normalize()
    SystemFileSystem.createDirectories(sdkRoot.toKxPath())

    val initialTools = sdkLocator.inspectSdkRoot(sdkRoot)

    if (!initialTools.hasCmdlineTools) {
      emit(
        SdkInstallProgress(
          stageTitle = "Fetching SDK metadata",
          detailMessage =
            "Resolving official Android Command-Line Tools package for ${platformDetector.current.osFamily.displayName}...",
          fraction = 0.02f,
        )
      )

      val metadata = resolveCmdlineToolsArchive(platformDetector.current.osFamily)
      val tempZip = Files.createTempFile("viki-cmdline-tools-", ".zip")

      try {
        downloadWithChecksumVerification(
          metadata = metadata,
          destination = tempZip,
          onProgress = { downloadedBytes, totalBytes ->
            val downloadFraction =
              if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
              } else {
                null
              }
            val mbDownloaded = downloadedBytes / (1024 * 1024)
            val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0L
            emit(
              SdkInstallProgress(
                stageTitle = "Downloading Android Command-Line Tools",
                detailMessage =
                  if (mbTotal > 0) {
                    "Downloaded $mbDownloaded MB of $mbTotal MB"
                  } else {
                    "Downloaded $mbDownloaded MB"
                  },
                fraction = downloadFraction?.let { 0.05f + it * 0.40f },
              )
            )
          },
        )

        emit(
          SdkInstallProgress(
            stageTitle = "Extracting Command-Line Tools",
            detailMessage =
              "Unpacking command-line tools into ${sdkRoot.resolve("cmdline-tools/latest")}...",
            fraction = 0.48f,
          )
        )
        extractCmdlineToolsZip(tempZip, sdkRoot)
      } finally {
        SystemFileSystem.delete(tempZip.toKxPath(), mustExist = false)
      }
    }

    val toolsAfterExtract = sdkLocator.inspectSdkRoot(sdkRoot)
    val sdkManagerBin =
      toolsAfterExtract.sdkManagerBin
        ?: error(
          "sdkmanager binary was not found after extracting command-line tools into $sdkRoot"
        )

    emit(
      SdkInstallProgress(
        stageTitle = "Accepting Android SDK Licenses",
        detailMessage = "Configuring standard Android SDK licenses...",
        fraction = 0.55f,
      )
    )
    acceptLicenses(sdkRoot, sdkManagerBin)

    val packagesToInstall = buildList {
      if (!toolsAfterExtract.hasPlatformTools) add("platform-tools")
      if (!toolsAfterExtract.hasEmulator) add("emulator")
    }

    if (packagesToInstall.isNotEmpty()) {
      emit(
        SdkInstallProgress(
          stageTitle = "Installing Android Emulator & Platform Tools",
          detailMessage = "Installing ${packagesToInstall.joinToString(", ")} via sdkmanager...",
          fraction = 0.60f,
        )
      )

      installPackagesInternal(
        sdkRoot = sdkRoot,
        sdkManagerBin = sdkManagerBin,
        packages = packagesToInstall,
        onLine = { line, pkgFraction ->
          emit(
            SdkInstallProgress(
              stageTitle = "Installing Android Emulator & Platform Tools",
              detailMessage = line,
              fraction = pkgFraction?.let { 0.60f + it * 0.38f } ?: 0.75f,
            )
          )
        },
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

  /**
   * Installs specific SDK packages (e.g. `platforms;android-35` and a system image) using
   * `sdkmanager`.
   */
  fun installSdkPackages(
    packages: List<String>,
    stageTitle: String = "Downloading Android System Image",
  ): Flow<SdkInstallProgress> = flow {
    val currentStatus = sdkLocator.sdkStatus.value
    val tools =
      when (currentStatus) {
        is SdkStatus.Ready -> currentStatus.tools
        is SdkStatus.Incomplete -> currentStatus.tools
        is SdkStatus.NotFound -> error("Android SDK is not configured.")
      }
    val sdkManagerBin = tools.sdkManagerBin ?: error("sdkmanager not found in ${tools.sdkRoot}")

    emit(
      SdkInstallProgress(
        stageTitle = stageTitle,
        detailMessage = "Preparing ${packages.joinToString(", ")}...",
        fraction = 0.05f,
      )
    )

    acceptLicenses(tools.sdkRoot, sdkManagerBin)

    installPackagesInternal(
      sdkRoot = tools.sdkRoot,
      sdkManagerBin = sdkManagerBin,
      packages = packages,
      onLine = { line, fraction ->
        emit(
          SdkInstallProgress(
            stageTitle = stageTitle,
            detailMessage = line,
            fraction = fraction,
          )
        )
      },
    )

    emit(
      SdkInstallProgress(
        stageTitle = stageTitle,
        detailMessage = "Installed ${packages.joinToString(", ")}",
        fraction = 1.0f,
      )
    )
  }
    .flowOn(Dispatchers.IO)

  /**
   * Queries `sdkmanager --list_installed` or inspects disk to check if a package path (such as
   * `system-images;android-35;google_apis_playstore;arm64-v8a`) is already installed.
   */
  fun isPackageInstalledOnDisk(sdkRoot: Path, packageId: String): Boolean {
    val relativePath = packageId.replace(';', '/')
    val packageDir = sdkRoot.resolve(relativePath)
    return packageDir.exists() &&
      packageDir.isDirectory() &&
      (packageDir.resolve("package.xml").isRegularFile() ||
        packageDir.resolve("source.properties").isRegularFile())
  }

  internal suspend fun resolveCmdlineToolsArchive(osFamily: OsFamily): CmdlineToolsArchiveMetadata =
    withContext(Dispatchers.IO) {
      runCatching {
        fetchLatestCmdlineToolsFromRepoXml(osFamily)
      }
        .getOrElse { fallbackArchiveMetadata(osFamily) }
    }

  private fun fetchLatestCmdlineToolsFromRepoXml(osFamily: OsFamily): CmdlineToolsArchiveMetadata {
    val request = Request.Builder().url(GOOGLE_REPO_XML_URL).build()
    val call = httpClient.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }

    val dbf =
      DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      }

    val doc =
      call.execute().use { response ->
        if (response.code != 200) error("HTTP ${response.code} fetching repository XML")
        dbf.newDocumentBuilder().parse(response.body.byteStream())
      }
    val remotePackages = doc.getElementsByTagName("remotePackage")

    var bestMetadata: CmdlineToolsArchiveMetadata? = null
    var bestMajorRevision = -1

    for (i in 0 until remotePackages.length) {
      val pkg = remotePackages.item(i) as? Element ?: continue
      if (pkg.getAttribute("path") != "cmdline-tools;latest") continue

      val channelRef = pkg.getElementsByTagName("channelRef").item(0) as? Element
      val channelId = channelRef?.getAttribute("ref") ?: "channel-0"
      // Prefer stable channel-0
      if (channelId != "channel-0") continue

      val revisionEl = pkg.getElementsByTagName("revision").item(0) as? Element
      val majorRev =
        revisionEl?.getElementsByTagName("major")?.item(0)?.textContent?.trim()?.toIntOrNull() ?: 0

      val archives = pkg.getElementsByTagName("archive")
      for (j in 0 until archives.length) {
        val archive = archives.item(j) as? Element ?: continue
        val hostOs = archive.getElementsByTagName("host-os").item(0)?.textContent?.trim()
        if (hostOs != osFamily.repoHostOs) continue

        val complete = archive.getElementsByTagName("complete").item(0) as? Element ?: continue
        val size =
          complete.getElementsByTagName("size").item(0)?.textContent?.trim()?.toLongOrNull()
            ?: continue
        val checksumEl = complete.getElementsByTagName("checksum").item(0) as? Element ?: continue
        val checksumHex = checksumEl.textContent?.trim()?.lowercase() ?: continue
        val checksumType = checksumEl.getAttribute("type").ifBlank { "sha1" }.uppercase()
        val urlText = complete.getElementsByTagName("url").item(0)?.textContent?.trim() ?: continue

        val fullUrl =
          if (urlText.startsWith("https://")) {
            urlText
          } else {
            "$GOOGLE_REPO_BASE_URL$urlText"
          }

        if (majorRev >= bestMajorRevision) {
          bestMajorRevision = majorRev
          bestMetadata =
            CmdlineToolsArchiveMetadata(
              url = fullUrl,
              expectedSizeBytes = size,
              expectedChecksumHex = checksumHex,
              checksumAlgorithm =
                if (checksumType == "SHA256" || checksumType == "SHA-256") "SHA-256" else "SHA-1",
            )
        }
      }
    }

    return bestMetadata ?: fallbackArchiveMetadata(osFamily)
  }

  internal fun fallbackArchiveMetadata(osFamily: OsFamily): CmdlineToolsArchiveMetadata =
    when (osFamily) {
      OsFamily.MACOS ->
        CmdlineToolsArchiveMetadata(
          url = "${GOOGLE_REPO_BASE_URL}commandlinetools-mac-15641748_latest.zip",
          expectedSizeBytes = 152127646L,
          expectedChecksumHex = "b62a5d8cf63ded173b47be867be4ee058ceda6df",
          checksumAlgorithm = "SHA-1",
        )
      OsFamily.LINUX ->
        CmdlineToolsArchiveMetadata(
          url = "${GOOGLE_REPO_BASE_URL}commandlinetools-linux-15641748_latest.zip",
          expectedSizeBytes = 174244366L,
          expectedChecksumHex = "63523a02a975a81102238566f2a16c057d52301e",
          checksumAlgorithm = "SHA-1",
        )
      OsFamily.WINDOWS ->
        CmdlineToolsArchiveMetadata(
          url = "${GOOGLE_REPO_BASE_URL}commandlinetools-win-15641748_latest.zip",
          expectedSizeBytes = 151965705L,
          expectedChecksumHex = "2bea1388b8a248040a340a08ca0638138633f687",
          checksumAlgorithm = "SHA-1",
        )
    }

  private suspend fun downloadWithChecksumVerification(
    metadata: CmdlineToolsArchiveMetadata,
    destination: Path,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
  ) {
    val url = metadata.url.toHttpUrl()
    require(url.isHttps) { "Refusing to download SDK tools over non-HTTPS URI: ${metadata.url}" }

    val call =
      httpClient.newCall(Request.Builder().url(url).build()).apply {
        timeout().timeout(10, TimeUnit.MINUTES)
      }

    val digest = MessageDigest.getInstance(metadata.checksumAlgorithm)
    var downloadedBytes = 0L
    var lastReportedBytes = 0L

    call.execute().use { response ->
      if (response.code != 200) {
        error("Failed to download command-line tools (HTTP ${response.code}) from ${metadata.url}")
      }

      val totalBytes =
        response.body.contentLength().takeIf { it >= 0 } ?: metadata.expectedSizeBytes
      val input = response.body.byteStream()
      SystemFileSystem.sink(destination.toKxPath()).buffered().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          output.write(buffer, 0, read)
          digest.update(buffer, 0, read)
          downloadedBytes += read
          if (downloadedBytes - lastReportedBytes >= 512 * 1024 || downloadedBytes == totalBytes) {
            lastReportedBytes = downloadedBytes
            onProgress(downloadedBytes, totalBytes)
          }
        }
      }
    }

    val actualChecksumHex = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actualChecksumHex.equals(metadata.expectedChecksumHex, ignoreCase = true)) {
      SystemFileSystem.delete(destination.toKxPath(), mustExist = false)
      error(
        "Checksum verification failed for ${metadata.url}: " +
          "expected ${metadata.expectedChecksumHex} (${metadata.checksumAlgorithm}), got $actualChecksumHex"
      )
    }
  }

  internal fun extractCmdlineToolsZip(zipFile: Path, sdkRoot: Path) {
    val cmdlineLatestDir = sdkRoot.resolve("cmdline-tools/latest").toAbsolutePath().normalize()
    val tempExtractDir =
      sdkRoot.resolve("cmdline-tools/.extracting-tmp").toAbsolutePath().normalize()

    if (tempExtractDir.exists()) {
      tempExtractDir.toFile().deleteRecursively()
    }
    SystemFileSystem.createDirectories(tempExtractDir.toKxPath())

    ZipInputStream(SystemFileSystem.source(zipFile.toKxPath()).buffered().asInputStream()).use { zis
      ->
      var entry = zis.nextEntry
      while (entry != null) {
        // The official zip has top-level `cmdline-tools/...` prefix; strip it so files land
        // directly in `latest/`
        val rawName = entry.name.replace('\\', '/')
        val relativeName = rawName.removePrefix("cmdline-tools/").removePrefix("/")
        if (relativeName.isNotBlank()) {
          val outPath = tempExtractDir.resolve(relativeName).normalize()
          if (!outPath.startsWith(tempExtractDir)) {
            error("Blocked Zip Slip path traversal entry: ${entry.name}")
          }
          if (entry.isDirectory) {
            SystemFileSystem.createDirectories(outPath.toKxPath())
          } else {
            SystemFileSystem.createDirectories(outPath.parent.toKxPath())
            // transferFrom doesn't close zis, so the next entry stays readable.
            SystemFileSystem.sink(outPath.toKxPath()).buffered().use {
              it.transferFrom(zis.asSource())
            }
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    if (cmdlineLatestDir.exists()) {
      cmdlineLatestDir.toFile().deleteRecursively()
    }
    SystemFileSystem.createDirectories(cmdlineLatestDir.parent.toKxPath())
    SystemFileSystem.atomicMove(tempExtractDir.toKxPath(), cmdlineLatestDir.toKxPath())

    // Ensure binaries in bin/ are executable on macOS and Linux
    if (platformDetector.current.osFamily != OsFamily.WINDOWS) {
      val binDir = cmdlineLatestDir.resolve("bin")
      if (binDir.isDirectory()) {
        Files.walk(binDir).use { stream ->
          stream.filter { Files.isRegularFile(it) }.forEach { file -> makeExecutablePosix(file) }
        }
      }
    }
  }

  private fun acceptLicenses(sdkRoot: Path, sdkManagerBin: Path) {
    val command =
      listOf(
        sdkManagerBin.toString(),
        "--sdk_root=$sdkRoot",
        "--licenses",
      )
    val process = createSdkToolProcessBuilder(sdkRoot, command).start()
    OutputStreamWriter(process.outputStream).use { writer ->
      repeat(40) { writer.write("y\n") }
      writer.flush()
    }
    process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
  }

  private suspend fun installPackagesInternal(
    sdkRoot: Path,
    sdkManagerBin: Path,
    packages: List<String>,
    onLine: suspend (line: String, fraction: Float?) -> Unit,
  ) {
    val command = buildList {
      add(sdkManagerBin.toString())
      add("--sdk_root=$sdkRoot")
      addAll(packages)
    }

    val process = createSdkToolProcessBuilder(sdkRoot, command).start()

    // Automatically confirm any license prompts during package install
    Thread {
      runCatching {
        OutputStreamWriter(process.outputStream).use { writer ->
          repeat(25) {
            writer.write("y\n")
            writer.flush()
          }
        }
      }
    }
      .apply { isDaemon = true }
      .start()

    val recentLines = ArrayDeque<String>(20)
    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
      val lineBuffer = StringBuilder()
      while (true) {
        val ch = reader.read()
        if (ch == -1) break
        if (ch == '\r'.code || ch == '\n'.code) {
          val text = lineBuffer.toString().trim()
          lineBuffer.clear()
          if (text.isNotEmpty()) {
            if (recentLines.size >= 20) recentLines.removeFirst()
            recentLines.addLast(text)
            val pct = PERCENT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val fraction = pct?.let { (it / 100f).coerceIn(0f, 1f) }
            onLine(text, fraction)
          }
        } else {
          lineBuffer.append(ch.toChar())
        }
      }
    }

    val exitCode = process.waitFor()
    if (exitCode != 0) {
      val tail = recentLines.joinToString("\n")
      error("sdkmanager exited with code $exitCode:\n$tail")
    }

    // Ensure installed emulator & platform-tools binaries have executable permissions on Posix
    if (platformDetector.current.osFamily != OsFamily.WINDOWS) {
      listOf(sdkRoot.resolve("emulator"), sdkRoot.resolve("platform-tools")).forEach { dir ->
        if (dir.isDirectory()) {
          Files.walk(dir, 2).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { makeExecutablePosix(it) }
          }
        }
      }
    }
  }

  fun createSdkToolProcessBuilder(sdkRoot: Path, command: List<String>): ProcessBuilder {
    val platform = platformDetector.current
    return ProcessBuilder(command).apply {
      redirectErrorStream(true)
      val env = environment()
      // Supply current running JVM as JAVA_HOME so designers don't need a separate JDK installed
      env["JAVA_HOME"] = platform.javaHome.toAbsolutePath().toString()
      env["ANDROID_HOME"] = sdkRoot.toAbsolutePath().toString()
      env["ANDROID_SDK_ROOT"] = sdkRoot.toAbsolutePath().toString()
    }
  }

  private fun makeExecutablePosix(path: Path) {
    runCatching {
      val perms = Files.getPosixFilePermissions(path).toMutableSet()
      perms.add(PosixFilePermission.OWNER_EXECUTE)
      perms.add(PosixFilePermission.GROUP_EXECUTE)
      perms.add(PosixFilePermission.OTHERS_EXECUTE)
      Files.setPosixFilePermissions(path, perms)
    }
  }

  companion object {
    private const val GOOGLE_REPO_BASE_URL = "https://dl.google.com/android/repository/"
    private const val GOOGLE_REPO_XML_URL = "${GOOGLE_REPO_BASE_URL}repository2-3.xml"
    private val PERCENT_REGEX = Regex("""(\d{1,3})%""")
  }
}
