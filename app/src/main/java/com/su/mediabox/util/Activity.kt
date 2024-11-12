package com.su.mediabox.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.viewbinding.ViewBinding
import com.su.mediabox.pluginapi.action.Action
import com.su.mediabox.util.Util.withoutExceptionGet

inline fun <reified T : Activity> Context.goActivity(
    intent: Intent = Intent(),
    options: Bundle? = null
) {
    intent.setClass(this, T::class.java)
    if (this !is Activity)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent, options)
}

//<Action名称，Action实例>
val actionPoolMap = mutableMapOf<String, Action>()

fun <T : Action> putAction(action: T) {
    actionPoolMap[action.javaClass.simpleName] = action
}

@Suppress("UNCHECKED_CAST")
fun <T : Action> consumeActionIns(actionClass: Class<T>): T? =
    (actionPoolMap[actionClass.simpleName] as? T)?.also {
        actionPoolMap.remove(actionClass.simpleName)
    }

inline fun <reified T : Action> consumeAction(): T? = consumeActionIns(T::class.java)

fun <VB : ViewBinding> Activity.viewBind(inflater: (LayoutInflater) -> VB) =
    lazy(LazyThreadSafetyMode.NONE) {
        inflater(layoutInflater).apply {
            setContentView(root)
        }
    }

fun Context.toComponentActivity(): ComponentActivity? =
    withoutExceptionGet { toActivity() as ComponentActivity }

fun Context.toActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.toComponentActivity()
    else -> null
}