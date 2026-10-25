package io.github.jnesew.plainpoddroid.core.download;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Activity-owned guarded deletion. Durable transfers use ManualDownloadRunner. */
public final class EpisodeDownloadController implements AutoCloseable {
    public enum Phase { IDLE, DELETING, SUCCEEDED, ERROR }
    public enum Failure {
        NONE, INVALID_SELECTION, EPISODE_NOT_FOUND, ALREADY_ACTIVE, PLAYBACK_IN_USE,
        STORAGE_FAILED, BACKGROUND_WORK_UNAVAILABLE
    }
    public interface Listener { void onEpisodeDownloadStateChanged(State state); }
    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final String mediaId;
        private State(Phase phase,Failure failure,String mediaId){
            this.phase=Objects.requireNonNull(phase);this.failure=Objects.requireNonNull(failure);
            this.mediaId=Objects.requireNonNull(mediaId);
        }
        public Phase getPhase(){return phase;}
        public Failure getFailure(){return failure;}
        public String getMediaId(){return mediaId;}
        public boolean isBusy(){return phase==Phase.DELETING;}
    }
    private final Object lock=new Object();
    private final EpisodeDownloadActions actions;
    private final Executor worker,callbacks;
    private Listener listener;
    private State state=new State(Phase.IDLE,Failure.NONE,"");
    private long generation;
    private boolean closed;
    public EpisodeDownloadController(EpisodeDownloadActions actions,Executor worker,
            Executor callbacks,Listener listener){
        this.actions=Objects.requireNonNull(actions);this.worker=Objects.requireNonNull(worker);
        this.callbacks=Objects.requireNonNull(callbacks);this.listener=Objects.requireNonNull(listener);
    }
    public State getState(){synchronized(lock){return state;}}
    public boolean delete(String mediaId){
        boolean valid;
        try{ManualDownloadQueue.requireMediaId(mediaId);valid=true;}catch(IllegalArgumentException e){valid=false;}
        final long ticket;final State pending;
        synchronized(lock){
            if(closed||state.isBusy())return false;
            ticket=++generation;
            pending=new State(valid?Phase.DELETING:Phase.ERROR,valid?Failure.NONE:Failure.INVALID_SELECTION,valid?mediaId:"");
            state=pending;
        }
        callbacks.execute(()->notifyListener(pending));
        if(!valid)return false;
        try{worker.execute(()->runDelete(ticket,mediaId));}
        catch(RejectedExecutionException e){completeLater(ticket,mediaId,Failure.BACKGROUND_WORK_UNAVAILABLE);}
        return true;
    }
    private void runDelete(long ticket,String mediaId){
        Failure failure=Failure.NONE;
        try{actions.delete(mediaId);}
        catch(EpisodeDownloadException e){failure=switch(e.getReason()){
            case INVALID_SELECTION->Failure.INVALID_SELECTION;case EPISODE_NOT_FOUND->Failure.EPISODE_NOT_FOUND;
            case ALREADY_ACTIVE->Failure.ALREADY_ACTIVE;case PLAYBACK_IN_USE->Failure.PLAYBACK_IN_USE;
            default->Failure.STORAGE_FAILED;
        };}catch(RuntimeException e){failure=Failure.STORAGE_FAILED;}
        completeLater(ticket,mediaId,failure);
    }
    private void completeLater(long ticket,String mediaId,Failure failure){
        callbacks.execute(()->{
            final State result;
            synchronized(lock){
                if(closed||generation!=ticket||state.phase!=Phase.DELETING||!mediaId.equals(state.mediaId))return;
                result=new State(failure==Failure.NONE?Phase.SUCCEEDED:Phase.ERROR,failure,mediaId);state=result;
            }
            notifyListener(result);
        });
    }
    private void notifyListener(State expected){
        final Listener target;
        synchronized(lock){if(closed||state!=expected)return;target=listener;}
        if(target!=null)target.onEpisodeDownloadStateChanged(expected);
    }
    @Override public void close(){synchronized(lock){closed=true;generation++;listener=null;}}
}
