package app.captureinbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    const val CHANNEL = "capture_expiry"
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("capture:$id") }
    fun refresh(context: Context, c: Capture) {
        cancel(context, c.id)
        val day = c.date?.let(CaptureParser::parseDateStrict) ?: return
        val days = c.remindDays ?: return
        if (!c.dateConfirmed || c.done || day.isBefore(LocalDate.now())) return
        val target = day.minusDays(days.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delay = (target - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("capture_id" to c.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("capture:${c.id}", ExistingWorkPolicy.REPLACE, work)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("capture_id") ?: return Result.failure()
        val c = try { CaptureRepository(applicationContext).readAll().firstOrNull { it.id == id } }
            catch (_: Exception) { return Result.retry() } ?: return Result.success()
        if (c.done || !c.dateConfirmed || c.remindDays == null || !c.isUpcoming()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL, "쿠폰·일정 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("capture_id", c.id)
            .setData(android.net.Uri.parse("captureinbox://capture/${android.net.Uri.encode(c.id)}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, c.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("캡처함").setContentText("확인할 캡처가 있어요.").build()
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle(c.title)
            .setContentText("${c.dueLabel()} · ${c.date}").setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        try { manager.notify(c.id, 0, notification) } catch (_: SecurityException) { return Result.success() }
        return Result.success()
    }
}
