package com.plexaudiobooks.di

import android.content.Context
import androidx.room.Room
import com.plexaudiobooks.api.PlexResourcesApi
import com.plexaudiobooks.api.PlexServerApi
import com.plexaudiobooks.api.PlexTvApi
import com.plexaudiobooks.data.local.AudiobookDatabase
import com.plexaudiobooks.util.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(sessionManager: SessionManager): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // CRITICAL: X-Plex-Client-Identifier must come from SessionManager so it
                // matches the clientID we embed in the app.plex.tv/auth URL. Using a
                // hardcoded string here while the URL uses the stored UUID caused the
                // "We were unable to complete this request" auth error.
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("X-Plex-Platform", "Android")
                    .header("X-Plex-Platform-Version", android.os.Build.VERSION.RELEASE)
                    .header("X-Plex-Device", android.os.Build.MODEL)
                    .header("X-Plex-Device-Name", android.os.Build.MODEL)
                    .header("X-Plex-Product", "PlexAudiobooks")
                    .header("X-Plex-Version", "1.0.0")
                    .header("X-Plex-Client-Identifier", sessionManager.clientId)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // Use BODY level to log raw JSON responses — critical for debugging auth issues
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

    @Provides
    @Singleton
    @Named("plextv")
    fun providePlexTvOkHttpClient(baseClient: OkHttpClient): OkHttpClient =
        baseClient.newBuilder()
            .addInterceptor { chain ->
                // Origin header is required by app.plex.tv auth JS to validate the PIN.
                // Without it, the auth page shows "We were unable to complete this request".
                val request = chain.request().newBuilder()
                    .header("Origin", "https://app.plex.tv")
                    .build()
                chain.proceed(request)
            }
            .build()

    @Provides
    @Singleton
    @Named("plextv")
    fun providePlexTvRetrofit(@Named("plextv") client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://plex.tv/api/v2/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun providePlexTvApi(@Named("plextv") retrofit: Retrofit): PlexTvApi =
        retrofit.create(PlexTvApi::class.java)

    @Provides
    @Singleton
    @Named("server")
    fun providePlexServerRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun providePlexServerApi(@Named("server") retrofit: Retrofit): PlexServerApi =
        retrofit.create(PlexServerApi::class.java)

    @Provides
    @Singleton
    @Named("resources")
    fun providePlexResourcesRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://clients.plex.tv/api/v2/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun providePlexResourcesApi(@Named("resources") retrofit: Retrofit): PlexResourcesApi =
        retrofit.create(PlexResourcesApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudiobookDatabase =
        Room.databaseBuilder(context, AudiobookDatabase::class.java, "audiobooks.db")
            .addMigrations(AudiobookDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideProgressDao(db: AudiobookDatabase) = db.progressDao()
    @Provides fun provideDownloadDao(db: AudiobookDatabase) = db.downloadDao()
    @Provides fun provideLibraryDao(db: AudiobookDatabase) = db.libraryDao()
    @Provides fun provideLibraryPagingDao(db: AudiobookDatabase) = db.libraryPagingDao()
}
