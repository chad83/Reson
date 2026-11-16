package com.reson.player.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.reson.player.domain.NasException
import com.reson.player.domain.NasLogin
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfile
import com.reson.player.domain.NasProfiles
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreNasProfiles(context: Context) : NasProfiles {
    private val preferences = context.getSharedPreferences("nas_connections", Context.MODE_PRIVATE)

    @Synchronized
    override fun list(): List<NasProfile> = protect {
        preferences.all.keys.filter(NasPaths::validId).map { id -> login(id).profile }
            .sortedBy { it.name.lowercase() }
    }

    @Synchronized
    override fun login(id: String): NasLogin = protect {
        val encoded = preferences.getString(id, null) ?: throw NasException(NasProblem.Missing)
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, IV_BYTES))
        cipher.updateAAD(id.toByteArray())
        val plain = cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
        try {
            val data = JSONObject(plain.decodeToString())
            val folder = data.getJSONArray("folder")
            NasLogin(
                NasProfile(
                    id = id,
                    name = data.getString("name"),
                    host = data.getString("host"),
                    port = data.getInt("port"),
                    share = data.getString("share"),
                    username = data.getString("username"),
                    domain = data.getString("domain"),
                    folder = List(folder.length(), folder::getString),
                ),
                data.getString("password"),
            )
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    override fun save(login: NasLogin) = protect {
        val profile = login.profile
        require(NasPaths.validId(profile.id))
        val data = JSONObject()
            .put("name", profile.name)
            .put("host", profile.host)
            .put("port", profile.port)
            .put("share", profile.share)
            .put("username", profile.username)
            .put("domain", profile.domain)
            .put("folder", JSONArray(profile.folder))
            .put("password", login.password)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_BYTES)
        cipher.updateAAD(profile.id.toByteArray())
        val plain = data.toString().encodeToByteArray()
        val encrypted = try {
            cipher.iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(profile.id, encoded).commit())
    }

    @Synchronized
    override fun remove(id: String) = protect {
        check(preferences.edit().remove(id).commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (error: NasException) {
        throw error
    } catch (_: Exception) {
        throw NasException(NasProblem.SavedConnection)
    }

    private companion object {
        const val KEY_ALIAS = "reson.nas.connections.v1"
        const val IV_BYTES = 12
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
