package app.viki.di

import app.viki.avd.AvdRepository
import app.viki.avd.EmulatorProcessManager
import app.viki.sdk.AndroidSdkLocator
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.ui.Ui
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface VikiAppGraph {
  val circuit: Circuit
  val appCoroutineScope: CoroutineScope
  val avdRepository: AvdRepository
  val emulatorProcessManager: EmulatorProcessManager
  val sdkLocator: AndroidSdkLocator

  @Provides
  @SingleIn(AppScope::class)
  fun provideAppCoroutineScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Provides
  @SingleIn(AppScope::class)
  fun provideCircuit(
    presenterFactories: Set<Presenter.Factory>,
    uiFactories: Set<Ui.Factory>,
  ): Circuit =
    Circuit.Builder()
      .addPresenterFactories(presenterFactories)
      .addUiFactories(uiFactories)
      .lenientNavigationEventDispatcherOwner(true)
      .build()
}
