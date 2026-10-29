package com.nuvio.tv.data.anilist

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

@Singleton
class AniListRetryScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule(delayMs: Long) {
        val job = JobInfo.Builder(18767, ComponentName(context, AniListRetryService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs.coerceAtLeast(1000)).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }
}

/** One-shot network-constrained retry. No permanent or foreground service. */
@AndroidEntryPoint
class AniListRetryService : JobService() {
    @Inject lateinit var repository: AniListRepository
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { repository.retryPending() }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return true }
}
