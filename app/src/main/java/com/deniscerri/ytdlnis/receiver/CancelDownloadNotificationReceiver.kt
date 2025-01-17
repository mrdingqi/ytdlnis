package com.deniscerri.ytdlnis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deniscerri.ytdlnis.database.DBManager
import com.deniscerri.ytdlnis.database.repository.DownloadRepository
import com.deniscerri.ytdlnis.util.NotificationUtil
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CancelDownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val message = intent.getStringExtra("cancel")
        val id = intent.getIntExtra("workID", 0)
        if (message != null) {
            val notificationUtil = NotificationUtil(c)
            notificationUtil.cancelDownloadNotification(id)
            YoutubeDL.getInstance().destroyProcessById(id.toString())

            val dbManager = DBManager.getInstance(c)
            CoroutineScope(Dispatchers.IO).launch{
                val item = dbManager.downloadDao.getDownloadById(id.toLong())
                item.status = DownloadRepository.Status.Cancelled.toString()
                dbManager.downloadDao.update(item)
            }

        }
    }
}