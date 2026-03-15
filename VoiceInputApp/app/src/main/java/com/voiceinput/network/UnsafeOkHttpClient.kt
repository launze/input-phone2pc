package com.voiceinput.network

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object UnsafeOkHttpClient {

    private const val TAG = "UnsafeOkHttpClient"

    private fun baseBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
    }

    /**
     * Trust-all client for development only.
     */
    fun getUnsafeClient(@Suppress("UNUSED_PARAMETER") context: Context): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val trustManager = trustAllCerts[0] as X509TrustManager

            baseBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Client that trusts BOTH the bundled self-signed cert AND system certs.
     * Also allows hostname mismatch for self-signed scenarios.
     */
    fun getClientWithCustomCert(context: Context, certResourceId: Int): OkHttpClient {
        return try {
            // Load the bundled self-signed certificate
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificate = context.resources.openRawResource(certResourceId).use {
                certificateFactory.generateCertificate(it) as X509Certificate
            }
            Log.d(TAG, "Loaded cert: subject=${certificate.subjectDN}, issuer=${certificate.issuerDN}")

            // Create a KeyStore containing the self-signed cert
            val customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("server", certificate)
            }

            // Also load the default system trust store
            val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            defaultTmf.init(null as KeyStore?) // null = system default
            val defaultTrustManager = defaultTmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()

            // Create a trust manager for the custom cert
            val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            customTmf.init(customKeyStore)
            val customTrustManager = customTmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()

            // Composite trust manager: try custom first, then system
            val compositeTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    defaultTrustManager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        customTrustManager.checkServerTrusted(chain, authType)
                        Log.d(TAG, "Server trusted via custom cert")
                    } catch (e: Exception) {
                        Log.d(TAG, "Custom cert check failed, trying system trust: ${e.message}")
                        defaultTrustManager.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return defaultTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers
                }
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(compositeTrustManager), null)

            baseBuilder()
                .sslSocketFactory(sslContext.socketFactory, compositeTrustManager)
                .hostnameVerifier { hostname, session ->
                    // Allow the self-signed cert's CN
                    val certCN = certificate.subjectX500Principal.name
                    if (certCN.contains(hostname, ignoreCase = true)) {
                        Log.d(TAG, "Hostname '$hostname' matches cert CN")
                        true
                    } else {
                        // Fall back to default verification for other hosts
                        Log.d(TAG, "Hostname '$hostname' not in cert CN '$certCN', using default verifier")
                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                            .verify(hostname, session)
                    }
                }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create client with custom cert", e)
            throw RuntimeException(e)
        }
    }
}
