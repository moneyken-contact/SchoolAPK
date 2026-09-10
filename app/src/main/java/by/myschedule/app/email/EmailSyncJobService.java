package by.myschedule.app.email;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import by.myschedule.app.data.AppRepository;
import by.myschedule.app.model.EmailSettings;
import by.myschedule.app.notifications.NotificationHelper;
import by.myschedule.app.security.SecurePasswordStore;

public final class EmailSyncJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> runningTask;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        AppRepository repository = new AppRepository(this);
        EmailSettings settings = repository.getEmailSettings();
        String password = new SecurePasswordStore(this).load();
        if (!settings.isReady(password) || !EmailSyncGate.tryStart()) return false;

        runningTask = executor.submit(() -> {
            boolean retry = false;
            try {
                ImapClient.SyncResult result = new ImapClient().sync(
                        settings,
                        password,
                        repository.getLastImapUid()
                );
                List<String> newAnswerSubjects = new ArrayList<>();
                for (ImapClient.IncomingMessage message : result.messages) {
                    if (repository.addIncomingAnswer(
                            message.messageKey,
                            message.subject,
                            message.body,
                            LocalDate.now()
                    )) {
                        newAnswerSubjects.add(message.subject);
                    }
                }
                repository.setLastImapUid(result.highestUid);
                repository.setLastSyncAt(System.currentTimeMillis());
                NotificationHelper.showNewAnswers(this, newAnswerSubjects);
            } catch (Exception ignored) {
                retry = true;
            } finally {
                EmailSyncGate.finish();
                boolean shouldRetry = retry;
                mainHandler.post(() -> jobFinished(parameters, shouldRetry));
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        if (runningTask != null) runningTask.cancel(true);
        EmailSyncGate.finish();
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
