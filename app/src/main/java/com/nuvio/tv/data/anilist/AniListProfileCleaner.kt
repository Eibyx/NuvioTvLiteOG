package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow

@Singleton
class AniListProfileCleaner @Inject internal constructor(
    private val auth: AndroidAniListAuthPersistence,
    private val disk: AndroidAniListStorage
) : ProfileScopedCredentialStore {
    internal val changes = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    override fun removeProfile(profileId: Int) {
        auth.write(profileId, null); disk.remove(profileId); changes.tryEmit(profileId)
    }
    override fun clearAllProfiles() {
        auth.clear(); (1..6).forEach(disk::remove); changes.tryEmit(-1)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AniListProfileModule {
    @Binds @IntoSet
    abstract fun credentialStore(cleaner: AniListProfileCleaner): ProfileScopedCredentialStore
}
