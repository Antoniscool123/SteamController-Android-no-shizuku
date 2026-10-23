package com.steamcontroller.android.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * This app's own ADB client identity and connection (libadb-android). It plays the role the
 * `adb` binary on a PC plays: pair once with Wireless debugging, then connect to the local
 * adbd to launch the shell-UID helper. No Shizuku, no PC after the one-time pairing.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = context.filesDir
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")

        var loadedKey: PrivateKey? = null
        var loadedCert: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            try {
                loadedKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                loadedCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream())
            } catch (_: Exception) {
                // Unreadable key: make a new one. The user has to pair again.
                loadedKey = null; loadedCert = null
            }
        }
        if (loadedKey == null || loadedCert == null) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=SteamControllerAndroid")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now),
                Date(now - DAY_MS), Date(now + 3650L * DAY_MS),
                subject, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            loadedKey = pair.private
            loadedCert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(loadedCert.encoded)
        }
        key = loadedKey
        cert = loadedCert
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "SteamControllerAndroid"

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager = instance ?: synchronized(this) {
            instance ?: run {
                // Pairing needs TLS 1.3 key-material export, which the platform provider lacks.
                try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) {}
                AdbManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
