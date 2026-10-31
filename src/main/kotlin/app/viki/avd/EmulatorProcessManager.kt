package app.viki.avd

import app.viki.di.AppScope
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.SdkStatus
import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbSession
import com.android.adblib.AdbSessionHost
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.shellAsText
import com.android.adblib.tools.EmulatorConsole
import com.android.adblib.tools.localConsoleAddress
import com.android.adblib.tools.openEmulatorConsole
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

/**
 * All state is confined to Dispatchers.Main; blocking work hops to IO, adb calls go through adblib.
 */
@SingleIn(AppScope::class)
@Inject
class EmulatorProcessManager(
  private val sdkLocator: AndroidSdkLocator,
  private val platformDetector: HostPlatformDetector,
  private val appScope: CoroutineScope,
) {
  private val spawnedProcesses = mutableMapOf<String, Process>()
  private val recentErrors = mutableMapOf<String, String>()

  private val _states = MutableStateFlow<Map<String, EmulatorInstanceState>>(emptyMap())
  val states: StateFlow<Map<String, EmulatorInstanceState>> = _states.asStateFlow()

  private val adbSession: AdbSession by lazy {
    val host = AdbSessionHost()
    AdbSession.create(host, AdbServerChannelProvider.createOpenLocalHost(host))
  }

  /** Online emulator serials, pushed by the adb server. */
  private var onlineSerials = emptyList<String>()
  private val avdIdsBySerial = mutableMapOf<String, String>()

  init {
    appScope.launch {
      adbSession.hostServices
        .trackDevices()
        .retryWhen { _, _ ->
          // adb server not running yet (or SDK not set up): start it and reconnect.
          delay(2.5.seconds)
          startAdbServer()
          true
        }
        .collect { devices ->
          onlineSerials =
            devices.entries
              .filter {
                it.serialNumber.startsWith("emulator-") && it.deviceState == DeviceState.ONLINE
              }
              .map { it.serialNumber }
          avdIdsBySerial.keys.retainAll(onlineSerials.toSet())
          pollRunningEmulators()
        }
    }
    // Nothing pushes boot completion, so poll only while something is booting.
    appScope.launch {
      while (isActive) {
        delay(2.5.seconds)
        if (_states.value.values.any { it.status == EmulatorRunStatus.BOOTING }) {
          runCatching { pollRunningEmulators() }
        }
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
            // Pin the emulator to the same AVD folder Viki reads and creates in.
            env["ANDROID_AVD_HOME"] = platformDetector.current.avdHomeDir.toString()
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
    _states.value[avdId]?.adbSerial?.let { serial ->
      runCatching { withEmulatorConsole(serial) { it.kill() } }
    }

    spawnedProcesses.remove(avdId)?.let { proc ->
      if (proc.isAlive) {
        proc.destroy()
      }
    }

    delay(500.milliseconds)
    pollRunningEmulators()
  }

  suspend fun pollRunningEmulators() {
    val discoveredByAvdId = linkedMapOf<String, EmulatorInstanceState>()

    for (serial in onlineSerials) {
      val avdId = avdIdForSerial(serial) ?: continue
      val runStatus =
        if (isBootCompleted(serial)) EmulatorRunStatus.RUNNING else EmulatorRunStatus.BOOTING
      discoveredByAvdId[avdId] =
        EmulatorInstanceState(
          avdId = avdId,
          status = runStatus,
          adbSerial = serial,
          lastError = recentErrors[avdId],
        )
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

  private suspend fun startAdbServer() {
    val adbBin = (sdkLocator.sdkStatus.value as? SdkStatus.Ready)?.tools?.adbBin ?: return
    withContext(Dispatchers.IO) {
      runCatching {
        val proc =
          ProcessBuilder(adbBin.toString(), "start-server").redirectErrorStream(true).start()
        proc.outputStream.close()
        if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
      }
    }
  }

  private suspend fun avdIdForSerial(serial: String): String? =
    avdIdsBySerial[serial]
      ?: runCatching { withEmulatorConsole(serial) { it.avdName() } }
        .getOrNull()
        ?.also { avdIdsBySerial[serial] = it }

  private suspend fun isBootCompleted(serial: String): Boolean = runCatching {
    adbSession.deviceServices
      .shellAsText(
        DeviceSelector.fromSerialNumber(serial),
        "getprop sys.boot_completed",
        commandTimeout = Duration.ofSeconds(3),
      )
      .stdout
      .trim() == "1"
  }
    .getOrDefault(false)

  private suspend fun <T> withEmulatorConsole(
    serial: String,
    block: suspend (EmulatorConsole) -> T,
  ): T {
    val port = serial.removePrefix("emulator-").toInt()
    return withTimeout(3.seconds) {
      adbSession.openEmulatorConsole(localConsoleAddress(port)).use { block(it) }
    }
  }
}
