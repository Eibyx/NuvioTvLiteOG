package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.cloudstorage.AndroidCloudIndexStore
import com.nuvio.tv.core.cloudstorage.AndroidCloudPersistence
import com.nuvio.tv.core.cloudstorage.CloudIndexLimits
import com.nuvio.tv.core.cloudstorage.CloudStorageRepository
import com.nuvio.tv.core.cloudstorage.CloudStreams
import com.nuvio.tv.core.cloudstorage.CloudTitleAliases
import com.nuvio.tv.core.cloudstorage.LocalCloudStreamServer
import com.nuvio.tv.core.cloudstorage.OkHttpCloudHttp
import com.nuvio.tv.core.device.DeviceMemoryTier
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudHttpClient

/**
 * Cloud storage (Google Drive, TeraBox). Its HTTP client is its own, without the app's logging
 * or caching interceptors, so cookies and tokens never reach a log or a disk cache.
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudStorageModule {
    @Provides
    @Singleton
    @CloudHttpClient
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        @CloudHttpClient client: OkHttpClient,
        profileDataStore: ProfileDataStore
    ): CloudStorageRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = CloudStorageRepository(
            persistence = AndroidCloudPersistence(context),
            indexStore = AndroidCloudIndexStore(context),
            http = OkHttpCloudHttp(client.newBuilder().followRedirects(true).followSslRedirects(true).build()),
            scope = scope,
            limits = CloudIndexLimits.forDevice(DeviceMemoryTier.isLowRam, DeviceMemoryTier.isConstrained)
        )
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        return repository
    }

    @Provides
    @Singleton
    fun streams(
        repository: CloudStorageRepository,
        @CloudHttpClient client: OkHttpClient,
        metaRepository: MetaRepository,
        tmdbMetadata: TmdbMetadataService
    ): CloudStreams = CloudStreams(
        repository = repository,
        metaRepository = metaRepository,
        tmdbMetadata = tmdbMetadata,
        aliases = CloudTitleAliases(OkHttpCloudHttp(client.newBuilder().followRedirects(true).build())),
        // Not started until a cloud file is played; stops after five idle minutes.
        server = LocalCloudStreamServer(
            client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build(),
            // 2 MB blocks: 3 at a time on boxes with little RAM (about 8 MB per stream), 4 otherwise.
            parallelReads = if (DeviceMemoryTier.isLowRam) 3 else 4,
            chunkBytes = 2 * 1024 * 1024
        )
    )

    @Provides
    @IntoSet
    fun credentialStore(repository: CloudStorageRepository): ProfileScopedCredentialStore = repository
}
