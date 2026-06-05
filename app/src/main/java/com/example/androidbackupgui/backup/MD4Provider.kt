package com.example.androidbackupgui.backup

import android.util.Log
import org.bouncycastle.crypto.digests.MD4Digest
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security

/**
 * Ensures MD4 [MessageDigest] is available for jcifs-ng on Android.
 *
 * jcifs-ng 2.1.x obtains MD4 by instantiating [BouncyCastleProvider]
 * and calling [MessageDigest.getInstance]("MD4", bcProvider).
 * Android's BouncyCastleProvider class is shadowed by the boot classloader
 * and lacks MD4.
 *
 * Strategy: use reflection to replace `jcifs.util.Crypto.provider`
 * with a delegating provider that wraps Android's BC and adds MD4.
 * The MD4 [MessageDigestSpi] implementation comes from [MD4Digest]
 * in bcprov-jdk15to18 (not shadowed — the class is not in boot CL).
 */
object MD4Provider {

    private const val TAG = "MD4Provider"
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val md4Provider: Provider by lazy {
        val bc = Security.getProvider("BC")
        Md4DelegatingProvider(bc)
    }

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        try {
            // 1. Replace cached provider in every jcifs-ng class that has one
            setProviderField("jcifs.util.Crypto")
            for (cn in listOf(
                "jcifs.smb.NtlmUtil",
                "jcifs.smb.NtlmPasswordAuthenticator",
                "jcifs.ntlmssp.Type3Message",
                "jcifs.smb.NtlmContext"
            )) setProviderField(cn)

            // 2. Verify by checking what Crypto.getProvider() returns
            try {
                val cl = Class.forName("jcifs.util.Crypto")
                val getProv = cl.getDeclaredMethod("getProvider")
                getProv.isAccessible = true
                val actual = getProv.invoke(null) as Provider
                Log.i(TAG, "Crypto.getProvider() => ${actual::class.java.simpleName} (hasMD4=${actual.getService("MessageDigest", "MD4") != null})")
            } catch (_: Exception) {}

            // 3. Fallback: register a global MD4 provider too
            try {
                Security.insertProviderAt(Md4StandaloneProvider(), 1)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject MD4", e)
        }
    }

    private fun setProviderField(clsName: String) {
        try {
            val cls = Class.forName(clsName)
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    Provider::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    f.set(null, md4Provider)
                    Log.i(TAG, "Set $clsName.${f.name} = Md4DelegatingProvider")
                    return
                }
            }
            Log.i(TAG, "No static Provider field in $clsName")
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Class not found: $clsName")
        }
    }

    // ── MD4 MessageDigestSpi ────────────────────────────────────

    class Md4DigestSpi : MessageDigestSpi() {
        private val d = MD4Digest()
        override fun engineGetDigestLength() = d.digestSize
        override fun engineUpdate(b: Byte) { d.update(b) }
        override fun engineUpdate(b: ByteArray, o: Int, l: Int) { d.update(b, o, l) }
        override fun engineDigest(): ByteArray {
            val r = ByteArray(d.digestSize); d.doFinal(r, 0); return r
        }
        override fun engineReset() { d.reset() }
    }

    // ── Delegating provider ─────────────────────────────────────

    /** A "BC"-named provider that delegates to [bc] except for MD4. */
    private class Md4DelegatingProvider(
        private val bc: Provider?
    ) : Provider("BC", bc?.version ?: 1.0, "BC + MD4") {

        init {
            // Register MD4 service in the provider's internal service map
            putService(Service(this, "MessageDigest", "MD4",
                Md4DigestSpi::class.java.name, null, null))
        }

        override fun getService(type: String, algorithm: String): Service? {
            if (type == "MessageDigest" && algorithm.equals("MD4", ignoreCase = true)) {
                return super.getService(type, algorithm)
            }
            return bc?.getService(type, algorithm)
        }

        override fun getServices(): MutableSet<Service> {
            val s = (bc?.getServices() ?: emptySet<Service>()).toMutableSet()
            s.addAll(super.getServices())
            return s
        }
    }

    /** Standalone MD4-only provider registered globally as fallback. */
    private class Md4StandaloneProvider : Provider("Md4Provider", 1.0, "MD4 only") {
        override fun getService(type: String, algorithm: String): Service? {
            if (type == "MessageDigest" && algorithm.equals("MD4", ignoreCase = true)) {
                return Service(this, type, algorithm, Md4DigestSpi::class.java.name, null, null)
            }
            return null
        }
    }
}
