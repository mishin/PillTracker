package com.mishin.syncftp

import com.syncftp.CameraSettings
import com.mishin.syncftp.FtpFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File

object FtpBrowser {

    suspend fun listFiles(settings: CameraSettings, path: String): List<FtpFileInfo> {
        return withContext(Dispatchers.IO) {
            val client = FTPClient()
            try {
                client.connect(settings.server, settings.port)
                client.login(settings.username, settings.password)
                client.enterLocalPassiveMode()
                val targetPath = if (path.isEmpty()) settings.serverFolder else path
                client.changeWorkingDirectory(targetPath)
                val files = client.listFiles()
                files.mapNotNull { ftpFile ->
                    if (ftpFile.name == "." || ftpFile.name == "..") return@mapNotNull null
                    com.mishin.syncftp.FtpFileInfo(
                        name = ftpFile.name,
                        path = if (targetPath.endsWith("/")) "$targetPath${ftpFile.name}" else "$targetPath/${ftpFile.name}",
                        isDirectory = ftpFile.isDirectory,
                        size = ftpFile.size,
                        modifiedDate = ftpFile.timestamp?.timeInMillis ?: 0
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            } finally {
                try { client.logout(); client.disconnect() } catch (e: Exception) { }
            }
        }
    }

    suspend fun downloadFile(settings: CameraSettings, remotePath: String, localFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            val client = FTPClient()
            try {
                client.connect(settings.server, settings.port)
                client.login(settings.username, settings.password)
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                localFile.outputStream().use { outputStream ->
                    client.retrieveFile(remotePath, outputStream)
                }
                return@withContext true
            } catch (e: Exception) {
                return@withContext false
            } finally {
                try { client.logout(); client.disconnect() } catch (e: Exception) { }
            }
        }
    }
}