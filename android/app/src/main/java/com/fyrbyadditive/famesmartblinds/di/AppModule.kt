package com.fyrbyadditive.famesmartblinds.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for app-level dependencies.
 *
 * Note: Most dependencies use constructor injection with @Singleton and @Inject
 * annotations directly on the classes (DeviceRepository, HttpClient, DeviceDiscovery,
 * BleManager), so no @Provides methods are needed here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // All dependencies are provided via constructor injection with @Inject and @Singleton
    // on the class definitions themselves. This module is kept for potential future bindings.
}
