package com.jscyril.ebb.service

import android.os.Build
import android.text.format.DateFormat
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.guard.PaymentPause
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/** Quick Settings tile: pause Ebb for a payment, or tap again to end the pause early. */
class PauseTileService : TileService() {
    private val scope = MainScope()

    override fun onStartListening() { scope.launch { refresh() } }

    override fun onClick() {
        scope.launch {
            val repo = Repo.get(this@PauseTileService)
            if (PaymentPause.isPaused(repo.pausedUntil(), TrustedNow.peek(this@PauseTileService))) SelfToggle.resume(this@PauseTileService)
            else when (SelfToggle.pause(this@PauseTileService)) {
                SelfToggle.Result.NOT_SET_UP -> toast(R.string.pause_not_set_up)
                SelfToggle.Result.NONE_LEFT -> toast(R.string.pause_none_left)
                SelfToggle.Result.PAUSED -> {}
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val repo = Repo.get(this)
        val now = TrustedNow.peek(this)
        val until = repo.pausedUntil()
        val paused = PaymentPause.isPaused(until, now)
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = when {
            !SelfToggle.canWrite(this) -> getString(R.string.pause_tile_setup)
            paused -> getString(R.string.pause_tile_until, DateFormat.getTimeFormat(this).format(Date(until!!)))
            else -> getString(R.string.pause_tile_left, PaymentPause.remaining(repo.pauseUses(), now, repo.zone()))
        }
        tile.updateTile()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
