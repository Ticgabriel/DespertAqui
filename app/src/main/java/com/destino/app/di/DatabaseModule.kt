package com.destino.app.di

import android.content.Context
import androidx.room.Room
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.AlarmRepositoryImpl
import com.destino.app.data.local.DestinoDatabase
import com.destino.app.data.local.FavoriteRepository
import com.destino.app.data.local.FavoriteRepositoryImpl
import com.destino.app.data.local.HistoryRepository
import com.destino.app.data.local.HistoryRepositoryImpl
import com.destino.app.data.local.OccurrenceRepository
import com.destino.app.data.local.OccurrenceRepositoryImpl
import com.destino.app.data.local.ScheduleRepository
import com.destino.app.data.local.ScheduleRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DestinoDatabase {
        return Room.databaseBuilder(
            context,
            DestinoDatabase::class.java,
            "destino_database.db"
        )
            .addMigrations(DestinoDatabase.MIGRATION_1_2, DestinoDatabase.MIGRATION_2_3)
            .build()
    }

    @Provides
    @Singleton
    fun provideAlarmRepository(database: DestinoDatabase): AlarmRepository {
        return AlarmRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideFavoriteRepository(database: DestinoDatabase): FavoriteRepository {
        return FavoriteRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideHistoryRepository(database: DestinoDatabase): HistoryRepository {
        return HistoryRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideScheduleRepository(database: DestinoDatabase): ScheduleRepository {
        return ScheduleRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideOccurrenceRepository(database: DestinoDatabase): OccurrenceRepository {
        return OccurrenceRepositoryImpl(database)
    }
}
