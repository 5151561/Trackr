package com.busonroad

import android.app.Application
import com.busonroad.data.remote.chelaile.ChelaileApiClient
import com.busonroad.data.remote.mygolbs.MygolbsApiClient
import com.busonroad.data.repository.ITransitRepository
import com.busonroad.data.repository.TransitRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BusOnRoadApp : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var chelaileClient: ChelaileApiClient
        private set

    lateinit var mygolbsClient: MygolbsApiClient
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
        repository = TransitRepository(
            context = applicationContext,
            chelaileClient = chelaileClient,
            mygolbsClient = mygolbsClient
        )
    }

    companion object {
        @Volatile
        lateinit var instance: BusOnRoadApp
            private set
    }
}
