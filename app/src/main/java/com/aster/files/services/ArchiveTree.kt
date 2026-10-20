package com.aster.files.services

/** 只读 ZIP/APK 条目树；路径使用 / 分隔，根节点 path 为空。 */
data class ArchiveTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedSize: Long,
    val children: List<ArchiveTreeNode>
)

object ArchiveTreeBuilder {

    private class MutableNode(
        val name: String,
        val path: String,
        var isDirectory: Boolean
    ) {
        val children = LinkedHashMap<String, MutableNode>()
        var sizeBytes: Long = 0L
        var compressedSize: Long = 0L

        fun freeze(): ArchiveTreeNode {
            val sorted = children.values.sortedWith(
                compareBy<MutableNode> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            return ArchiveTreeNode(
                name = name,
                path = path,
                isDirectory = isDirectory,
                sizeBytes = sizeBytes,
                compressedSize = compressedSize,
                children = sorted.map { it.freeze() }
            )
        }
    }

    fun build(entries: List<ZipEntryInfo>): ArchiveTreeNode {
        val root = MutableNode("", "", true)
        for (entry in entries) {
            val trimmed = entry.name.trimEnd('/')
            if (trimmed.isEmpty()) continue
            val segments = trimmed.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue

            var current = root
            for ((index, segment) in segments.withIndex()) {
                val isLast = index == segments.lastIndex
                val isDirectory = !isLast || entry.isDirectory || entry.name.endsWith('/')
                val childPath = if (current.path.isEmpty()) segment else current.path + "/" + segment
                val child = current.children.getOrPut(segment) {
                    MutableNode(segment, childPath, isDirectory)
                }
                if (!isDirectory) {
                    child.isDirectory = false
                    child.sizeBytes = entry.uncompressedSize
                    child.compressedSize = entry.compressedSize
                } else {
                    child.isDirectory = true
                }
                current = child
            }
        }
        return root.freeze()
    }

    fun find(root: ArchiveTreeNode, path: String): ArchiveTreeNode? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = current.children.firstOrNull { it.name == segment } ?: return null
        }
        return current
    }

    fun children(root: ArchiveTreeNode, path: String): List<ArchiveTreeNode> =
        find(root, path)?.children.orEmpty()

    fun parentPath(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index <= 0) "" else path.substring(0, index)
    }
}
