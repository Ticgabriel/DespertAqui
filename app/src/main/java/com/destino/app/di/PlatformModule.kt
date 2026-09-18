package com.destino.app.di

import com.destino.app.core.model.AlertController
import com.destino.app.core.model.LocationSource
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.platform.alerts.AlertControllerImpl
import com.destino.app.platform.location.FusedLocationSource
import com.destino.app.platform.runtime.MonitoringCoordinatorImpl
import com.destino.app.core.model.PlacesSearchSource
import com.destino.app.platform.places.PlacesSearchSourceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    @Singleton
    abstract fun bindLocationSource(fusedLocationSource: FusedLocationSource): LocationSource

    @Binds
    @Singleton
    abstract fun bindAlertController(alertControllerImpl: AlertControllerImpl): AlertController

    @Binds
    @Singleton
    abstract fun bindMonitoringCoordinator(monitoringCoordinatorImpl: MonitoringCoordinatorImpl): MonitoringCoordinator

    @Binds
    @Singleton
    abstract fun bindPlacesSearchSource(placesSearchSourceImpl: PlacesSearchSourceImpl): PlacesSearchSource

    @Binds
    @Singleton
    abstract fun bindSoundResolver(soundResolverImpl: com.destino.app.platform.alerts.SoundResolverImpl): com.destino.app.platform.alerts.SoundResolver

    @Binds
    @Singleton
    abstract fun bindAudioRouteObserver(audioRouteObserverImpl: com.destino.app.platform.audio.AudioRouteObserverImpl): com.destino.app.platform.audio.AudioRouteObserver

    @Binds
    @Singleton
    abstract fun bindSystemScheduler(systemSchedulerImpl: com.destino.app.platform.schedule.SystemSchedulerImpl): com.destino.app.platform.schedule.SystemScheduler

    @Binds
    @Singleton
    abstract fun bindLocationRequestPlanner(plannerImpl: com.destino.app.core.engine.LocationRequestPlannerImpl): com.destino.app.core.engine.LocationRequestPlanner
}
