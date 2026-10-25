package io.github.jnesew.plainpoddroid;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** Explicit requests use any network; automatic-download preferences remain separate. */
final class ManualDownloadScheduler {
    static final int JOB_ID=0x50504445;
    private final JobScheduler jobs;
    private final ComponentName service;
    ManualDownloadScheduler(Context context) {
        jobs=context.getSystemService(JobScheduler.class);
        service=new ComponentName(context,AutomaticDownloadJobService.class);
    }
    boolean scheduleSoon() {
        try {
            if(jobs==null)return false;
            JobInfo current=jobs.getPendingJob(JOB_ID);
            if(current!=null&&service.equals(current.getService())&&!current.isPersisted())return true;
            return jobs.schedule(new JobInfo.Builder(JOB_ID,service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())==JobScheduler.RESULT_SUCCESS;
        }catch(RuntimeException e){return false;}
    }
}
