package app.viki

import app.viki.avd.AvdRepository
import app.viki.avd.DesignerCatalog
import app.viki.avd.DeviceFormFactor
import app.viki.di.VikiAppGraph
import app.viki.sdk.AndroidSdkInstaller
import app.viki.sdk.AndroidSdkLocator
import app.viki.sdk.CpuArch
import app.viki.sdk.HostPlatformDetector
import app.viki.sdk.OsFamily
import app.viki.sdk.SdkStatus
import app.viki.ui.screens.CreateDeviceScreen
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.screens.SdkSetupScreen
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.InternalCircuitApi
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.test.FakeNavigator
import com.slack.circuit.test.test
import dev.zacsweers.metro.createGraph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VikiTests {

  @Test
  fun `HostPlatformDetector resolves OS, CPU architecture, and standard SDK paths`() {
    val detector = HostPlatformDetector()

    val macArm =
      detector.detect(
        osNameProp = "Mac OS X",
        osArchProp = "aarch64",
        userHomeProp = "/Users/designer",
      )
    assertEquals(OsFamily.MACOS, macArm.osFamily)
    assertEquals(CpuArch.ARM64, macArm.cpuArch)
    assertEquals("/Users/designer/Library/Android/sdk", macArm.defaultSdkRoot.toString())

    val linuxX64 =
      detector.detect(
        osNameProp = "Linux",
        osArchProp = "amd64",
        userHomeProp = "/home/designer",
      )
    assertEquals(OsFamily.LINUX, linuxX64.osFamily)
    assertEquals(CpuArch.X86_64, linuxX64.cpuArch)
    assertEquals("/home/designer/Android/Sdk", linuxX64.defaultSdkRoot.toString())
  }

  @Test
  fun `AndroidSdkLocator identifies incomplete and complete SDK directories`() {
    val tempSdk = Files.createTempDirectory("viki-test-sdk")
    try {
      val detector = HostPlatformDetector()
      val locator = AndroidSdkLocator(detector)

      // Empty dir -> nothing installed
      val emptyInspection = locator.inspectSdkRoot(tempSdk)
      assertEquals(false, emptyInspection.isComplete)
      assertEquals(2, emptyInspection.missingComponents.size)

      // Populate platform-tools, then emulator
      val platform = detector.current

      val platformTools = tempSdk.resolve("platform-tools")
      Files.createDirectories(platformTools)
      Files.writeString(platformTools.resolve(platform.executableName("adb")), "#!/bin/sh\n")

      val partialInspection = locator.inspectSdkRoot(tempSdk)
      assertEquals(true, partialInspection.hasPlatformTools)
      assertEquals(false, partialInspection.hasEmulator)

      val emulatorDir = tempSdk.resolve("emulator")
      Files.createDirectories(emulatorDir)
      Files.writeString(emulatorDir.resolve(platform.executableName("emulator")), "#!/bin/sh\n")

      val completeInspection = locator.inspectSdkRoot(tempSdk)
      assertTrue(completeInspection.isComplete)
      assertTrue(completeInspection.missingComponents.isEmpty())

      val detected =
        locator.detectSdk(
          envProvider = { key -> if (key == "ANDROID_HOME") tempSdk.toString() else null }
        )
      assertTrue(detected is SdkStatus.Ready)
    } finally {
      tempSdk.toFile().deleteRecursively()
    }
  }

  @Test
  fun `AvdRepository parses AVD ini and config files and applies designer hardware overrides`() {
    val tempAvdHome = Files.createTempDirectory("viki-test-avd-home")
    try {
      val detector = HostPlatformDetector()
      val locator = AndroidSdkLocator(detector)
      val installer = AndroidSdkInstaller(locator)
      val repo = AvdRepository(detector, locator, installer)

      val avdId = "Pixel_9_Pro_Android_15"
      val avdDir = tempAvdHome.resolve("$avdId.avd")
      Files.createDirectories(avdDir)

      val rootIni = tempAvdHome.resolve("$avdId.ini")
      Files.writeString(
        rootIni,
        """
        avd.ini.encoding=UTF-8
        path=${avdDir.toAbsolutePath()}
        target=android-35
        """
          .trimIndent(),
      )

      val configIni = avdDir.resolve("config.ini")
      Files.writeString(
        configIni,
        """
        image.sysdir.1=system-images/android-35/google_apis_playstore/arm64-v8a/
        tag.id=google_apis_playstore
        abi.type=arm64-v8a
        """
          .trimIndent(),
      )

      val preset = DesignerCatalog.devicePresets.first { it.id == "pixel_9_pro" }
      val avdManager = repo.avdManager(tempAvdHome)
      val avd = avdManager.getAvd(avdId, false)!!
      avdManager.updateAvd(
        avd,
        avd.properties +
          repo.designerHardwareConfig(preset) +
          ("avd.ini.displayname" to "Pixel 9 Pro (Android 15)"),
      )

      val loaded = repo.loadAvdsFromDirectory(tempAvdHome)
      assertEquals(1, loaded.size)
      val device = loaded.first()
      assertEquals(avdId, device.avdId)
      assertEquals("Pixel 9 Pro (Android 15)", device.displayName)
      assertEquals(35, device.apiLevel)
      assertEquals("Android 15 (API 35)", device.androidReleaseLabel)
      assertEquals(DeviceFormFactor.PHONE, device.formFactor)
      assertEquals(1280, device.widthPx)
      assertEquals(2856, device.heightPx)
      assertEquals(495, device.densityDpi)
      assertEquals("Google Play", device.googlePlayServicesBadge)
    } finally {
      tempAvdHome.toFile().deleteRecursively()
    }
  }

  @OptIn(InternalCircuitApi::class)
  @Test
  fun `Metro VikiAppGraph builds Circuit with all Screen Presenter and UI factories`() {
    val graph = createGraph<VikiAppGraph>()
    val circuit = graph.circuit

    assertNotNull(circuit.ui(SdkSetupScreen(), CircuitContext.EMPTY))
    assertNotNull(circuit.ui(DeviceListScreen, CircuitContext.EMPTY))
    assertNotNull(circuit.ui(CreateDeviceScreen, CircuitContext.EMPTY))

    assertNotNull(circuit.presenter(SdkSetupScreen(), Navigator.NoOp, CircuitContext.EMPTY))
    assertNotNull(circuit.presenter(DeviceListScreen, Navigator.NoOp, CircuitContext.EMPTY))
    assertNotNull(circuit.presenter(CreateDeviceScreen, Navigator.NoOp, CircuitContext.EMPTY))
  }

  @OptIn(InternalCircuitApi::class)
  @Test
  fun `CreateDevicePresenter updates device preset, release, and default name`() = runTest {
    val graph = createGraph<VikiAppGraph>()
    val navigator = FakeNavigator(CreateDeviceScreen)
    val presenter =
      checkNotNull(graph.circuit.presenter(CreateDeviceScreen, navigator, CircuitContext.EMPTY))

    presenter.test {
      val initial = awaitItem() as CreateDeviceScreen.State
      assertEquals(DeviceFormFactor.PHONE, initial.selectedFormFactor)
      assertEquals("Pixel 9 Pro (Android 15)", initial.customDeviceName)

      initial.eventSink(CreateDeviceScreen.Event.SelectFormFactor(DeviceFormFactor.TABLET))
      var current = awaitItem() as CreateDeviceScreen.State
      while (
        current.selectedFormFactor != DeviceFormFactor.TABLET ||
          !current.customDeviceName.startsWith("Pixel Tablet")
      ) {
        current = awaitItem() as CreateDeviceScreen.State
      }
      assertEquals("pixel_tablet", current.selectedPreset.id)
      assertEquals("Pixel Tablet (Android 15)", current.customDeviceName)

      cancelAndIgnoreRemainingEvents()
    }
  }
}
