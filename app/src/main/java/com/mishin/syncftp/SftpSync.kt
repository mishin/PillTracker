package com.syncftp

import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object SftpSync {

    private fun createSession(settings: Settings): Session {
        val jsch = JSch()
        val session = jsch.getSession(settings.username, settings.server, settings.port)
        session.setPassword(settings.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.timeout = 10000
        return session
    }

    suspend fun testConnection(settings: Settings): Boolean {
        return withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                session = createSession(settings)
                session.connect()
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                return@withContext true
            } catch (e: Exception) {
                throw e
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }

    suspend fun uploadFile(settings: Settings, file: File) {
        withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null

            try {
                session = createSession(settings)
                session.connect()

                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()

                FileInputStream(file).use { inputStream ->
                    channel.put(inputStream, file.name)
                }
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }
}