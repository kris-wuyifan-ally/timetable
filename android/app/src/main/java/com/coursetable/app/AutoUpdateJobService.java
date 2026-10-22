package com.coursetable.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class AutoUpdateJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                AutoUpdate.runNow(getApplicationContext());
            } catch (Throwable ignored) {
            } finally {
                jobFinished(params, false);
            }
        }, "auto-update").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
