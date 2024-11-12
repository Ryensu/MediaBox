package com.su.mediabox.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.asFlow
import androidx.work.*
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.microsoft.appcenter.analytics.Analytics
import com.su.mediabox.App
import com.su.mediabox.Pref
import com.su.mediabox.database.getAppDataBase
import com.su.mediabox.plugin.MediaUpdateCheck
import com.su.mediabox.plugin.PluginManager
import com.su.mediabox.plugin.PluginManager.acquireComponent
import com.su.mediabox.pluginapi.components.IMediaUpdateDataComponent
import com.su.mediabox.view.activity.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import com.su.mediabox.R
import com.su.mediabox.model.PluginInfo
import com.su.mediabox.plugin.PluginPreferenceImpl
import com.su.mediabox.plugin.PluginPreferenceImpl.checkKeyExist
import com.su.mediabox.plugin.PluginPreferenceImpl.get
import com.su.mediabox.plugin.PluginPreferenceImpl.set
import com.su.mediabox.util.*
import com.su.mediabox.view.activity.MediaDataActivity

const val MEDIA_UPDATE_CHECK_WORKER_ID = "MEDIA_UPDATE_CHECK_WORKER_ID"
const val MEDIA_UPDATE_CHECK_WORKER_TAG = "MEDIA_UPDATE_CHECK_WORKER_TAG"
const val MEDIA_UPDATE_CHECK_TARGET_PLUGIN = "MEDIA_UPDATE_CHECK_TARGET_PLUGIN"

private val mediaUpdateCheckWorkerInfo = WorkManager.getInstance(App.context)
    .getWorkInfosByTagLiveData(MEDIA_UPDATE_CHECK_WORKER_TAG)

/**
 * 媒体检查更新服务当前运行状态，包括自动和手动
 */
val mediaUpdateCheckWorkerIsRunning = mediaUpdateCheckWorkerInfo
    .asFlow()
    .map { list ->
        list.find { it.state == WorkInfo.State.RUNNING } != null
    }
    .flowOn(Dispatchers.Default)
    .stateIn(appCoroutineScope, SharingStarted.WhileSubscribed(), false)

internal class MediaUpdateCheckWorker(context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters) {

    private val mediaUpdateNofChannelId = "media_update_check"
    private val TAG = "媒体检查更新Worker"
    private val key = ResourceUtil.getString(R.string.media_update_check_title)

