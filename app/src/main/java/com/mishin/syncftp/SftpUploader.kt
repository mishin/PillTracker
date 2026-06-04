package com.mishin.syncftp

import com.jcraft.jsch.*
import com.syncftp.CameraSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object SftpUploader {
    
    private fun createSession(settings: CameraSettings): Session {
        val jsch = JSch()
        val session = jsch.getSession(settings.username, settings.server, settings.port)
        session.setPassword(settings.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.timeout = 10000
        return session
    }
    
    suspend fun testConnection(settings: CameraSettings): Boolean {
        return withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                session = createSession(settings)
                session.connect()
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                return@withContext true
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }
    
    suspend fun uploadFile(settings: CameraSettings, file: File, remotePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelSftp? = null
            
            try {
                session = createSession(settings)
                session.connect()
                
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                
                // Create directory if needed
                val remoteDir = settings.serverFolder
                if (remoteDir.isNotEmpty()) {
                    try {
                        channel.mkdir(remoteDir)
                    } catch (e: SftpException) {
                        // Directory might already exist
                    }
                    channel.cd(remoteDir)
                }
                
                FileInputStream(file).use { inputStream ->
                    channel.put(inputStream, remotePath)
                }
                return@withContext true
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }
    }
}