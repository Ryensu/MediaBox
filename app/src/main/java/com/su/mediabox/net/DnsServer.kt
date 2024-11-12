package com.su.mediabox.net

import androidx.appcompat.app.AppCompatActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.su.mediabox.App
import com.su.mediabox.R
import com.su.mediabox.util.editor
import com.su.mediabox.util.sharedPreferences
import com.su.mediabox.util.showToast
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.lang.Exception

object DnsServer {
    class Dns(val dnsName: String, val dnsServer: String) : CharSequence {
        override val length: Int
            get() = dnsServer.length

        override fun get(index: Int): Char = dnsServer[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            return dnsServer.subSequence(startIndex, endIndex)
        }

        override fun toString(): String =
            if (dnsServer.isBlank()) dnsName else "$dnsName: $dnsServer"

        override fun equals(other: Any?): Boolean {
            return when (other) {
                null -> false
                (other === this) -> true
                is String -> other == dnsServer
                is Dns -> other.dnsServer == this.dnsServer && other.dnsName == this.dnsName
                else -> false
            }
        }

        override fun hashCode(): Int {
            var result = dnsServer.hashCode()
            result = 31 * result + dnsName.hashCode()
            return result
        }
    }

    private infix fun String.to(that: String): Dns = Dns(this, that)

    val defaultDnsServer: List<Dns> = listOf(
        "不使用" to "",
        "alidns" to "https://223.5.5.5/dns-query",
        "Cloudflare" to "https://1.0.0.1/dns-query",
        "Google" to "https://8.8.8.8/dns-query"
    )

    var dnsServer: String? = null
        set(value) {
            if (value == null || value == field) return
            App.context.sharedPreferences().editor { putString("dnsServer", value) }
            field = value
            changeDnsServer(value)
        }
        get() {
            return field ?: App.context.sharedPreferences()
                .getString("dnsServer", null).apply { field = this }
        }

    fun AppCompatActivity.selectDnsServer() {
        var initialSelection = -1
        defaultDnsServer.forEachIndexed { index, s ->
            if (s.equals(dnsServer)) initialSelection = index
        }
        if (dnsServer.isNullOrBlank()) initialSelection = 0
        MaterialDialog(this).listItemsSingleChoice(
            items = defaultDnsServer,
            initialSelection = initialSelection
        ) { _, index, _ ->
            dnsServer = defaultDnsServer[index].dnsServer
        }.positiveButton(R.string.ok).negativeButton(R.string.custom_dns_server) {
            customDnsServer()
            it.dismiss()
        }.show {
            title(res = R.string.select_dns_server)
        }
    }

    fun AppCompatActivity.customDnsServer() {
        MaterialDialog(this).input(hintRes = R.string.custom_dns_server_describe) { _, text ->
            val url = text.toString()
            try {
                // 测试url合法性
                url.toHttpUrl()
                dnsServer = url
            } catch (e: Exception) {
                e.printStackTrace()
                e.message?.showToast()
            }
        }.show()
    }
}