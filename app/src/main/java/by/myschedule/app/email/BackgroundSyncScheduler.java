package by.myschedule.app.email;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import by.myschedule.app.data.AppRepository;
import by.myschedule.app.model.EmailSettings;
import by.myschedule.app.security.SecurePasswordStore;

public final class BackgroundSyncScheduler {
    public static final int JOB_ID = 945_993;
    public static final long INTERVAL_MILLIS = 60 * 60 * 1000L;

    private BackgroundSyncScheduler() {
    }

    public static void update(Context context) {
        Context applicationContext = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        AppRepository repository = new AppRepository(applicationContext);
        EmailSettings settings = repository.getEmailSettings();
        String password = new SecurePasswordStore(applicationContext).load();
        if (!settings.isReady(password)) {
            scheduler.cancel(JOB_ID);
            return;
        }

        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(applicationContext, EmailSyncJobService.class)
        )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MILLIS)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }
}
