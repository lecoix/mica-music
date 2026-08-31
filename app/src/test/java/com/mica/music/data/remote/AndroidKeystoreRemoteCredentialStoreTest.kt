package com.mica.music.data.remote

import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreRemoteCredentialStoreTest {
    private lateinit var store: AndroidKeystoreRemoteCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(TEST_PREFERENCES, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        store = AndroidKeystoreRemoteCredentialStore(
            context = context,
            envelopeCipher = TestAesGcmCipher(),
            preferencesName = TEST_PREFERENCES,
        )
    }

    @Test
    fun missingCredentialReturnsNull() = runTest {
        assertNull(store.resolve("credential/missing"))
    }

    @Test
    fun usernamePasswordRoundTripStoresOnlyCiphertextAndAdvancesRevision() = runTest {
        val credentialRef = "credential/nav-1"
        val first = RemoteCredentialMaterial.UsernamePassword("alice", "super-secret-password")
        val firstSnapshot = store.put(credentialRef, first)
        val resolvedFirst = store.resolve(credentialRef)!!

        assertEquals(1L, firstSnapshot.revision)
        assertEquals(1L, resolvedFirst.revision)
        val material = resolvedFirst.material as RemoteCredentialMaterial.UsernamePassword
        assertEquals("alice", material.username)
        assertEquals("super-secret-password", material.password)
        assertNoPlaintext("alice", "super-secret-password", credentialRef)

        val second = RemoteCredentialMaterial.UsernamePassword("alice", "rotated-password")
        val secondSnapshot = store.put(credentialRef, second)
        assertEquals(2L, secondSnapshot.revision)
        assertEquals("rotated-password", (store.resolve(credentialRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
        assertNoPlaintext("alice", "rotated-password", credentialRef)
    }

    @Test
    fun bearerAndAnonymousCredentialsRemainIndependent() = runTest {
        store.put("credential/a", RemoteCredentialMaterial.BearerToken("bob", "token-a"))
        store.put("credential/b", RemoteCredentialMaterial.Anonymous)

        val a = store.resolve("credential/a")!!.material as RemoteCredentialMaterial.BearerToken
        assertEquals("bob", a.username)
        assertEquals("token-a", a.token)
        assertEquals(RemoteCredentialMaterial.Anonymous, store.resolve("credential/b")!!.material)
        assertNoPlaintext("bob", "token-a")
    }

    @Test
    fun deleteRemovesEncryptedPayloadAndRevision() = runTest {
        val ref = "credential/delete-me"
        store.put(ref, RemoteCredentialMaterial.UsernamePassword("alice", "secret"))
        assertTrue(store.delete(ref))
        assertNull(store.resolve(ref))
        assertFalse(store.delete(ref))
        assertTrue(store.encryptedPreferenceSnapshotForTests().isEmpty())
    }
    @Test
    fun snapshotAndMaterialToStringNeverExposeSecrets() = runTest {
        val snapshot = store.put(
            "credential/nav-1",
            RemoteCredentialMaterial.UsernamePassword("alice", "dont-print-me"),
        )
        val text = snapshot.toString() + " " + snapshot.material.toString()

        assertFalse(text.contains("dont-print-me"))
        assertTrue(text.contains("<redacted>"))
    }

    private fun assertNoPlaintext(vararg secrets: String) {
        val raw = store.encryptedPreferenceSnapshotForTests().entries.joinToString("|") { (key, value) -> "$key=$value" }
        secrets.forEach { secret ->
            assertFalse("encrypted preference leaked '$secret': $raw", raw.contains(secret))
        }
    }

    private class TestAesGcmCipher : CredentialEnvelopeCipher {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext)
            val iv = cipher.iv
            return ByteBuffer.allocate(4 + iv.size + encrypted.size)
                .putInt(iv.size)
                .put(iv)
                .put(encrypted)
                .array()
        }

        override fun decrypt(envelope: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(envelope)
            val iv = ByteArray(input.int).also(input::get)
            val encrypted = ByteArray(input.remaining()).also(input::get)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(encrypted)
            }
        }
    }

    private companion object {
        const val TEST_PREFERENCES = "mica_remote_credentials_test"
    }
}
