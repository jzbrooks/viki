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
import app.viki.ui.createdevice.CreateDevicePresenter
import app.viki.ui.screens.CreateDeviceScreen
import app.viki.ui.screens.DeviceListScreen
import app.viki.ui.screens.SdkSetupScreen
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.InternalCircuitApi
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.presenterOf
import com.slack.circuit.test.FakeNavigator
import com.slack.circuit.test.test
import dev.zacsweers.metro.createGraph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
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

  @Test
  fun `createDesignerAvd creates emulator on first attempt with preinstalled system image and no platform package`() =
    runTest {
      val tempHome = Files.createTempDirectory("viki-test-home")
      try {
        val baseDetector = HostPlatformDetector()
        val isolatedPlatform =
          baseDetector.detect(
            osNameProp = "Mac OS X",
            osArchProp = "aarch64",
            userHomeProp = tempHome.toString(),
            envProvider = { null },
          )
        val detector = HostPlatformDetector(isolatedPlatform)
        val sdkRoot = isolatedPlatform.defaultSdkRoot
        populateSdkTools(sdkRoot, detector)

        val locator = AndroidSdkLocator(detector)
        assertTrue(locator.sdkStatus.value is SdkStatus.Ready)

        val installer = AndroidSdkInstaller(locator)
        val repo = AvdRepository(detector, locator, installer)
        // Warm up sdklib caches before the system image exists on disk
        Files.createDirectories(isolatedPlatform.avdHomeDir)
        repo.refreshDevices()

        populateSystemImage(sdkRoot, detector, apiLevel = 33)

        val release33 = DesignerCatalog.androidReleases.first { it.apiLevel == 33 }
        assertTrue(repo.isReleaseSystemImageInstalled(release33))
        assertFalse(installer.isPackageInstalledOnDisk(sdkRoot, release33.platformPackage))

        val preset = DesignerCatalog.devicePresets.first { it.id == "pixel_8a" }
        val progressSteps =
          repo
            .createDesignerAvd(
              displayName = "Pixel 8a (Android 13)",
              devicePreset = preset,
              releasePreset = release33,
            )
            .toList()

        assertTrue(progressSteps.isNotEmpty())
        assertEquals(1.0f, progressSteps.last().fraction)
        // Ensure no intermediate step prematurely emitted fraction >= 1.0f
        assertTrue(progressSteps.dropLast(1).none { (it.fraction ?: 0f) >= 1.0f })

        val createdDevices = repo.devices.value
        assertEquals(1, createdDevices.size)
        val created = createdDevices.first()
        assertEquals("Pixel_8a_Android_13", created.avdId)
        assertEquals("Pixel 8a (Android 13)", created.displayName)
        assertEquals(33, created.apiLevel)
        assertEquals(preset.widthPx, created.widthPx)
        assertEquals(preset.heightPx, created.heightPx)
        assertEquals(preset.densityDpi, created.densityDpi)
      } finally {
        tempHome.toFile().deleteRecursively()
      }
    }

  @Test
  fun `CreateDevicePresenter completes AVD creation before popping screen`() = runTest {
    val tempHome = Files.createTempDirectory("viki-test-presenter-home")
    try {
      val baseDetector = HostPlatformDetector()
      val isolatedPlatform =
        baseDetector.detect(
          osNameProp = "Mac OS X",
          osArchProp = "aarch64",
          userHomeProp = tempHome.toString(),
          envProvider = { null },
        )
      val detector = HostPlatformDetector(isolatedPlatform)
      val sdkRoot = isolatedPlatform.defaultSdkRoot
      populateCompleteSdkWithSystemImage(sdkRoot, detector, apiLevel = 33)

      val locator = AndroidSdkLocator(detector)
      val installer = AndroidSdkInstaller(locator)
      val repo = AvdRepository(detector, locator, installer)
      val navigator = FakeNavigator(CreateDeviceScreen)

      val presenter = presenterOf {
        CreateDevicePresenter(
          navigator = navigator,
          platformDetector = detector,
          avdRepository = repo,
        )
      }

      presenter.test {
        val initial = awaitItem()
        val release33Option = initial.releaseOptions.first { it.preset.apiLevel == 33 }
        assertTrue(release33Option.isSystemImageDownloaded)

        initial.eventSink(CreateDeviceScreen.Event.SelectRelease(release33Option))
        var state = awaitItem()
        while (state.selectedRelease.preset.apiLevel != 33) {
          state = awaitItem()
        }

        state.eventSink(CreateDeviceScreen.Event.CreateClicked)
        navigator.awaitPop()

        assertEquals(1, repo.devices.value.size)
        assertEquals("Pixel 9 Pro (Android 13)", repo.devices.value.first().displayName)
        cancelAndIgnoreRemainingEvents()
      }
    } finally {
      tempHome.toFile().deleteRecursively()
    }
  }

  private fun populateCompleteSdkWithSystemImage(
    sdkRoot: Path,
    detector: HostPlatformDetector,
    apiLevel: Int,
  ) {
    populateSdkTools(sdkRoot, detector)
    populateSystemImage(sdkRoot, detector, apiLevel)
  }

  private fun populateSdkTools(
    sdkRoot: Path,
    detector: HostPlatformDetector,
  ) {
    val platform = detector.current
    val platformTools = sdkRoot.resolve("platform-tools")
    Files.createDirectories(platformTools)
    Files.writeString(platformTools.resolve(platform.executableName("adb")), "#!/bin/sh\n")

    val emulatorDir = sdkRoot.resolve("emulator")
    Files.createDirectories(emulatorDir)
    Files.writeString(emulatorDir.resolve(platform.executableName("emulator")), "#!/bin/sh\n")
  }

  private fun populateSystemImage(
    sdkRoot: Path,
    detector: HostPlatformDetector,
    apiLevel: Int,
  ) {
    val platform = detector.current
    val abi = platform.cpuArch.abiName
    val sysImgDir = sdkRoot.resolve("system-images/android-$apiLevel/google_apis_playstore/$abi")
    Files.createDirectories(sysImgDir)
    Files.writeString(sysImgDir.resolve("system.img"), "fake-system-img")
    Files.writeString(sysImgDir.resolve("userdata.img"), "fake-userdata-img")
    Files.writeString(
      sysImgDir.resolve("build.prop"),
      """
      ro.product.cpu.abi=$abi
      ro.build.version.sdk=$apiLevel
      """
        .trimIndent(),
    )
    Files.writeString(
      sysImgDir.resolve("package.xml"),
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <ns2:repository
          xmlns:ns2="http://schemas.android.com/repository/android/common/02"
          xmlns:ns14="http://schemas.android.com/sdk/android/repo/sys-img2/05"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <localPackage path="system-images;android-$apiLevel;google_apis_playstore;$abi" obsolete="false">
          <type-details xsi:type="ns14:sysImgDetailsType">
            <api-level>$apiLevel</api-level>
            <base-extension>true</base-extension>
            <tag>
              <id>google_apis_playstore</id>
              <display>Google Play</display>
            </tag>
            <vendor>
              <id>google</id>
              <display>Google Inc.</display>
            </vendor>
            <abi>$abi</abi>
            <abis>$abi</abis>
          </type-details>
          <revision>
            <major>9</major>
          </revision>
          <display-name>Google Play ARM 64 v8a System Image</display-name>
        </localPackage>
      </ns2:repository>
      """
        .trimIndent(),
    )
  }
}
