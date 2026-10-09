package com.hermes.bot.data.cron

import com.hermes.bot.data.auth.AuthHttpTransport
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.gateway.okhttp.OkHttpGatewayTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Even a server Retry-After response must not cause a second mutation exchange. */
fun cronHttpTransport(): AuthHttpTransport {
    class ExchangeGuard(val sent: AtomicBoolean = AtomicBoolean())
    val client = OkHttpGatewayTransport.defaultClient().newBuilder()
        .retryOnConnectionFailure(false)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().tag(ExchangeGuard::class.java, ExchangeGuard()).build())
        }
        .addNetworkInterceptor { chain ->
            val guard = chain.request().tag(ExchangeGuard::class.java)
            if (guard == null || !guard.sent.compareAndSet(false, true)) throw IOException("Repeated cron exchange rejected")
            chain.proceed(chain.request())
        }.build()
    return OkHttpAuthTransport(client = client)
}
