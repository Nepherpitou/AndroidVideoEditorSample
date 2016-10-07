package co.tula.videoencoder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;

/**
 * Created by nephe on 07.10.2016.
 */
public class TranscoderService extends JobService {

    private static final int    JOB_ID        = 1;
    private static final String ARG_INPUT     = "input";
    private static final String ARG_OUTPUT    = "output";
    private static final String ARG_SHADER    = "shader";
    private static final String ARG_RUN_COUNT = "run_count";

    private Thread transcoderThread;

    public static void enqueueTranscode(@NonNull Context context, String inputPath, String outputPath, String shader) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(ARG_INPUT, inputPath);
        extras.putString(ARG_OUTPUT, outputPath);
        extras.putString(ARG_SHADER, shader);
        extras.putInt(ARG_RUN_COUNT, 0);
        ComponentName serviceName = new ComponentName(context, TranscoderService.class);
        JobInfo jobInfo = new JobInfo.Builder(JOB_ID, serviceName)
                .setExtras(extras)
                .setBackoffCriteria(10000, JobInfo.BACKOFF_POLICY_LINEAR)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(jobInfo);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getExtras().getInt(ARG_RUN_COUNT) < 3) {
            params.getExtras().putInt(ARG_RUN_COUNT, params.getExtras().getInt(ARG_RUN_COUNT) + 1);
            transcoderThread = startTranscode(params);
        }
        return false;
    }


    @Override
    public boolean onStopJob(JobParameters params) {
        if (transcoderThread != null) {
            transcoderThread.interrupt();
        }
        return true;
    }

    private Thread startTranscode(JobParameters parameters) {
        String input  = parameters.getExtras().getString(ARG_INPUT);
        String output = parameters.getExtras().getString(ARG_OUTPUT);
        String shader = parameters.getExtras().getString(ARG_SHADER);
        TranscoderThread thread = new TranscoderThread(input, output, shader, 720, 720,
                                                       () -> jobFinished(parameters, false));
        thread.start();
        return thread;
    }
}
