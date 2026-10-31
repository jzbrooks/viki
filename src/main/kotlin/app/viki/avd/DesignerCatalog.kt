package app.viki.avd

import app.viki.sdk.CpuArch

enum class DeviceFormFactor(val label: String) {
  PHONE("Phone"),
  FOLDABLE("Foldable"),
  TABLET("Tablet"),
}

data class DevicePreset(
  val id: String,
  val displayName: String,
  val subtitle: String,
  val formFactor: DeviceFormFactor,
  val diagonalInches: String,
  val widthPx: Int,
  val heightPx: Int,
  val densityDpi: Int,
  val densityBucket: String,
  /**
   * Ordered preference of `avdmanager` device definition IDs, falling back gracefully if an older
   * cmdline-tools revision is installed.
   */
  val avdManagerDeviceIds: List<String>,
) {
  val resolutionSummary: String
    get() = "${widthPx} × ${heightPx} px ($densityBucket, ${densityDpi} dpi)"
}

data class AndroidReleasePreset(
  val apiLevel: Int,
  val versionName: String,
  val codename: String,
  val summary: String,
  val isRecommended: Boolean = false,
) {
  val displayTitle: String
    get() = "Android $versionName (API $apiLevel)"

  fun preferredSystemImagePackage(arch: CpuArch): String =
    "system-images;android-$apiLevel;google_apis_playstore;${arch.abiName}"

  fun fallbackSystemImagePackage(arch: CpuArch): String =
    "system-images;android-$apiLevel;google_apis;${arch.abiName}"

  val platformPackage: String
    get() = "platforms;android-$apiLevel"
}

object DesignerCatalog {
  val devicePresets: List<DevicePreset> =
    listOf(
      DevicePreset(
        id = "pixel_9_pro",
        displayName = "Pixel 9 Pro",
        subtitle = "Flagship high-density OLED display",
        formFactor = DeviceFormFactor.PHONE,
        diagonalInches = "6.3\"",
        widthPx = 1280,
        heightPx = 2856,
        densityDpi = 495,
        densityBucket = "xxxhdpi",
        avdManagerDeviceIds = listOf("pixel_9_pro", "pixel_8_pro", "pixel_7_pro", "pixel_6_pro"),
      ),
      DevicePreset(
        id = "pixel_9",
        displayName = "Pixel 9",
        subtitle = "Standard modern Android phone baseline",
        formFactor = DeviceFormFactor.PHONE,
        diagonalInches = "6.3\"",
        widthPx = 1080,
        heightPx = 2424,
        densityDpi = 422,
        densityBucket = "xxhdpi",
        avdManagerDeviceIds = listOf("pixel_9", "pixel_8", "pixel_7", "pixel_6"),
      ),
      DevicePreset(
        id = "pixel_8a",
        displayName = "Pixel 8a",
        subtitle = "Mid-size 6.1\" everyday viewport",
        formFactor = DeviceFormFactor.PHONE,
        diagonalInches = "6.1\"",
        widthPx = 1080,
        heightPx = 2400,
        densityDpi = 430,
        densityBucket = "xxhdpi",
        avdManagerDeviceIds = listOf("pixel_8a", "pixel_7a", "pixel_6a", "pixel_5"),
      ),
      DevicePreset(
        id = "pixel_5",
        displayName = "Compact Phone (Pixel 5)",
        subtitle = "Great for testing compact layouts & small screens",
        formFactor = DeviceFormFactor.PHONE,
        diagonalInches = "6.0\"",
        widthPx = 1080,
        heightPx = 2340,
        densityDpi = 432,
        densityBucket = "xxhdpi",
        avdManagerDeviceIds = listOf("pixel_5", "pixel_4"),
      ),
      DevicePreset(
        id = "pixel_9_pro_fold",
        displayName = "Pixel 9 Pro Fold",
        subtitle = "Unfolded inner canvas & continuity testing",
        formFactor = DeviceFormFactor.FOLDABLE,
        diagonalInches = "8.0\"",
        widthPx = 2076,
        heightPx = 2152,
        densityDpi = 373,
        densityBucket = "xhdpi",
        avdManagerDeviceIds = listOf("pixel_9_pro_fold", "pixel_fold", "pixel_c"),
      ),
      DevicePreset(
        id = "pixel_tablet",
        displayName = "Pixel Tablet",
        subtitle = "Large-screen adaptive & multi-column layouts",
        formFactor = DeviceFormFactor.TABLET,
        diagonalInches = "10.95\"",
        widthPx = 2560,
        heightPx = 1600,
        densityDpi = 276,
        densityBucket = "xhdpi",
        avdManagerDeviceIds = listOf("pixel_tablet", "pixel_c", "Nexus 10"),
      ),
    )

  val androidReleases: List<AndroidReleasePreset> =
    listOf(
      AndroidReleasePreset(
        apiLevel = 35,
        versionName = "15",
        codename = "Vanilla Ice Cream",
        summary = "Edge-to-edge enforcement, predictive back, and Material 3 expressive polish",
        isRecommended = true,
      ),
      AndroidReleasePreset(
        apiLevel = 36,
        versionName = "16",
        codename = "Baklava",
        summary = "Latest Android platform preview & adaptive layout APIs",
        isRecommended = false,
      ),
      AndroidReleasePreset(
        apiLevel = 34,
        versionName = "14",
        codename = "Upside Down Cake",
        summary = "Non-linear font scaling up to 200% & ultra HDR display support",
        isRecommended = false,
      ),
      AndroidReleasePreset(
        apiLevel = 33,
        versionName = "13",
        codename = "Tiramisu",
        summary = "Themed app icons & per-app language preferences",
        isRecommended = false,
      ),
    )

  fun releaseNameForApi(apiLevel: Int?): String =
    when (apiLevel) {
      36 -> "Android 16 (API 36)"
      35 -> "Android 15 (API 35)"
      34 -> "Android 14 (API 34)"
      33 -> "Android 13 (API 33)"
      32 -> "Android 12L (API 32)"
      31 -> "Android 12 (API 31)"
      30 -> "Android 11 (API 30)"
      null -> "Android Virtual Device"
      else -> "Android API $apiLevel"
    }

  fun inferFormFactor(deviceName: String, widthPx: Int, heightPx: Int): DeviceFormFactor {
    val lower = deviceName.lowercase()
    return when {
      "fold" in lower -> DeviceFormFactor.FOLDABLE
      "tablet" in lower || "nexus 10" in lower || "nexus 9" in lower || "pixel_c" in lower ->
        DeviceFormFactor.TABLET
      widthPx > 0 &&
        heightPx > 0 &&
        minOf(widthPx, heightPx).toFloat() / maxOf(widthPx, heightPx).toFloat() > 0.85f ->
        DeviceFormFactor.FOLDABLE
      minOf(widthPx, heightPx) >= 1500 -> DeviceFormFactor.TABLET
      else -> DeviceFormFactor.PHONE
    }
  }
}
