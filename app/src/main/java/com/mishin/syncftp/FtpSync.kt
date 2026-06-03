package com.syncftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream

object FtpSync {

    suspend fun testConnection(settings: Settings): Boolean {
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
                } catch (e: Exception) {
                    // Ignore disconnect errors
                }
            }
        }
    }

    suspend fun uploadFile(settings: Settings, file: File) {
        withContext(Dispatchers.IO) {
            val client = FTPClient()
            try {
                client.connect(settings.server, settings.port)
                client.login(settings.username, settings.password)
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.setFileTransferMode(FTP.BINARY_FILE_TYPE)

                FileInputStream(file).use { inputStream ->
                    val done = client.storeFile(file.name, inputStream)
                    if (!done) {
                        throw Exception("FTP store failed: ${client.replyString}")
                    }
                }

                client.logout()
            } finally {
                try {
                    client.disconnect()
                } catch (e: Exception) {
                    // Ignore disconnect errors
                }
            }
        }
    }
}