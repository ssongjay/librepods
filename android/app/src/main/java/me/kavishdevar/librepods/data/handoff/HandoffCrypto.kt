package me.kavishdevar.librepods.data.handoff

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal data class HandoffIdentity(val privateKey: PrivateKey, val publicDer: ByteArray)

internal data class HandoffSessionKeys(
    val macToPhone: ByteArray,
    val phoneToMac: ByteArray,
    val pairing: ByteArray,
) {
    val sas: String
        get() {
            require(pairing.size >= SAS_BYTES)
            var accumulator = 0
            var bitCount = 0
            val encoded = buildString(SAS_CHARACTERS) {
                for (index in 0 until SAS_BYTES) {
                    accumulator = (accumulator shl 8) or (pairing[index].toInt() and 0xff)
                    bitCount += 8
                    while (bitCount >= 5) {
                        bitCount -= 5
                        append(CROCKFORD_BASE32[(accumulator shr bitCount) and 0x1f])
                    }
                    accumulator = accumulator and ((1 shl bitCount) - 1)
                }
            }
            require(bitCount == 0 && encoded.length == SAS_CHARACTERS)
            return encoded.chunked(4).joinToString("-")
        }

    private companion object {
        const val SAS_BYTES = 10
        const val SAS_CHARACTERS = 16
        const val CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}

internal object HandoffCrypto {
    private const val KEY_ALIAS = "librepods_my_handoff_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SPKI_LENGTH = 91
    private val spkiPrefix = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private val random = SecureRandom()

    fun identity(): HandoffIdentity {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
        }
        val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("handoff identity unavailable")
        val publicDer = store.getCertificate(KEY_ALIAS)?.publicKey?.encoded
            ?: error("handoff public identity unavailable")
        requireValidPublicDer(publicDer)
        return HandoffIdentity(privateKey, publicDer)
    }

    fun nonce(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun discoveryHint(publicDer: ByteArray): String {
        requireValidPublicDer(publicDer)
        val suffix = MessageDigest.getInstance("SHA-256").digest(publicDer)
            .copyOfRange(0, 4)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
            }
        return "LPMy-$suffix"
    }

    fun decodePublicDer(value: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        requireValidPublicDer(bytes)
        return bytes
    }

    fun derive(
        identity: HandoffIdentity,
        macDer: ByteArray,
        macNonce: ByteArray,
        phoneNonce: ByteArray,
    ): HandoffSessionKeys {
        require(macNonce.size == 32 && phoneNonce.size == 32)
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(macDer)) as ECPublicKey
        val agreement = KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE)
        agreement.init(identity.privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        require(sharedSecret.size == 32)
        val transcript = macDer + identity.publicDer + macNonce + phoneNonce
        val salt = MessageDigest.getInstance("SHA-256").digest(transcript)
        return try {
            HandoffSessionKeys(
                macToPhone = hkdf(sharedSecret, salt, "LPH1 mac-to-phone"),
                phoneToMac = hkdf(sharedSecret, salt, "LPH1 phone-to-mac"),
                pairing = hkdf(sharedSecret, salt, "LPH1 pairing"),
            )
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun encrypt(key: ByteArray, sequence: Long, payload: JSONObject): JSONObject {
        require(sequence > 0)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(sequence))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("type", "secure")
            .put("seq", sequence)
            .put("data", Base64.encodeToString(nonce + encrypted, Base64.NO_WRAP))
    }

    fun decrypt(key: ByteArray, expectedSequence: Long, envelope: JSONObject): JSONObject {
        require(envelope.optString("type") == "secure")
        require(envelope.getLong("seq") == expectedSequence && expectedSequence > 0)
        val combined = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        require(combined.size >= 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, combined.copyOfRange(0, 12)),
        )
        cipher.updateAAD(aad(expectedSequence))
        val plain = cipher.doFinal(combined.copyOfRange(12, combined.size))
        require(plain.size <= HandoffFrameDecoder.MAX_FRAME_BYTES)
        return JSONObject(String(plain, StandardCharsets.UTF_8))
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        return try {
            expand.doFinal(info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1))
        } finally {
            prk.fill(0)
        }
    }

    private fun aad(sequence: Long) =
        "LPH1:$sequence".toByteArray(StandardCharsets.UTF_8)

    private fun requireValidPublicDer(bytes: ByteArray) {
        require(bytes.size == SPKI_LENGTH && bytes.copyOfRange(0, spkiPrefix.size).contentEquals(spkiPrefix))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun resetIdentity() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }
}
