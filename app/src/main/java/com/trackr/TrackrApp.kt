package com.trackr

import android.app.Application
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.data.repository.ITransitRepository
import com.trackr.data.repository.TransitRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TrackrApp : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var chelaileClient: ChelaileApiClient
        private set

    lateinit var mygolbsClient: MygolbsApiClient
        private set

    lateinit var chelaileProvider: ChelaileTransitProvider
        private set

    lateinit var mygolbsProvider: MygolbsTransitProvider
        private set

    lateinit var coordinator: TransitProviderCoordinator
        private set

    lateinit var repository: ITransitRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()

        chelaileClient = ChelaileApiClient(okHttpClient)
        mygolbsClient = MygolbsApiClient(okHttpClient)

        chelaileProvider = ChelaileTransitProvider(chelaileClient)
        mygolbsProvider = MygolbsTransitProvider(mygolbsClient)

        coordinator = TransitProviderCoordinator(
            chelaileProvider = chelaileProvider,
            mygolbsProvider = mygolbsProvider
        )

        repository = TransitRepository(
            context = applicationContext,
            coordinator = coordinator
        )
    }

    companion object {
        @Volatile
        lateinit var instance: TrackrApp
            private set
    }
}
