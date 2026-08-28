package com.mica.music.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only AES-GCM ciphertext in SharedPreferences; the AES key itself is non-exportable and
 * owned by Android Keystore. Room/DataStore and remote catalog models persist only credentialRef.
 */
class AndroidKeystoreRemoteCredentialStore internal constructor(
    context: Context,
    private val envelopeCipher: CredentialEnvelopeCipher,
    preferencesName: String = PREFERENCES_NAME,
) : MutableSecureRemoteCredentialStore {
    constructor(context: Context) : this(
        context = context,
        envelopeCipher = AndroidKeystoreCredentialEnvelopeCipher(),
    )

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()

    override suspend fun resolve(credentialRef: String): RemoteCredentialSnapshot? = synchronized(lock) {
        require(credentialRef.isNotBlank()) { "credentialRef must not be blank" }
        val key = storageKey(credentialRef)
        val encoded = preferences.getString("payload.$key", null)
        val revision = preferences.getLong("revision.$key", 0L)
        if (encoded == null && revision == 0L) return@synchronized null
        check(encoded != null && revision > 0L) { "Incomplete encrypted credential state for ref=$credentialRef" }

        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrElse { throw IllegalStateException("Invalid encrypted credential encoding for ref=$credentialRef", it) }
        val material = runCatching { RemoteCredentialMaterialCodec.decode(envelopeCipher.decrypt(encrypted)) }
            .getOrElse { throw IllegalStateException("Unable to decrypt remote credential ref=$credentialRef", it) }
        RemoteCredentialSnapshot(credentialRef, revision, material)
    }

    override suspend fun put(
        credentialRef: String,
        material: RemoteCredentialMaterial,
    ): RemoteCredentialSnapshot = synchronized(lock) {
        require(credentialRef.isNotBlank()) { "credentialRef must not be blank" }
        val key = storageKey(credentialRef)
        val nextRevision = preferences.getLong("revision.$key", 0L) + 1L
        val plaintext = RemoteCredentialMaterialCodec.encode(material)
        val encrypted = try {
            envelopeCipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(
            preferences.edit()
                .putString("payload.$key", encoded)
                .putLong("revision.$key", nextRevision)
                .commit(),
        ) { "Failed to persist encrypted remote credential ref=$credentialRef" }
        RemoteCredentialSnapshot(credentialRef, nextRevision, material)
    }

    internal fun encryptedPreferenceSnapshotForTests(): Map<String, *> = preferences.all

    private fun storageKey(credentialRef: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(credentialRef.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "mica_remote_credentials_v1"
    }
}

internal interface CredentialEnvelopeCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
}

private class AndroidKeystoreCredentialEnvelopeCipher(
    private val alias: String = KEY_ALIAS,
) : CredentialEnvelopeCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size in 1..255) { "Unexpected AES-GCM IV length=${iv.size}" }
        return ByteArrayOutputStream(2 + iv.size + encrypted.size).use { output ->
            output.write(ENVELOPE_VERSION)
            output.write(iv.size)
            output.write(iv)
            output.write(encrypted)
            output.toByteArray()
        }
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= 3) { "Encrypted credential envelope is truncated" }
        require(envelope[0].toInt() and 0xff == ENVELOPE_VERSION) { "Unsupported credential envelope version" }
        val ivLength = envelope[1].toInt() and 0xff
        require(ivLength > 0 && envelope.size > 2 + ivLength) { "Invalid credential envelope IV" }
        val iv = envelope.copyOfRange(2, 2 + ivLength)
        val encrypted = envelope.copyOfRange(2 + ivLength, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mica.remote.credentials.aes.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = 1
        private const val GCM_TAG_BITS = 128
    }
}

private object RemoteCredentialMaterialCodec {
    private const val VERSION = 1
    private const val TYPE_ANONYMOUS = 0
    private const val TYPE_USERNAME_PASSWORD = 1
    private const val TYPE_BEARER_TOKEN = 2
    private const val MAX_FIELD_BYTES = 1024 * 1024

    fun encode(material: RemoteCredentialMaterial): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte(VERSION)
            when (material) {
                RemoteCredentialMaterial.Anonymous -> output.writeByte(TYPE_ANONYMOUS)
                is RemoteCredentialMaterial.UsernamePassword -> {
                    output.writeByte(TYPE_USERNAME_PASSWORD)
                    output.writeString(material.username)
                    output.writeString(material.password)
                }
                is RemoteCredentialMaterial.BearerToken -> {
                    output.writeByte(TYPE_BEARER_TOKEN)
                    output.writeString(material.username)
                    output.writeString(material.token)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): RemoteCredentialMaterial = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readUnsignedByte() == VERSION) { "Unsupported credential payload version" }
        val material = when (input.readUnsignedByte()) {
            TYPE_ANONYMOUS -> RemoteCredentialMaterial.Anonymous
            TYPE_USERNAME_PASSWORD -> RemoteCredentialMaterial.UsernamePassword(
                username = input.readString(),
                password = input.readString(),
            )
            TYPE_BEARER_TOKEN -> RemoteCredentialMaterial.BearerToken(
                username = input.readString(),
                token = input.readString(),
            )
            else -> error("Unknown credential payload type")
        }
        require(input.available() == 0) { "Unexpected trailing credential payload bytes" }
        material
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "Credential field is too large" }
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES) { "Invalid credential field length" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
