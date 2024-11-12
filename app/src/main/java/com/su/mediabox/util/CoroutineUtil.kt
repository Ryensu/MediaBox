package com.su.mediabox.util

import android.app.Dialog
import com.su.mediabox.util.logD
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.su.mediabox.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * 创建一个符合Dialog生命周期的协程域，会占用[Dialog.setOnCancelListener]
 */
fun Dialog.createCoroutineScope(context: CoroutineContext = Dispatchers.Default) =
    CoroutineScope(context).also { cs ->
        setOnCancelListener {
            cs.cancel()
        }
    }

val pluginExceptionHandler = CoroutineExceptionHandler { _, e ->
    e.printStackTrace()
    when (e.javaClass) {
        NoSuchMethodError::class.java, InstantiationError::class.java -> "该插件API版本过低！请更新插件！".showToast()
        else -> e.message?.showToast()
    }
}

val appCoroutineScope = CoroutineScope(Dispatchers.IO)

private val pluginIO = Dispatchers.IO + SupervisorJob() + pluginExceptionHandler
val Dispatchers.PluginIO
    get() = pluginIO

private class ViewCoroutineInterceptor(
    private val view: View
) : ContinuationInterceptor {

    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        //以Job为单位拦截，其中一个协程崩溃不会影响其他
        continuation.context[Job]?.also {
            view.addOnAttachStateChangeListener(ViewStateListener(view, it))
        }
        return continuation
    }

    private class ViewStateListener(private val view: View, private val job: Job) :
        View.OnAttachStateChangeListener, CompletionHandler {

        override fun onViewAttachedToWindow(v: View) {}

        //在Recyclerview上使用LinearLayoutManager可能并不会调用，取决于mRecycleChildrenOnDetach，因此必须手动调用setRecycleChildrenOnDetach(true)
        override fun onViewDetachedFromWindow(v: View) {
            logD("View协程", "分离视图->取消")
            view.removeOnAttachStateChangeListener(this)
            job.cancel()
        }

        override fun invoke(cause: Throwable?) {
            view.removeOnAttachStateChangeListener(this)
            job.cancel()
        }
    }
}

private const val VIEW_CS_TAG = R.id.vc_video_linear_item_tag_list

/**
 * 符合View生命周期的协程域，在分离视图时自动cancel
 *
 * 在RecyclerView.ViewHolder中使用需要注意[LinearLayoutManager.setRecycleChildrenOnDetach]
 */
val View.viewLifeCycleCoroutineScope: CoroutineScope
    get() {
        var scope = getTag(VIEW_CS_TAG) as? CoroutineScope
        if (scope != null)
            return scope

        //重新生成
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + ViewCoroutineInterceptor(this))
        setTag(VIEW_CS_TAG, scope)

        return scope
    }