package com.jscyril.ebb

import android.app.Application
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EbbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch { runCatching { Repo.get(this@EbbApp).pruneOldEvents(TrustedNow.peek(this@EbbApp)) } }
        WidgetRefresher.scheduleNext(this)
    }
}
