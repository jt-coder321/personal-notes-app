package com.jt.notes

import android.content.Intent
import android.service.quicksettings.TileService

/** The Quick Settings tile: one tap opens a new note. The label and icon come from the manifest. */
class NoteTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_NEW_NOTE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
