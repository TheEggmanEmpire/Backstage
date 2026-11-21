package com.eggman.backstage.schedule

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Invisible trampoline behind a schedule notification's "Start" / "Run again" action (unlock happens first). */
class ScheduleRunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(Schedules.EXTRA_ID)?.let { id ->
            com.eggman.backstage.notify.Notifications.cancelScheduleResult(this, id)
            Toast.makeText(this, Schedules.runNow(this, id, manual = true), Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
