package com.mishin.syncftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.syncftp.CameraSettings
import com.mishin.syncftp.FtpFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

object SftpBrowser {

    private fun createSession(settings: CameraSettings): Session {
        val jsch = JSch()
        val session = jsch.getSession(settings.username, settings.server, settings.port)
        session.setPassword(settings.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.timeout = 10000
        return session
    }

    suspend fun listFiles(settings: CameraSettings, path: String): List<FtpFileInfo> {
        return withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                session = createSession(settings)
                session.connect()
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                val targetPath = if (path.isEmpty()) settings.serverFolder else path
                @Suppress("unchecked")
                val files = channel.ls(targetPath) as Vector<ChannelSftp.LsEntry>
                files.mapNotNull { entry ->
                    val attrs = entry.attrs
                    val name = entry.filename
                    if (name == "." || name == "..") return@mapNotNull null
                    com.mishin.syncftp.FtpFileInfo(
                        name = name,
                        path = if (targetPath.endsWith("/")) "$targetPath$name" else "$targetPath/$name",
                        isDirectory = attrs.isDir,
                        size = attrs.size,
                        modifiedDate = 0L
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }

    suspend fun downloadFile(settings: CameraSettings, remotePath: String, localFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                session = createSession(settings)
                session.connect()
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                localFile.outputStream().use { outputStream ->
                    channel.get(remotePath, outputStream)
                }
                return@withContext true
            } catch (e: Exception) {
                return@withContext false
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }
}