package app.viki.avd

import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.SdkStatus
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EmulatorRunStatus {
  STOPPED,
  BOOTING,
  RUNNING,
}

data class EmulatorInstanceState(
  val avdId: String,
  val status: EmulatorRunStatus = EmulatorRunStatus.STOPPED,
  val adbSerial: String? = null,
  val lastError: String? = null,
)

/** All state is confined to Dispatchers.Main; blocking work hops to IO. */
@SingleIn(AppScope::class)
@Inject
class EmulatorProcessManager(
  private val sdkLocator: AndroidSdkLocator,
  private val appScope: CoroutineScope,
) {
  private val spawnedProcesses = mutableMapOf<String, Process>()
  private val recentErrors = mutableMapOf<String, String>()

  private val _states = MutableStateFlow<Map<String, EmulatorInstanceState>>(emptyMap())
  val states: StateFlow<Map<String, EmulatorInstanceState>> = _states.asStateFlow()

  init {
    appScope.launch {
      while (isActive) {
        runCatching { pollRunningEmulators() }
        delay(2_500L)
      }
    }
  }

  fun clearError(avdId: String) {
    recentErrors.remove(avdId)
    val existing = _states.value[avdId] ?: return
    _states.value += avdId to existing.copy(lastError = null)
  }

  suspend fun launchAvd(
    avdId: String,
    coldBoot: Boolean = false,
  ): Result<Unit> =
    runCatching<Unit> {
        val status = sdkLocator.sdkStatus.value
        val tools = (status as? SdkStatus.Ready)?.tools ?: error("Android SDK is not ready.")
        val emulatorBin =
          tools.emulatorBin ?: error("Android Emulator binary not found in ${tools.sdkRoot}")

        recentErrors.remove(avdId)

        // Optimistically mark as BOOTING so the UI responds immediately
        _states.value +=
          avdId to EmulatorInstanceState(avdId = avdId, status = EmulatorRunStatus.BOOTING)

        val command = buildList {
          add(emulatorBin.toString())
          add("-avd")
          add(avdId)
          add("-netdelay")
          add("none")
          add("-netspeed")
          add("full")
          if (coldBoot) {
            add("-no-snapshot-load")
          }
        }

        val pb =
          ProcessBuilder(command).apply {
            directory(tools.sdkRoot.toFile())
            redirectErrorStream(true)
            val env = environment()
            env["ANDROID_HOME"] = tools.sdkRoot.toAbsolutePath().toString()
            env["ANDROID_SDK_ROOT"] = tools.sdkRoot.toAbsolutePath().toString()
          }

        val process = withContext(Dispatchers.IO) { pb.start() }
        spawnedProcesses[avdId] = process

        // Monitor process output in background in case it fails to boot
        appScope.launch {
          val logLines = ArrayDeque<String>(15)
          val exitCode =
            withContext(Dispatchers.IO) {
              runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                  lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                      if (logLines.size >= 15) logLines.removeFirst()
                      logLines.addLast(trimmed)
                    }
                  }
                }
              }
              process.waitFor()
            }
          spawnedProcesses.remove(avdId, process)
          if (exitCode != 0) {
            val errorSummary =
              logLines.lastOrNull {
                it.contains("ERROR", ignoreCase = true) || it.contains("FATAL", ignoreCase = true)
              } ?: logLines.lastOrNull() ?: "Emulator exited with code $exitCode"
            recentErrors[avdId] = errorSummary
          }
          pollRunningEmulators()
        }
      }
      .onFailure { err ->
        val message = err.message ?: "Failed to start emulator"
        recentErrors[avdId] = message
        _states.value +=
          avdId to
            EmulatorInstanceState(
              avdId = avdId,
              status = EmulatorRunStatus.STOPPED,
              lastError = message,
            )
      }

  suspend fun stopAvd(avdId: String): Result<Unit> = runCatching {
    val currentState = _states.value[avdId]
    val adbBin = (sdkLocator.sdkStatus.value as? SdkStatus.Ready)?.tools?.adbBin

    if (adbBin != null && currentState?.adbSerial != null) {
      withContext(Dispatchers.IO) {
        val killProcess =
          ProcessBuilder(
              adbBin.toString(),
              "-s",
              currentState.adbSerial,
              "emu",
              "kill",
            )
            .redirectErrorStream(true)
            .start()
        killProcess.waitFor(5, TimeUnit.SECONDS)
      }
    }

    spawnedProcesses.remove(avdId)?.let { proc ->
      if (proc.isAlive) {
        proc.destroy()
      }
    }

    delay(500L)
    pollRunningEmulators()
  }

  suspend fun pollRunningEmulators() {
    val adbBin = (sdkLocator.sdkStatus.value as? SdkStatus.Ready)?.tools?.adbBin
    val discoveredByAvdId = linkedMapOf<String, EmulatorInstanceState>()

    if (adbBin != null) {
      val emulators =
        withContext(Dispatchers.IO) {
          queryAdbEmulatorSerials(adbBin).mapNotNull { serial ->
            val avdId = queryAvdIdForSerial(adbBin, serial) ?: return@mapNotNull null
            Triple(serial, avdId, isBootCompleted(adbBin, serial))
          }
        }
      for ((serial, avdId, bootCompleted) in emulators) {
        val runStatus = if (bootCompleted) EmulatorRunStatus.RUNNING else EmulatorRunStatus.BOOTING
        discoveredByAvdId[avdId] =
          EmulatorInstanceState(
            avdId = avdId,
            status = runStatus,
            adbSerial = serial,
            lastError = recentErrors[avdId],
          )
      }
    }

    // Keep any freshly-spawned processes that haven't registered with adb yet as BOOTING
    for ((avdId, proc) in spawnedProcesses) {
      if (proc.isAlive && avdId !in discoveredByAvdId) {
        discoveredByAvdId[avdId] =
          EmulatorInstanceState(
            avdId = avdId,
            status = EmulatorRunStatus.BOOTING,
            adbSerial = null,
            lastError = recentErrors[avdId],
          )
      }
    }

    // Preserve recent error messages for stopped AVDs
    for ((avdId, errorMsg) in recentErrors) {
      if (avdId !in discoveredByAvdId) {
        discoveredByAvdId[avdId] =
          EmulatorInstanceState(
            avdId = avdId,
            status = EmulatorRunStatus.STOPPED,
            adbSerial = null,
            lastError = errorMsg,
          )
      }
    }

    _states.value = discoveredByAvdId
  }

  private fun queryAdbEmulatorSerials(adbBin: Path): List<String> = runCatching {
    val proc = ProcessBuilder(adbBin.toString(), "devices").redirectErrorStream(true).start()
    val lines = proc.inputStream.bufferedReader().use { it.readLines() }
    proc.waitFor(4, TimeUnit.SECONDS)
    lines
      .drop(1)
      .map { it.trim() }
      .filter { it.startsWith("emulator-") }
      .map { it.substringBefore('\t').substringBefore(' ').trim() }
  }
    .getOrDefault(emptyList())

  private fun queryAvdIdForSerial(adbBin: Path, serial: String): String? = runCatching {
    val proc =
      ProcessBuilder(adbBin.toString(), "-s", serial, "emu", "avd", "name")
        .redirectErrorStream(true)
        .start()
    val lines = proc.inputStream.bufferedReader().use { it.readLines() }
    proc.waitFor(3, TimeUnit.SECONDS)
    lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "OK" && !it.startsWith("KO:") }
  }
    .getOrNull()

  private fun isBootCompleted(adbBin: Path, serial: String): Boolean = runCatching {
    val proc =
      ProcessBuilder(adbBin.toString(), "-s", serial, "shell", "getprop", "sys.boot_completed")
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
    proc.waitFor(3, TimeUnit.SECONDS)
    out == "1"
  }
    .getOrDefault(false)
}
