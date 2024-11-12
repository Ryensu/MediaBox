package com.su.mediabox.config

import com.su.mediabox.App
import com.su.mediabox.R
import com.su.mediabox.bean.License
import com.su.mediabox.util.Util.getResColor
import com.su.mediabox.view.activity.LicenseActivity
import com.su.mediabox.view.adapter.type.initTypeList
import com.su.mediabox.view.adapter.type.linear
import com.su.mediabox.view.adapter.type.registerDataViewMap

interface Const {

    object ViewComponent {
        const val HISTORY_INFO_TAG = "history_info_tag"
        const val EPISODE_LIST_TAG = "episode_list_tag"
        const val DEFAULT_PAGE = 1
    }

    object Player {
        val SELECT_ITEM_COLOR = getResColor(R.color.unchanged_main_color_2_skin)
        val UNSELECT_ITEM_COLOR = getResColor(android.R.color.white)
    }

    interface Common {
        companion object {
            const val GITHUB_URL = "https://github.com/RyensX/MediaBox"
            const val GITHUB_NEW_ISSUE_URL = "https://github.com/RyensX/MediaBox/issues/new"
            const val GITHUB_PLUGIN_REPO_URL = "https://github.com/RyensX/MediaBoxPluginRepository"
            const val GITHUB_PLUGIN_REPO_OFFICE_URL =
                "https://ryensx.github.io/MediaBoxPluginRepository/"
            const val GITHUB_PLUGIN_REPO_DEV_DOC_URL = "https://github.com/RyensX/MediaBox/wiki"
            const val USER_NOTICE_VERSION = 2
            const val GROUP_URL = "https://pd.qq.com/s/1cii5y637"
            const val ANNOUNCEMENT="https://raw.githubusercontent.com/RyensX/MediaBox/dev/doc/announcement.json"

            val licenses = listOf(
                License("", "名称", "许可证", true),
                License("https://github.com/SkyD666/Imomoe", "Imomoe", "GPL-3.0 License"),
                License("https://github.com/jhy/jsoup", "jsoup", "MIT License"),
                License("https://github.com/coil-kt/coil", "coil", "Apache-2.0 License"),
                License("https://github.com/CarGuo/GSYVideoPlayer", "GSYVideoPlayer", "Apache-2.0 License"),
                License("https://github.com/square/okhttp", "okhttp", "Apache-2.0 License"),
                License("https://github.com/square/retrofit", "retrofit2", "Apache-2.0 License"),
                License("https://github.com/getActivity/XXPermissions", "XXPermissions", "Apache-2.0 License"),
                License("https://github.com/Kotlin/kotlinx.coroutines", "kotlinx.coroutines", "Apache-2.0 License"),
                License("https://github.com/afollestad/material-dialogs", "material-dialogs", "Apache-2.0 License"),
                License("https://github.com/lingochamp/FileDownloader", "FileDownloader", "Apache-2.0 License"),
                License("https://github.com/4thline/cling", "cling", "LGPL License"),
                License("https://github.com/eclipse/jetty.project", "jetty.project", "EPL-2.0, Apache-2.0 License"),
                License("https://github.com/NanoHttpd/nanohttpd", "nanohttpd", "BSD-3-Clause License"),
                License("https://github.com/greenrobot/EventBus", "EventBus", "Apache-2.0 License"),
                License("https://github.com/scwang90/SmartRefreshLayout", "SmartRefreshLayout", "Apache-2.0 License"),
                License("https://github.com/KwaiAppTeam/AkDanmaku", "AkDanmaku", "MIT License"),
                License("https://github.com/JakeWharton/DiskLruCache", "DiskLruCache", "Apache-2.0 License")
            )
    }
}

object Plugin {
    const val GITHUB_OFFICIAL_REPOSITORY_PLUGIN_INFO_TEMPLATE =
        "https://raw.githubusercontent.com/RyensX/MediaBoxPluginRepository/gh-pages/data/data.json"
    const val GITHUB_OFFICIAL_REPOSITORY_PAGE_PLUGIN_INFO_TEMPLATE =
        "https://raw.githubusercontent.com/RyensX/MediaBoxPluginRepository/gh-pages/data/data_{page}.json"

    const val PLUGIN_STATE_DOWNLOADABLE = 0
    const val PLUGIN_STATE_UPDATABLE = 70
    const val PLUGIN_STATE_DOWNLOADING = 1
    const val PLUGIN_STATE_OPEN = 100
}

interface Setting {
    companion object {
        const val NET_REPO_PROXY = "net_repo_proxy"
        const val SHOW_PLAY_BOTTOM_BAR = "show_play_bottom_bar"
        const val AUTO_SEEK_PLAY_POSITION = "auto_seek_play_position"
        const val APP_LAUNCH_COUNT = "app_launch_count"
        const val PLAY_ACTION_DEFAULT_CORE = "play_action_default_core"
        const val MEDIA_UPDATE_CHECK = "media_update_check"
        const val MEDIA_UPDATE_CHECK_INTERVAL = "media_update_check_interval"
        const val MEDIA_UPDATE_CHECK_LAST_TIME = "media_update_check_last_time"
        const val MEDIA_UPDATE_CHECK_ON_METERED_NET = "media_update_check_on_metered_net"
        const val COMMONLY_USED_VIDEO_SPEED = "commonly_used_video_speed"
        const val DANMAKU_SEND_COLOR = "danmaku_send_color"
        const val COMBINE_SEARCH_IGNORE_PLUGINS = "combine_search_ignore_plugins"
        const val DANMAKU_TEXT_SCALE_PERCENT = "danmaku_text_scale_percent"
        const val DANMAKU_TOP_DISPLAY_AREA_MODE = "danmaku_top_display_area_mode"
        const val DEBUG_VERSION = "debug_version"
        const val ANNOUNCEMENT = "announcement"
        const val VIDEO_PRELOAD = "video_preload"
    }
}

interface Database {
    object AppDataBase {
        const val MEDIA_DB_FILE_NAME_TEMPLATE = "media_plugin_data_%s.db"
        const val MEDIA_OFFLINE_DB_FILE_NAME_TEMPLATE = "media_plugin_offline_data_%s.db"
        const val FAVORITE_MEDIA_TABLE_NAME = "favorite"
        const val HISTORY_MEDIA_TABLE_NAME = "history"
        const val SEARCH_MEDIA_TABLE_NAME = "search"
    }

    object OfflineDataBase {
        const val PLAY_RECORD_TABLE_NAME = "playRecord"
        const val UPDATE_RECORD_TABLE_NAME = "mediaUpdateRecord"
        const val SKIP_POS_RECORD_TABLE_NAME = "skipPosRecord"
    }
}

interface DownloadAnime {
    companion object {
        val animeFilePath = App.context.getExternalFilesDir(null).toString() + "/DownloadAnime/"
    }
}

}