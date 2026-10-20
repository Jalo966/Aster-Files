package com.aster.files.network

import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网文件网页服务。所有路径均先经过 FileGuard，网页只提供浏览/上传/下载/删除。
 * 仅用于本地局域网，不包含云同步或外部账号。
 */
class LanWebServer(
    port: Int,
    private val rootPath: String,
    private val guard: FileGuard
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/" -> {
                    if (session.method == Method.POST) {
                        badRequest("不支持该请求")
                    } else {
                        browse(session)
                    }
                }
                "/download" -> download(session)
                "/upload" -> {
                    if (session.method == Method.POST) upload(session) else badRequest("上传需要 POST")
                }
                "/delete" -> {
                    if (session.method == Method.POST) delete(session) else badRequest("删除需要 POST")
                }
                else -> htmlResponse(
                    Response.Status.NOT_FOUND,
                    page("未找到", "<p>页面不存在。</p><p><a href=\"/\">返回根目录</a></p>")
                )
            }
        } catch (e: FileGuardException) {
            htmlResponse(
                Response.Status.FORBIDDEN,
                page("访问被拒绝", "<p>" + escape(e.message ?: "路径校验失败") + "</p><p><a href=\"/\">返回根目录</a></p>")
            )
        } catch (e: Exception) {
            htmlResponse(
                Response.Status.INTERNAL_ERROR,
                page("操作失败", "<p>" + escape(e.message ?: "未知错误") + "</p><p><a href=\"/\">返回根目录</a></p>")
            )
        }
    }

    private fun browse(session: IHTTPSession): Response {
        val requested = session.parameters["path"]?.firstOrNull() ?: rootPath
        val guarded = guard.guardForRead(requested)
        val directory = guarded.file
        if (!directory.isDirectory) {
            return htmlResponse(
                Response.Status.BAD_REQUEST,
                page("不是目录", "<p>该路径不是文件夹。</p><p><a href=\"/\">返回根目录</a></p>")
            )
        }
        val children = directory.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .orEmpty()
        val currentPath = guarded.canonicalPath
        val pathArg = encode(currentPath)
        val body = StringBuilder()
        body.append("<div class=\"path\">当前位置：<code>")
            .append(escape(currentPath))
            .append("</code></div>")
        if (currentPath != rootPath) {
            val parent = directory.parentFile?.absolutePath ?: rootPath
            body.append("<p><a href=\"/?path=").append(encode(parent))
                .append("\">↑ 上一级</a> · <a href=\"/?path=").append(encode(rootPath))
                .append("\">根目录</a></p>")
        }
        body.append("<form class=\"upload\" method=\"post\" enctype=\"multipart/form-data\" action=\"/upload?path=")
            .append(pathArg)
            .append("\"><input type=\"file\" name=\"file\" required><button type=\"submit\">上传到当前目录</button></form>")
        body.append("<table><thead><tr><th>名称</th><th>大小</th><th>操作</th></tr></thead><tbody>")
        if (children.isEmpty()) {
            body.append("<tr><td colspan=\"3\" class=\"empty\">此目录为空</td></tr>")
        }
        children.forEach { child ->
            val name = child.name
            val childPath = child.absolutePath
            val isDirectory = child.isDirectory
            val link = if (isDirectory) {
                "/?path=" + encode(childPath)
            } else {
                "/download?path=" + encode(childPath)
            }
            body.append("<tr><td class=\"name\"><a href=\"").append(link).append("\">")
                .append(if (isDirectory) "📁 " else "📄 ")
                .append(escape(name))
                .append("</a></td><td>")
                .append(if (isDirectory) "文件夹" else humanSize(child.length()))
                .append("</td><td>")
            if (!isDirectory) {
                body.append("<a class=\"btn\" href=\"").append(link).append("\">下载</a> ")
            }
            body.append("<form class=\"inline\" method=\"post\" action=\"/delete?path=")
                .append(encode(currentPath))
                .append("&target=").append(encode(childPath))
                .append("\" onsubmit=\"return confirm('确定删除此项目？');\">")
                .append("<button class=\"danger\" type=\"submit\">删除</button></form>")
            body.append("</td></tr>")
        }
        body.append("</tbody></table>")
        body.append("<p class=\"hint\">所有浏览、上传、下载、删除操作均经过 FileGuard 路径校验。仅限同一局域网使用。</p>")
        return htmlResponse(Response.Status.OK, page("Aster Files · Wi-Fi 传文件", body.toString()))
    }

    private fun download(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return badRequest("缺少文件路径")
        val guarded = guard.guardReadableFile(path)
        val file = guarded.file
        val mime = guessMime(file.name)
        val response = newChunkedResponse(
            Response.Status.OK,
            mime,
            file.inputStream()
        )
        response.addHeader(
            "Content-Disposition",
            "attachment; filename*=UTF-8''" + encode(file.name)
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun upload(session: IHTTPSession): Response {
        val parentPath = session.parameters["path"]?.firstOrNull() ?: rootPath
        val parent = guard.guardTargetDirectory(parentPath).file
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tempPath = files.values.firstOrNull() ?: return badRequest("没有收到文件")
        val original = session.parameters["file"]?.firstOrNull()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: ("upload_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".bin")
        val safeName = safeFileName(original)
        val plan = uniqueCreationPlan(parent.path, safeName)
        guard.verifyCreationTarget(plan)
        File(tempPath).inputStream().use { input ->
            plan.target.outputStream().use { output -> input.copyTo(output) }
        }
        return redirect("/?path=" + encode(parent.absolutePath))
    }

    private fun delete(session: IHTTPSession): Response {
        val target = session.parameters["target"]?.firstOrNull()
            ?: return badRequest("缺少删除目标")
        val guarded = guard.guardForWrite(target)
        val file = guarded.file
        if (!file.deleteRecursively()) {
            return htmlResponse(
                Response.Status.INTERNAL_ERROR,
                page("删除失败", "<p>无法删除该文件或文件夹。</p><p><a href=\"/?path=" + encode(rootPath) + "\">返回根目录</a></p>")
            )
        }
        val parent = file.parentFile?.absolutePath ?: rootPath
        return redirect("/?path=" + encode(parent))
    }

    private fun safeFileName(original: String): String {
        return try {
            guard.validateName(original)
            original
        } catch (_: FileGuardException) {
            "upload_" + System.currentTimeMillis() + ".bin"
        }
    }

    private fun uniqueCreationPlan(parentPath: String, name: String): com.aster.files.security.CreationPlan {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        repeat(50) { index ->
            val candidate = if (index == 0) {
                name
            } else {
                base + " (" + index + ")" + if (extension.isEmpty()) "" else "." + extension
            }
            runCatching {
                return guard.prepareCreate(parentPath, candidate)
            }
        }
        return guard.prepareCreate(parentPath, "upload_" + System.currentTimeMillis())
    }

    private fun badRequest(message: String): Response =
        htmlResponse(Response.Status.BAD_REQUEST, page("请求错误", "<p>" + escape(message) + "</p><p><a href=\"/\">返回根目录</a></p>"))

    private fun redirect(location: String): Response {
        val response = htmlResponse(Response.Status.REDIRECT, "")
        response.addHeader("Location", location)
        return response
    }

    private fun htmlResponse(status: Response.Status, html: String): Response =
        newFixedLengthResponse(status, "text/html; charset=UTF-8", html)

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(title)}</title>
        <style>
        body{margin:0;background:#f5f0e6;color:#243b32;font-family:system-ui,-apple-system,Segoe UI,sans-serif}
        main{max-width:960px;margin:0 auto;padding:24px 16px 48px}
        h1{font-size:24px;margin:0 0 16px;color:#164c3b}
        .card{background:#fffdf7;border:1px solid #e3dccb;border-radius:18px;padding:18px;box-shadow:0 8px 28px rgba(30,60,45,.08)}
        .path{word-break:break-all;color:#537066;font-size:13px;margin-bottom:8px} code{font-size:12px}
        .upload{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}
        input[type=file]{max-width:100%}
        button,.btn{background:#1d6b52;color:#fff;border:0;border-radius:10px;padding:9px 13px;text-decoration:none;cursor:pointer;font-size:13px}
        button.danger{background:#b33a52} .inline{display:inline}
        table{width:100%;border-collapse:collapse;margin-top:12px;font-size:14px}
        th,td{text-align:left;padding:11px 8px;border-bottom:1px solid #eee5d4;vertical-align:middle}
        th{font-size:12px;color:#70877b;font-weight:600} .name a{color:#174f3d;text-decoration:none;word-break:break-all}
        td .btn{padding:5px 9px;font-size:12px} .empty{text-align:center;color:#8da095;padding:32px}
        .hint{font-size:12px;color:#82968b;margin-top:20px;line-height:1.6}
        a{color:#1d6b52}
        </style></head><body><main><div class="card"><h1>${escape(title)}</h1>${body}</div></main></body></html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[i])
    }

    private fun guessMime(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "txt", "log", "md" -> "text/plain; charset=UTF-8"
            "html", "htm" -> "text/html; charset=UTF-8"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
