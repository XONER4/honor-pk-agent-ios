package com.honerai.admin.net

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Доверие к серверу-ретранслятору Honer (RunPod, свой TLS-сертификат) + всем обычным сайтам.
 * То же, что CloudTrust в основном приложении: без этого админка не подключилась бы к релею по HTTPS.
 * Сертификат публичный (не секрет); приватный ключ — только на сервере.
 */
object AdminTrust {
    private const val RELAY_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBoDCCAUWgAwIBAgIUNx987XWWIIsvzAMQZYsqBrKp634wCgYIKoZIzj0EAwIw
FjEUMBIGA1UEAwwLaG9uZXItcmVsYXkwHhcNMjYwOTMwMTcwNTM4WhcNMzYwOTI3
MTcwNTM4WjAWMRQwEgYDVQQDDAtob25lci1yZWxheTBZMBMGByqGSM49AgEGCCqG
SM49AwEHA0IABL1C9RjEgWVukSVjjH+/qE8HUxUuvZaYcRl+3Cn19shx1UW6W9cP
gyoFdhiRPxaJd6NnkijI2Bv4XQFXI5wIw0OjcTBvMB0GA1UdDgQWBBRdgiXh0JtI
uvYYW6pNpehR0I5L4zAfBgNVHSMEGDAWgBRdgiXh0JtIuvYYW6pNpehR0I5L4zAP
BgNVHRMBAf8EBTADAQH/MBwGA1UdEQQVMBOHBNWtaWyCC2hvbmVyLXJlbGF5MAoG
CCqGSM49BAMCA0kAMEYCIQCURq4PwO/HUplmpFI1q1PGCc2bvK4r1Y3M68xgcgHJ
mwIhAJ8Y2Uf12MJ3xCLmnChs926GpV+XHoEsKSDu3Fg46Sn2
-----END CERTIFICATE-----"""

    val trustManager: X509TrustManager by lazy { build() }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), null)
        ctx.socketFactory
    }

    private fun build(): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val relayCert = cf.generateCertificate(ByteArrayInputStream(RELAY_CERT_PEM.toByteArray())) as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        (systemTmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager)
            ?.acceptedIssuers?.forEachIndexed { i, cert -> keyStore.setCertificateEntry("sys-$i", cert) }
        keyStore.setCertificateEntry("honer-relay", relayCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
