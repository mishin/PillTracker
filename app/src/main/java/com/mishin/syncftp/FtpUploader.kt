package com.mishin.syncftp

import com.syncftp.CameraSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream

object FtpUploader {
    
    suspend fun testConnection(settings: CameraSettings): Boolean {
        return withContext(Dispatchers.IO) {
            val client = FTPClient()
            try {
                client.connect(settings.server, settings.port)
                val login = client.login(settings.username, settings.password)
                client.logout()
                return@withContext login
            } catch (e: Exception) {
                throw e
            } finally {
                try {
                    client.disconnect()
                } catch (e: Exception) { }
            }
        }
    }
    
    suspend fun uploadFile(settings: CameraSettings, file: File, remotePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            val client = FTPClient()
            try {
                client.connect(settings.server, settings.port)
                client.login(settings.username, settings.password)
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                
                // Create directory if needed
                val remoteDir = settings.serverFolder
                if (remoteDir.isNotEmpty()) {
                    client.makeDirectory(remoteDir)
                    client.changeWorkingDirectory(remoteDir)
                }
                
                FileInputStream(file).use { inputStream ->
                    val done = client.storeFile(remotePath, inputStream)
                    return@withContext done
                }
            } finally {
                try {
                    client.logout()
                    client.disconnect()
                } catch (e: Exception) { }
            }
        }
    }
}