    private val notifyFlag =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun createForegroundInfo(): ForegroundInfo {
//        val cancel = applicationContext.getString(R.string.cancel)
//        val intent = WorkManager.getInstance(applicationContext)
//            .createCancelPendingIntent(id)

        val notifyIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val notifyPendingIntent = PendingIntent.getActivity(
            applicationContext, 0, notifyIntent, notifyFlag
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val notification = NotificationCompat.Builder(applicationContext, mediaUpdateNofChannelId)
            .setContentTitle(applicationContext.getString(R.string.media_update_check_title))
            .setContentText(applicationContext.getString(R.string.checking))
            .setSmallIcon(R.mipmap.ic_mediabox)
            .setOngoing(true)
            .setContentIntent(notifyPendingIntent)
            //.addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()

        return ForegroundInfo(2, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private suspend fun PluginInfo.checkPluginConfig() = Util.withoutExceptionGet {
        val ds = PluginPreferenceImpl.getPluginDataStore(this)
        val has = ds.checkKeyExist(key, String::class.java, true)
        if (!has) {
            ds.set(key, value = true, isVisual = true)
            true
        } else
            ds.get(key, defaultValue = true, isVisual = true)
    } ?: false

    @FlowPreview
    override suspend fun doWork(): Result {
        if (mediaUpdateCheckWorkerIsRunning.value) {
            logD(TAG, "当前任务已在运行:${mediaUpdateCheckWorkerIsRunning.value}")
            return Result.success()
        }
        Analytics.trackEvent("功能：媒体自动检查更新")
        runCatching {
            setForeground(createForegroundInfo())
        }
        withContext(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            logD(TAG, "发生错误:${throwable.message}")
        }) {
            PluginManager.pluginFlow.first().apply {
                logD(TAG, "准备检查${size}个插件的媒体更新")
            }.asFlow()
                //TODO 检查插件私有存储配置决定是否参与更新
                //TODO ？定时1小时更新，插件可通过私有键对存储配置参与的更新时段
                .flatMapMerge { plugin ->
                    //并行检查
                    flow {
                        Util.withoutExceptionGet {
                            plugin.acquireComponent(
                                IMediaUpdateDataComponent::class.java
                            )
//                            object : IMediaUpdateDataComponent {
//                                override suspend fun getUpdateTag(detailUrl: String): String =
//                                    getDataFormat("H:m").format(System.currentTimeMillis())
//                            }
                        }?.also { component ->
                            if (plugin.checkPluginConfig()) {
                                val favorites =
                                    plugin.getAppDataBase().favoriteDao().getFavoriteList()
                                if (favorites.isNotEmpty())
                                    MediaUpdateCheck.checkMediaUpdate(
                                        favorites, plugin, component
                                    ) {
                                        emit(Pair(plugin, it))
                                    }
                            }
                        }
                    }
                }
                //.filter { it.second.isNotEmpty() }
                .toList().also { result ->
                    val validData = result.filter { it.second.isNotEmpty() }
                    createNotificationChannel()
                    logD(TAG, "共计检查${result.size}，发现${validData.size}个插件的媒体有更新")

                    validData.forEachIndexed { index, it ->
                        //每个插件媒体更新的通知
                        val updateList = NotificationCompat.InboxStyle()
                        it.second.take(5).forEachIndexed { index, media ->
                            media?.apply {
                                updateList.addLine("${index + 1}.$targetMediaLabel : $newTag")
                            }
                        }
                        if (it.second.size > 5)
                            updateList.addLine("...")

                        val pluginMediaDataManageIntent =
                            Intent(applicationContext, MediaDataActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                //目标插件
                                putExtra(MEDIA_UPDATE_CHECK_TARGET_PLUGIN, it.first.packageName)
                                //TODO 这里还要有个小问题：在已打开特定插件后还能通过插件打开另一个插件，改变了当前插件的上下文，会出现非预期效果
                            }

                        val pluginMediaDataManageNotifyPendingIntent = PendingIntent.getActivity(
                            applicationContext, index,
                            pluginMediaDataManageIntent, notifyFlag
                        )

                        val pluginMediaUpdateNofBuilder =
                            NotificationCompat.Builder(applicationContext, mediaUpdateNofChannelId)
                                .apply {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        setSmallIcon(IconCompat.createWithBitmap(it.first.icon.toBitmap()))
                                    } else
                                        setSmallIcon(R.mipmap.ic_mediabox)
                                }
                                .setContentTitle(it.first.name)
                                .setContentText(
                                    applicationContext.getString(
                                        R.string.media_update_check_plugin_result,
                                        it.second.size
                                    )
                                )
                                .setContentIntent(pluginMediaDataManageNotifyPendingIntent)
                                .setAutoCancel(true)
                                .setStyle(updateList)
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setGroup(mediaUpdateNofChannelId)

                        with(NotificationManagerCompat.from(applicationContext)) {
                            notify(it.first.hashCode(), pluginMediaUpdateNofBuilder.build())
                        }
                    }

                    //媒体检查更新统计信息通知
                    if (result.isNotEmpty()) {
                        val notifyIntent =
                            Intent(applicationContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        val notifyPendingIntent = PendingIntent.getActivity(
                            applicationContext, 0, notifyIntent, notifyFlag
                        )

                        val mediaUpdateCheckNofBuilder =
                            NotificationCompat.Builder(applicationContext, mediaUpdateNofChannelId)
                                .setLargeIcon(
                                    BitmapFactory.decodeResource(
                                        applicationContext.resources,
                                        R.mipmap.ic_mediabox
                                    )
                                )
                                .apply {
                                    //如果无有效通知，则5分钟后自动关闭通知
                                    if (validData.isEmpty())
                                        setTimeoutAfter(5 * 60 * 1000)
                                }
                                .setSmallIcon(R.mipmap.ic_mediabox)
                                .setContentTitle(applicationContext.getString(R.string.media_update_check_title))
                                .setContentText(
                                    applicationContext.getString(
                                        R.string.media_update_check_result,
                                        result.size, validData.size
                                    )
                                )
                                .setContentIntent(notifyPendingIntent)
                                .setStyle(
                                    NotificationCompat.BigTextStyle()
                                        .bigText(applicationContext.getString(R.string.media_update_check_hint))
                                )
                                .setAutoCancel(true)
                                .setPriority(NotificationCompat.PRIORITY_HIGH)

                        with(NotificationManagerCompat.from(applicationContext)) {
                            notify(1, mediaUpdateCheckNofBuilder.build())
                        }
                    }
                }

        }
        Pref.mediaUpdateCheckLastTime.saveData(System.currentTimeMillis())
        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.media_update_check_title)
            val descriptionText = applicationContext.getString(R.string.media_update_check_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(mediaUpdateNofChannelId, name, importance).apply {
                description = descriptionText
            }
            NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
        }
    }

}

fun launchMediaUpdateCheckWorker(existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(if (Pref.mediaUpdateOnMeteredNet.value) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)//电量充足才检查
        .build()

    val timePair = getTimePairByTimeFormat(Pref.mediaUpdateCheckInterval.value)!!
    logD("媒体定时自动检查", Pref.mediaUpdateCheckInterval.value)
    val request =
        PeriodicWorkRequestBuilder<MediaUpdateCheckWorker>(timePair.first, timePair.second)
            //PeriodicWorkRequestBuilder<MediaUpdateCheckWorker>(15, TimeUnit.MILLISECONDS)
            .addTag(MEDIA_UPDATE_CHECK_WORKER_TAG)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            //.setInitialDelay(1, TimeUnit.HOURS)
            .build()

    WorkManager.getInstance(App.context).enqueueUniquePeriodicWork(
        MEDIA_UPDATE_CHECK_WORKER_ID,
        existingPeriodicWorkPolicy,
        request
    )
}

fun stopMediaUpdateCheckWorker() {
    WorkManager.getInstance(App.context).cancelUniqueWork(MEDIA_UPDATE_CHECK_WORKER_ID)
}

fun launchMediaUpdateCheckWorkerNow() {
    val request = OneTimeWorkRequestBuilder<MediaUpdateCheckWorker>()
        .addTag(MEDIA_UPDATE_CHECK_WORKER_TAG)
        .build()

    val uniName = MediaUpdateCheckWorker::class.java.name

    WorkManager.getInstance(App.context).apply {
        enqueueUniqueWork(
            uniName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

}

fun registerMediaUpdateCheckShortcut() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {

        val intent = IntentProcessor.processorIntent(MEDIA_UPDATE_CHECK_WORKER_ID) {
            launchMediaUpdateCheckWorkerNow()
        }

        val name = App.context.getString(R.string.media_update_check_pref_now_title)

        val shortcut = ShortcutInfoCompat.Builder(App.context, MEDIA_UPDATE_CHECK_WORKER_ID)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(
                IconCompat.createWithResource(
                    App.context,
                    R.drawable.ic_update_main_color_2_24_skin
                )
            )
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(App.context, listOf(shortcut))
    }
}

@SuppressLint("BatteryLife")
fun Context.checkBatteryOptimizations(state: Boolean = Pref.mediaUpdateCheck.value) {
    if (state &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) != true
    ) {
        MaterialDialog(this).show {
            title(res = R.string.media_update_check_title)
            message(res = R.string.media_update_check_permission_alert)
            cancelable(false)
            negativeButton(res = R.string.cancel) { dismiss() }
            positiveButton(res = R.string.media_update_battery_optimization) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                if (intent.resolveActivity(packageManager) != null)
                    startActivity(intent)
            }
            countdownActionButton(WhichButton.NEGATIVE, durationSeconds = 5)
        }
    }
}