package com.su.mediabox.view.fragment.page

import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.afollestad.materialdialogs.MaterialDialog
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager
import com.su.mediabox.*
import com.su.mediabox.config.Const
import com.su.mediabox.util.*
import com.su.mediabox.util.update.AppUpdateHelper
import com.su.mediabox.util.update.AppUpdateStatus
import com.su.mediabox.view.activity.LicenseActivity
import com.su.mediabox.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

class SettingsPageFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener {

    override fun onResume() {
        super.onResume()
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            DataStorePreference(prefCoroutineScope = lifecycleScope)
        preferenceScreen = preferenceScreen {

            preferenceCategory {

                titleRes(R.string.support_title)

                preference {
                    setIcon(R.drawable.ic_github_star)
                    title = "Star"
                    summaryRes(R.string.open_source_star)
                    onPreferenceClickListener = this@SettingsPageFragment
                }

                preference {
                    setIcon(R.drawable.ic_baseline_eye_24)
                    title = "Watch"
                    summaryRes(R.string.open_source_watch)
                    onPreferenceClickListener = this@SettingsPageFragment
                }
            }

            preferenceCategory {

                titleRes(R.string.net_category_title)

                checkPreference {
                    key = Const.Setting.NET_REPO_PROXY
                    setDefaultValue(true)
                    setIcon(R.drawable.ic_language_main_color_2_24_skin)
                    titleRes(R.string.net_proxy_title)
                    summaryRes(R.string.net_proxy_summary)
                }
            }

            preferenceCategory {
                titleRes(R.string.media_update_check_category)
                val auto = switchPreference {
                    setDefaultValue(Pref.mediaUpdateCheck.value)
                    key = Const.Setting.MEDIA_UPDATE_CHECK
                    setIcon(R.drawable.ic_update_main_color_2_24_skin)
                    titleRes(R.string.media_update_check_name)
                    summaryRes(R.string.media_update_check_desc)
                }

                val interval = singleSelectListPreference {
                    dataTextListRes(R.array.media_update_check_interval_text)
                    dataListRes(R.array.media_update_check_interval_value)
                    key = Const.Setting.MEDIA_UPDATE_CHECK_INTERVAL
                    setDefaultValue(Pref.mediaUpdateCheck.value)
                    titleRes(R.string.media_update_check_pref_interval)

                    setOnPreferenceChangeListener { _, _ ->
                        //TODO 这个即使设置了ExistingPeriodicWorkPolicy.REPLACE但还是会导致Worker立刻开始运行一次
                        //launchMediaUpdateCheckWorker(ExistingPeriodicWorkPolicy.REPLACE)
                        WorkManager.getInstance(App.context)
                            .cancelUniqueWork(MEDIA_UPDATE_CHECK_WORKER_ID)
                        auto.isChecked = false
                        true
                    }
                }

                preference {
                    summaryRes(R.string.media_update_check_alert)
                    icon = ResourceUtil.getDrawable(
                        R.drawable.ic_info_white_24,
                        R.color.main_color_2_skin
                    )
                }

                val now = preference {
                    titleRes(R.string.media_update_check_pref_now_name)
                    setOnPreferenceClickListener {
                        launchMediaUpdateCheckWorkerNow()
                        true
                    }
                    val running =
                        App.context.getString(R.string.media_update_check_pref_now_summary)
                    lifecycleCollect(mediaUpdateCheckWorkerLastCompleteTime) {
                        //TODO 刷新触发可能还存在一些问题
                        summary =
                            if (mediaUpdateCheckWorkerIsRunning.value) running
                            else it?.run {
                                App.context.getString(
                                    R.string.media_update_check_pref_last_check_complete_time,
                                    friendlyTime(this)
                                )
                            } ?: running
                    }
                }

                lifecycleCollect(mediaUpdateCheckWorkerIsRunning) {
                    auto.isEnabled = !it
                    interval.isEnabled = !it
                    now.isEnabled = !it
                }
            }

            preferenceCategory {

                titleRes(R.string.player_category_title)

                switchPreference {
                    key = Const.Setting.SHOW_PLAY_BOTTOM_BAR
                    titleRes(R.string.player_bottom_progress_title)
                    summaryRes(R.string.player_bottom_progress_summary)

                    lifecycleCollect(Pref.isShowPlayerBottomProgressBar) {
                        isChecked = it
                    }
                }

                singleSelectListPreference {
                    dataTextListRes(R.array.play_action_core_text)
                    dataList(
                        arrayOf(
                            Exo2PlayerManager::class.java.name,
                            IjkPlayerManager::class.java.name
                        )
                    )
                    key = Const.Setting.PLAY_ACTION_DEFAULT_CORE
                    setDefaultValue(Pref.playDefaultCore.value)
                    setIcon(R.drawable.ic_baseline_core_24)
                    titleRes(R.string.player_default_core_title)
                }
            }

            preferenceCategory {
                titleRes(R.string.about_category_title)

                preference {
                    summaryRes(R.string.version_summary)

                    AppUpdateHelper.instance.apply {
                        getUpdateStatus()
                            .observe(this@SettingsPageFragment) {
                                isEnabled = it != AppUpdateStatus.CHECKING
                                when (it) {
                                    AppUpdateStatus.VALID ->
                                        context.getString(R.string.app_no_update_hint).showToast()
                                    AppUpdateStatus.DATED -> noticeUpdate(requireActivity() as AppCompatActivity)
                                    else -> Unit
                                }
                            }
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val packageInfo =
                            context.run { packageManager.getPackageInfo(packageName, 0) }
                        titleRes(
                            R.string.version_title,
                            packageInfo.versionName,
                            packageInfo.versionCode,
                            if (BuildConfig.DEBUG) "Debug" else "Release"
                        )

                        setOnPreferenceClickListener {
                            AppUpdateHelper.instance.checkUpdate()
                            true
                        }
                    }
                }

                preference {
                    setIcon(R.drawable.ic_github)
                    titleRes(R.string.open_source_title)
                    summary = Const.Common.GITHUB_URL
                    onPreferenceClickListener = this@SettingsPageFragment
                }

                preference {
                    titleRes(R.string.open_source_licenses)
                    summaryRes(R.string.open_source_licenses_summary, Const.Common.licenses.size)
                    setOnPreferenceClickListener {
                        context.goActivity<LicenseActivity>()
                        true
                    }
                }

                preference {
                    titleRes(R.string.user_notice)
                    summaryRes(R.string.user_notice_summary)
                    setOnPreferenceClickListener {
                        MaterialDialog(requireContext()).show {
                            title(res = R.string.user_notice)
                            message(text = Html.fromHtml(Util.getUserNoticeContent()))
                            cancelable(false)
                            positiveButton(res = R.string.ok) {
                                Util.setReadUserNoticeVersion(Const.Common.USER_NOTICE_VERSION)
                            }
                        }
                        true
                    }
                }
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        Util.openBrowser(Const.Common.GITHUB_URL)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add("").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setIcon(R.drawable.ic_info_white_24)
        }
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MaterialDialog(requireContext()).show {
            title(res = R.string.attention)
            message(res = R.string.statement)
            positiveButton(text = "Star") { Util.openBrowser(Const.Common.GITHUB_URL) }
            negativeButton(res = R.string.cancel) { dismiss() }
        }
        return super.onOptionsItemSelected(item)
    }
}