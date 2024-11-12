package com.su.mediabox.viewmodel

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.su.mediabox.R
import com.su.mediabox.model.PluginInfo
import com.su.mediabox.net.RetrofitManager
import com.su.mediabox.net.service.PluginService
import com.su.mediabox.plugin.PluginManager
import com.su.mediabox.pluginapi.data.BaseData
import com.su.mediabox.pluginapi.data.SimpleTextData
import com.su.mediabox.pluginapi.util.TextUtil.urlDecode
import com.su.mediabox.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.su.mediabox.util.ResourceUtil.getString

class PluginInstallerViewModel : ViewModel() {

    private val tag = "PluginInstaller"

    private val _pluginInstallState =
        MutableLiveData<PluginInstallState>(PluginInstallState.LOADING)
    val pluginInstallState = _pluginInstallState.toLiveData()

    fun load(intent: Intent?) {
        intent?.data?.apply {
            logD("插件安装载入", intent.toString())
            when (scheme) {
                "file", "content" -> localLoad(this)
                "mediabox" -> onlineLoad(this)
                else -> _pluginInstallState.value =
                    PluginInstallState.ERROR(
                        buildInfoPair(
                            getString(R.string.plugin_install_error),
                            getString(R.string.plugin_install_error_scheme_error),
                            Color.RED
                        )
                    )
            }
        }
    }

    fun install() {
        viewModelScope.launch {
            when (val data = pluginInstallState.value) {
                is PluginInstallState.READY -> {
                    PluginManager.installPlugin(data.fileUrl, data.pluginInfo)?.apply {
                        _pluginInstallState.postValue(
                            if (exists())
                                PluginInstallState.SUCCESS(data.pluginInfo)
                            else
                                PluginInstallState.ERROR(
                                    buildInfoPair(
                                        getString(R.string.plugin_install_error),
                                        getString(R.string.plugin_install_error_install_error)
                                    )
                                )
                        )
                    }
                }
                else -> {
                    logE(tag, "install error: $data")
                }
            }
        }
    }

    private fun localLoad(data: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            FileUri.getPathByUri(data)?.also { url ->
                var path = url
                repeat(3) {
                    path = path.urlDecode()
                }
                logD("路径", path)
                //path.showToast(Toast.LENGTH_LONG)

                val info = mutableListOf<SimpleTextData>()

                PluginManager.parsePluginInfo(path)?.apply {
                    info.addAll(
                        buildInfoPair(
                            getString(R.string.plugin_install_source),
                            sourcePath
                        )
                    )
                    info.addAll(buildInfoPair(getString(R.string.plugin_install_name), name))
                    info.addAll(
                        buildInfoPair(
                            getString(R.string.plugin_install_package_name),
                            packageName
                        )
                    )
                    info.addAll(buildInfoPair("API", "$apiVersion"))
                    PluginManager.queryPluginInfo(packageName)?.also {
                        //已安装检测
                        info.addAll(
                            buildInfoPair(
                                getString(R.string.plugin_install_version),
                                getString(
                                    R.string.plugin_install_version_desc,
                                    it.version,
                                    version
                                ),
                                Color.RED
                            )
                        )
                        //安全检测
                        if (signature != it.signature) {
                            info.addAll(
                                buildInfoPair(
                                    getString(R.string.plugin_install_warning),
                                    getString(R.string.plugin_install_warning_security),
                                    Color.RED
                                )
                            )
                            _pluginInstallState.postValue(
                                PluginInstallState.ERROR(info)
                            )
                            return@launch
                        }
                    } ?: info.addAll(
                        buildInfoPair(
                            getString(R.string.plugin_install_version),
                            version
                        )
                    )
                    _pluginInstallState.postValue(PluginInstallState.READY(data, this, info))
                } ?: run {
                    _pluginInstallState.postValue(
                        PluginInstallState.ERROR(
                            buildInfoPair(
                                getString(R.string.plugin_install_error),
                                getString(R.string.plugin_install_error_not_a_plugin),
                                Color.RED
                            )
                        )
                    )
                }
            } ?: run {
                "插件安装错误：$data".showToast()
                _pluginInstallState.postValue(
                    PluginInstallState.ERROR(
                        buildInfoPair(
                            getString(R.string.plugin_install_error),
                            getString(R.string.plugin_install_error_read_error),
                            Color.RED
                        )
                    )
                )
            }
        }
    }

    private fun onlineLoad(data: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            //TODO 载入在线安装预览信息
            when (data.path) {
                "/install" -> try {
                    logD("在线安装", "$data")
                    val pluginInfoUrl =
                        Util.withoutExceptionGet {
                            data.getQueryParameter("previewInfoUrl")?.urlDecode()
                        }

                    if (pluginInfoUrl != null) {
                        logD("在线安装", "预览信息=$pluginInfoUrl")
                        val api = RetrofitManager.get().create(PluginService::class.java)
                        api.fetchPluginPreviewInfo(pluginInfoUrl)?.apply {
                            val info = mutableListOf<SimpleTextData>()

                            info.addAll(
                                buildInfoPair(
                                    getString(R.string.plugin_install_source),
                                    pluginInfoUrl
                                )
                            )
                            info.addAll(
                                buildInfoPair(
                                    getString(R.string.plugin_install_source),
                                    sourcePath
                                )
                            )
                            info.addAll(
                                buildInfoPair(
                                    getString(R.string.plugin_install_name),
                                    name
                                )
                            )
                            info.addAll(
                                buildInfoPair(
                                    getString(R.string.plugin_install_package_name),
                                    packageName
                                )
                            )
                            info.addAll(buildInfoPair("API", "$apiVersion"))

                            _pluginInstallState.postValue(
                                PluginInstallState.PREVIEW(this, info)
                            )

                            logD("预览信息", toString())
                            return@launch
                        }
                    }
                    PluginInstallState.ERROR(
                        buildInfoPair(
                            getString(R.string.plugin_install_error),
                            getString(R.string.plugin_install_error_online_preview_error),
                            Color.RED
                        )
                    )
                } catch (e: Exception) {
                    PluginInstallState.ERROR(
                        buildInfoPair(
                            getString(R.string.plugin_install_error),
                            getString(R.string.plugin_install_error_online_preview_error),
                            Color.RED
                        )
                    )
                }
            }
        }
    }

    fun downloadPlugin() {
        when (val data = pluginInstallState.value) {
            is PluginInstallState.PREVIEW -> {
                PluginManager.downloadPlugin(data.pluginInfo)
            }
            else -> {
                logE(tag, "downloadPlugin: $data")
            }
        }
    }

    private fun buildInfoPair(type: String, data: String, color: Int = Color.BLACK) =
        listOf(SimpleTextData(type).apply {
            spanSize = 2
            fontColor = color
            fontStyle = Typeface.BOLD
        }, SimpleTextData(data).apply {
            spanSize = 6
            gravity = Gravity.RIGHT
            fontColor = color
        })


    sealed class PluginInstallState {
        object LOADING : PluginInstallState()

        //准备就绪
        class READY(val fileUrl: Uri, val pluginInfo: PluginInfo, val installInfo: List<BaseData>) :
            PluginInstallState()

        class SUCCESS(val pluginInfo: PluginInfo) : PluginInstallState()

        class ERROR(val errorInfo: List<BaseData>) : PluginInstallState()

        //在线安装时的预览插件包信息
        class PREVIEW(val pluginInfo: PluginInfo, val previewInfo: List<BaseData>) :
            PluginInstallState()
    }
}