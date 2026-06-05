package com.example.androidbackupgui.backup

import android.util.Log
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.MacSpi

/**
 * Injects missing algorithms (MD4, AESCMAC) into Android's BC provider
 * for jcifs-ng SMB support.
 *
 * jcifs-ng instantiates [BouncyCastleProvider] and requests algorithms
 * ([MessageDigest]"MD4", [Mac]"AESCMAC") that Android's built-in BC
 * has removed. The BouncyCastleProvider class is shadowed by the boot
 * classloader, so we patch `jcifs.util.Crypto.provider` via reflection.
 */
object MissingAlgoProvider {

    private const val TAG = "MissingAlgoProvider"
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val patchProvider: Provider by lazy {
        val bc = Security.getProvider("BC")
        DelegatingBcProvider(bc)
    }

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        try {
            // 1. Replace cached provider in jcifs-ng classes
            for (cn in listOf(
                "jcifs.util.Crypto",
                "jcifs.smb.NtlmUtil",
                "jcifs.smb.NtlmPasswordAuthenticator",
                "jcifs.ntlmssp.Type3Message",
                "jcifs.smb.NtlmContext"
            )) setProviderField(cn)

            // 2. Verify
            try {
                val cl = Class.forName("jcifs.util.Crypto")
                val getProv = cl.getDeclaredMethod("getProvider")
                getProv.isAccessible = true
                val actual = getProv.invoke(null) as Provider
                Log.i(TAG, "Crypto.getProvider() => ${actual::class.java.simpleName} " +
                    "(hasMD4=${actual.getService("MessageDigest", "MD4") != null}, " +
                    "hasAESCMAC=${actual.getService("Mac", "AESCMAC") != null})")
            } catch (ve: Exception) {
                Log.w(TAG, "Verification failed after injection", ve)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject algorithms", e)
        }
    }

    private fun setProviderField(clsName: String) {
        try {
            val cls = Class.forName(clsName)
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    Provider::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    f.set(null, patchProvider)
                    Log.i(TAG, "Set $clsName.${f.name} = DelegatingBcProvider")
                    return
                }
            }
            Log.i(TAG, "No static Provider field in $clsName")
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Class not found: $clsName")
        }
    }

    // ── MD4 MessageDigestSpi ────────────────────────────────────

    class Md4Spi : MessageDigestSpi() {
        private val d = MD4Digest()
        override fun engineGetDigestLength() = d.digestSize
        override fun engineUpdate(b: Byte) { d.update(b) }
        override fun engineUpdate(b: ByteArray, o: Int, l: Int) { d.update(b, o, l) }
        override fun engineDigest(): ByteArray {
            val r = ByteArray(d.digestSize); d.doFinal(r, 0); return r
        }
        override fun engineReset() { d.reset() }
    }

    // ── AESCMAC MacSpi ─────────────────────────────────────────
    class AesCmacSpi : MacSpi() {
        private val mac = CMac(AESEngine.newInstance())
        override fun engineInit(key: java.security.Key, params: AlgorithmParameterSpec?) {
            val raw = key.encoded ?: throw java.security.InvalidKeyException("AESCMAC key has no encoded form")
            mac.init(KeyParameter(raw))
        }
        override fun engineUpdate(inp: Byte) { mac.update(inp) }
        override fun engineUpdate(inp: ByteArray, o: Int, l: Int) { mac.update(inp, o, l) }
        override fun engineDoFinal(): ByteArray {
            val r = ByteArray(mac.macSize); mac.doFinal(r, 0); return r
        }
        override fun engineGetMacLength() = mac.macSize
        override fun engineReset() { mac.reset() }
    }

    // ── Delegating provider ─────────────────────────────────────

    /** A "BC"-named provider that delegates to [bc] except for patched algorithms. */
    private class DelegatingBcProvider(
        private val bc: Provider?
    ) : Provider("BC", bc?.version ?: 1.0, "BC + patches") {

        init {
            putService(Service(this, "MessageDigest", "MD4",
                Md4Spi::class.java.name, null, null))
            putService(Service(this, "Mac", "AESCMAC",
                AesCmacSpi::class.java.name, null, null))
        }

        override fun getService(type: String, algorithm: String): Service? {
            if (type == "MessageDigest" && algorithm.equals("MD4", ignoreCase = true)) return super.getService(type, algorithm)
            if (type == "Mac" && algorithm.equals("AESCMAC", ignoreCase = true)) return super.getService(type, algorithm)
            return bc?.getService(type, algorithm)
        }

        override fun getServices(): MutableSet<Service> {
            val s = (bc?.getServices() ?: emptySet<Service>()).toMutableSet()
            s.addAll(super.getServices())
            return s
        }
    }
}
