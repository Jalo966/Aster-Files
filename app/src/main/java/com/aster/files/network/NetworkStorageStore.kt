package com.aster.files.network

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NetworkProtocol(val label: String) {
    FTP("FTP"),
    WEBDAV("WebDAV"),
    SMB("SMB")
}

data class NetworkNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: NetworkProtocol,
    val address: String,
    val username: String = "",
    val password: String = ""
)

/** 网络存储配置；V3.0 只保存配置，不发起网络文件访问。 */
class NetworkStorageStore(
    context: Context,
    private val passwordCipher: NetworkPasswordCipher = KeystoreNetworkPasswordCipher(context)
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "aster_network_storage",
        Context.MODE_PRIVATE
    )

    fun load(): List<NetworkNode> {
        val raw = preferences.getString(KEY_NODES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        NetworkNode(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            protocol = runCatching {
                                NetworkProtocol.valueOf(item.optString("protocol"))
                            }.getOrDefault(NetworkProtocol.FTP),
                            address = item.optString("address"),
                            username = item.optString("username"),
                            password = decodePassword(item.optString("password"))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(node: NetworkNode) {
        val nodes = load() + node
        persist(nodes)
    }

    fun update(node: NetworkNode) {
        persist(load().map { if (it.id == node.id) node else it })
    }

    fun remove(id: String) {
        persist(load().filterNot { it.id == id })
    }

    private fun persist(nodes: List<NetworkNode>) {
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject().apply {
                    put("id", node.id)
                    put("name", node.name)
                    put("protocol", node.protocol.name)
                    put("address", node.address)
                    put("username", node.username)
                    put("password", encodePassword(node.password))
                }
            )
        }
        preferences.edit().putString(KEY_NODES, array.toString()).apply()
    }

    private fun encodePassword(value: String): String = passwordCipher.encrypt(value)

    private fun decodePassword(value: String): String = runCatching {
        if (value.startsWith("enc:v1:")) {
            passwordCipher.decrypt(value)
        } else {
            // 兼容 V3.0-V3.3 的 Base64 旧数据；下次保存会改为 Keystore 密文。
            String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
        }
    }.getOrDefault("")

    private companion object {
        const val KEY_NODES = "nodes"
    }
}
