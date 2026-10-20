package com.aster.files.integrity

import com.aster.files.data.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户确认后的完整性扫描范围。
 *
 * filePaths 为显式选择的文件；directoryPaths 为目录（recursiveDirectories 决定
 * 是否递归）。服务会解析成实际文件列表，并把两者一起写入 task.scopeJson。
 */
data class IntegrityScope(
    val filePaths: List<String> = emptyList(),
    val directoryPaths: List<String> = emptyList(),
    val recursiveDirectories: Boolean = true,
    val mediaTypes: Set<MediaType> = emptySet()
) {
    val isEmpty: Boolean
        get() = filePaths.isEmpty() && directoryPaths.isEmpty()
}

data class PersistedIntegrityScope(
    val scope: IntegrityScope,
    val resolvedFiles: List<String>
)

/** scope_json 的编解码；resolvedFiles 保证 runTask 使用用户确认时的文件集合。 */
object IntegrityScopeCodec {

    fun encode(scope: IntegrityScope, resolvedFiles: List<String>): String =
        JSONObject().apply {
            put("filePaths", toArray(scope.filePaths))
            put("directoryPaths", toArray(scope.directoryPaths))
            put("recursiveDirectories", scope.recursiveDirectories)
            put("mediaTypes", toArray(scope.mediaTypes.map { it.name }))
            put("resolvedFiles", toArray(resolvedFiles))
        }.toString()

    fun decode(json: String): PersistedIntegrityScope {
        val objectValue = JSONObject(json)
        val scope = IntegrityScope(
            filePaths = objectValue.optJSONArray("filePaths").toStringList(),
            directoryPaths = objectValue.optJSONArray("directoryPaths").toStringList(),
            recursiveDirectories = objectValue.optBoolean("recursiveDirectories", true),
            mediaTypes = objectValue.optJSONArray("mediaTypes")
                .toStringList()
                .mapNotNull { runCatching { MediaType.valueOf(it) }.getOrNull() }
                .toSet()
        )
        return PersistedIntegrityScope(
            scope = scope,
            resolvedFiles = objectValue.optJSONArray("resolvedFiles").toStringList()
        )
    }

    private fun toArray(values: List<String>): JSONArray {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val values = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotEmpty()) values.add(value)
        }
        return values
    }
}
