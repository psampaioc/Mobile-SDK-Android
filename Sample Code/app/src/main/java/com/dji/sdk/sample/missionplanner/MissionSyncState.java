package com.dji.sdk.sample.missionplanner;

/** Truthful local and remote mission evidence; never infers aircraft state from a draft. */
public final class MissionSyncState {
    public enum Local { LOCAL_DRAFT, APP_LOADED, UPLOADED_THIS_SESSION }
    public enum Remote { REMOTE_UNKNOWN, EXECUTING, PAUSED }

    private MissionSyncState() { }

    public static Snapshot resolve(boolean appLoaded, boolean uploadedThisSession,
            boolean draftDirty, String operatorState) {
        Local local = uploadedThisSession && !draftDirty ? Local.UPLOADED_THIS_SESSION
                : appLoaded && !draftDirty ? Local.APP_LOADED : Local.LOCAL_DRAFT;
        Remote remote = "EXECUTING".equals(operatorState) ? Remote.EXECUTING
                : "EXECUTION_PAUSED".equals(operatorState) ? Remote.PAUSED : Remote.REMOTE_UNKNOWN;
        return new Snapshot(local, remote);
    }

    public static final class Snapshot {
        private final Local local;
        private final Remote remote;

        private Snapshot(Local local, Remote remote) {
            this.local = local;
            this.remote = remote;
        }

        public Local getLocal() { return local; }
        public Remote getRemote() { return remote; }

        public String render() {
            return local.name().replace('_', ' ') + " · " + remote.name().replace('_', ' ');
        }
    }
}
