package com.dji.sdk.sample.recording;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.transport.video.EncodedVideoAccessUnitPipeline;
import com.dji.sdk.sample.transport.video.H264AccessUnit;
import com.dji.sdk.sample.transport.video.H264NalUnit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.RTKState;
import dji.common.model.LocationCoordinate2D;

/**
 * Process-wide passive Primary recording runtime. It subscribes only below {@link DjiDataHub};
 * it never registers a DJI listener, decodes H.264, alters RTP, or issues a flight command.
 */
public final class LocalPrimaryRecordingManager implements DjiDataHub.Listener,
        DjiDataHub.PrimaryVideoListener {
    public interface Listener { void onRecordingState(@NonNull Snapshot snapshot); }
    public static final class Snapshot {
        public final RecordingState state; public final String reason; public final long startedMonoNanos;
        public final String destination; public final long callbackDrops; public final long telemetryDrops;
        Snapshot(RecordingState state, String reason, long startedMonoNanos, String destination,
                 long callbackDrops, long telemetryDrops) { this.state = state; this.reason = reason; this.startedMonoNanos = startedMonoNanos; this.destination = destination; this.callbackDrops = callbackDrops; this.telemetryDrops = telemetryDrops; }
        public long elapsedMillis() { return state == RecordingState.RECORDING && startedMonoNanos > 0 ? Math.max(0L, (SystemClock.elapsedRealtimeNanos() - startedMonoNanos) / 1_000_000L) : 0L; }
    }
    private static final LocalPrimaryRecordingManager INSTANCE = new LocalPrimaryRecordingManager();
    private static final long MINIMUM_FREE_BYTES = 512L * 1024L * 1024L;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private CallbackMulticaster.Subscription hubSubscription;
    private DjiDataHub.PrimaryVideoSubscription primaryVideoSubscription;
    private RecordingState state = RecordingState.IDLE;
    private String reason = "READY";
    private String destination = "CHOOSE RECORDING FOLDER";
    private long startedMonoNanos;
    private RecordingRuntime runtime;
    private long runtimeGeneration;

    private LocalPrimaryRecordingManager() { }
    public static LocalPrimaryRecordingManager getInstance() { return INSTANCE; }
    public synchronized CallbackMulticaster.Subscription addListener(Listener listener) {
        listeners.add(listener); listener.onRecordingState(snapshotLocked());
        return () -> listeners.remove(listener);
    }
    public synchronized Snapshot snapshot(Context context) {
        if (state == RecordingState.IDLE && RecordingRecoveryStore.consumeInterrupted(context)) {
            reason = "PREVIOUS RECORDING INTERRUPTED; MP4 MAY NOT BE PLAYABLE";
        }
        if (state == RecordingState.IDLE) destination = new RecordingFolderPreferences(context).displayName();
        return snapshotLocked();
    }
    public synchronized boolean isRetentionNeeded() { return state == RecordingState.ARMING || state == RecordingState.RECORDING; }

    /** Explicit UI action. API 26 is required for safe MediaMuxer writes to SAF descriptors. */
    public synchronized boolean start(Context context) {
        if (isRetentionNeeded()) return true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { setStateLocked(RecordingState.FAILED, "LOCAL RECORDING REQUIRES ANDROID 8 OR NEWER", 0L); return false; }
        if (availableBytes(context) < MINIMUM_FREE_BYTES) {
            setStateLocked(RecordingState.FAILED, "TABLET STORAGE BELOW 512 MB RESERVE", 0L);
            return false;
        }
        RecordingFolderPreferences preferences = new RecordingFolderPreferences(context);
        Uri treeUri = preferences.getTreeUri();
        if (treeUri == null) { setStateLocked(RecordingState.FAILED, "CHOOSE A RECORDING FOLDER IN SETTINGS", 0L); return false; }
        try {
            long generation = ++runtimeGeneration;
            runtime = RecordingRuntime.create(context.getApplicationContext(), treeUri,
                    (next, nextReason, startNanos) -> onRuntimeState(generation, next,
                            nextReason, startNanos));
            destination = runtime.destination;
            DjiDataHub hub = DjiDataHub.getInstance();
            if (hubSubscription == null) hubSubscription = hub.addListener(this);
            if (primaryVideoSubscription == null) {
                primaryVideoSubscription = hub.addPrimaryVideoListener(this);
            }
            runtime.start();
            setStateLocked(RecordingState.ARMING, "WAITING FOR PRIMARY SPS/PPS + IDR", 0L);
            return true;
        } catch (IOException error) {
            runtime = null;
            setStateLocked(RecordingState.FAILED, "RECORDING FOLDER UNAVAILABLE: " + safe(error.getMessage()), 0L);
            return false;
        }
    }

    /** Explicit UI action. Safe both while waiting for a keyframe and while muxing. */
    public void stop() {
        RecordingRuntime previous;
        CallbackMulticaster.Subscription previousHubSubscription;
        DjiDataHub.PrimaryVideoSubscription previousVideoSubscription;
        synchronized (this) {
            previous = runtime;
            runtime = null;
            runtimeGeneration++;
            previousHubSubscription = hubSubscription;
            hubSubscription = null;
            previousVideoSubscription = primaryVideoSubscription;
            primaryVideoSubscription = null;
            setStateLocked(RecordingState.IDLE, "READY", 0L);
        }
        if (previousHubSubscription != null) previousHubSubscription.close();
        if (previousVideoSubscription != null) previousVideoSubscription.close();
        if (previous != null) previous.close("stopped");
    }

    @Override public void onVideo(byte[] data, int size, @NonNull String source) {
        RecordingRuntime current;
        synchronized (this) { current = runtime; }
        if (current != null) current.offer(data, size, SystemClock.elapsedRealtimeNanos());
    }

    @Override public void onDiscontinuity() {
        RecordingRuntime current;
        synchronized (this) { current = runtime; }
        if (current != null) current.signalDiscontinuity();
    }
    @Override public void onFlightState(@NonNull FlightControllerState state) {
        RecordingRuntime current; synchronized (this) { current = runtime; }
        if (current != null) current.flight(state, SystemClock.elapsedRealtimeNanos());
    }
    @Override public void onRtkState(@NonNull RTKState state) {
        RecordingRuntime current; synchronized (this) { current = runtime; }
        if (current != null) current.rtk(state, SystemClock.elapsedRealtimeNanos());
    }

    private void onRuntimeState(long generation, RecordingState next, String nextReason,
            long startNanos) {
        RecordingRuntime failed = null;
        CallbackMulticaster.Subscription failedHubSubscription = null;
        DjiDataHub.PrimaryVideoSubscription failedVideoSubscription = null;
        synchronized (this) {
            if (generation != runtimeGeneration) return;
            if (runtime == null && next != RecordingState.FAILED) return;
            if (next == RecordingState.FAILED) {
                failed = runtime;
                runtime = null;
                runtimeGeneration++;
                failedHubSubscription = hubSubscription;
                hubSubscription = null;
                failedVideoSubscription = primaryVideoSubscription;
                primaryVideoSubscription = null;
            }
            setStateLocked(next, nextReason, startNanos);
        }
        if (failedHubSubscription != null) failedHubSubscription.close();
        if (failedVideoSubscription != null) failedVideoSubscription.close();
        if (failed != null) failed.close("failed");
    }
    private void setStateLocked(RecordingState next, String nextReason, long startNanos) {
        state = next; reason = nextReason; startedMonoNanos = startNanos;
        Snapshot snapshot = snapshotLocked(); for (Listener listener : listeners) listener.onRecordingState(snapshot);
    }
    private Snapshot snapshotLocked() {
        return new Snapshot(state, reason, startedMonoNanos, destination,
                runtime == null ? 0L : runtime.callbackDrops(), runtime == null ? 0L : runtime.telemetryDrops());
    }
    private static String safe(String value) { return value == null || value.trim().isEmpty() ? "UNKNOWN ERROR" : value; }
    private static long availableBytes(Context context) {
        try { return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes(); }
        catch (RuntimeException ignored) { return Long.MAX_VALUE; }
    }

    private interface RuntimeListener { void onState(RecordingState state, String reason, long startNanos); }
    private static final class RecordingRuntime {
        private final EncodedVideoAccessUnitPipeline pipeline = new EncodedVideoAccessUnitPipeline(64, 4 * 1024 * 1024);
        private final Object wakeup = new Object(); private final RuntimeListener listener;
        private final AtomicBoolean discontinuityRequested = new AtomicBoolean();
        private final ContentResolver resolver; private final DocumentFile directory; private ParcelFileDescriptor videoDescriptor; private final RecordingTelemetryWriter telemetry;
        private final Thread worker; final String destination;
        private volatile boolean running; private volatile MediaMuxer muxer; private volatile int track = -1;
        private volatile long firstPresentationNanos = -1L; private H264NalUnit sps; private H264NalUnit pps;

        static RecordingRuntime create(Context context, Uri treeUri, RuntimeListener listener) throws IOException {
            ContentResolver resolver = context.getContentResolver(); DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
            if (tree == null || !tree.canWrite()) throw new IOException("no write permission for selected folder");
            String session = "recording-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            DocumentFile directory = tree.createDirectory(session);
            if (directory == null) throw new IOException("cannot create session folder");
            DocumentFile sidecar = directory.createFile("application/x-ndjson", "telemetry.ndjson");
            if (sidecar == null) throw new IOException("cannot create telemetry sidecar");
            OutputStream output = resolver.openOutputStream(sidecar.getUri(), "w");
            if (output == null) throw new IOException("cannot open telemetry sidecar");
            RecordingRecoveryStore.markActive(context, directory.getUri());
            return new RecordingRuntime(context, resolver, directory, new RecordingTelemetryWriter(output), listener, directory.getUri().toString());
        }
        private final Context context;
        private RecordingRuntime(Context context, ContentResolver resolver, DocumentFile directory, RecordingTelemetryWriter telemetry, RuntimeListener listener, String destination) {
            this.context = context; this.resolver = resolver; this.directory = directory; this.telemetry = telemetry; this.listener = listener; this.destination = destination;
            worker = new Thread(this::run, "dji-primary-recorder"); worker.setDaemon(true);
        }
        void start() { running = true; telemetry.lifecycle("arming", SystemClock.elapsedRealtimeNanos(), "waiting_for_sps_pps_idr"); worker.start(); }
        void offer(byte[] data, int size, long timestamp) { pipeline.offer(data, size, timestamp); synchronized (wakeup) { wakeup.notifyAll(); } }
        void signalDiscontinuity() {
            discontinuityRequested.set(true);
            synchronized (wakeup) { wakeup.notifyAll(); }
        }
        void flight(FlightControllerState state, long mono) {
            LocationCoordinate3D point = state.getAircraftLocation(); boolean position = point != null && LocationCoordinate2D.isValid(point.getLatitude(), point.getLongitude());
            boolean altitude = point != null && finite(point.getAltitude()); boolean heading = finite(state.getAircraftHeadDirection());
            telemetry.flight(mono, point == null ? null : point.getLatitude(), position, point == null ? null : point.getLongitude(), point == null ? null : (double) point.getAltitude(), altitude, (float) state.getAircraftHeadDirection(), heading);
        }
        void rtk(RTKState state, long mono) {
            LocationCoordinate2D point = state.getFusionMobileStationLocation(); boolean valid = point != null && point.isValid();
            telemetry.rtk(mono, point == null ? null : point.getLatitude(), valid, point == null ? null : point.getLongitude(), state.isRTKBeingUsed());
        }
        private void run() {
            try { while (running) {
                if (discontinuityRequested.getAndSet(false)) pipeline.signalDiscontinuity();
                int drained = pipeline.drain(128, this::writeAccessUnit);
                if (drained == 0) synchronized (wakeup) { try { wakeup.wait(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            }
                // STOP during ARMING deliberately discards pending chunks: it must not create an
                // MP4 after the operator has cancelled a recording request.
                if (muxer != null) { while (pipeline.drain(128, this::writeAccessUnit) > 0) { } pipeline.endOfStream(this::writeAccessUnit); }
            }
            catch (RuntimeException error) { listener.onState(RecordingState.FAILED, "RECORDER FAILED: " + safe(error.getMessage()), 0L); }
        }
        private void writeAccessUnit(H264AccessUnit unit) {
            H264NalUnit candidateSps = AvcSampleAdapter.firstOfType(unit, 7); if (candidateSps != null) sps = candidateSps;
            H264NalUnit candidatePps = AvcSampleAdapter.firstOfType(unit, 8); if (candidatePps != null) pps = candidatePps;
            if (muxer == null) {
                if (!unit.isKeyFrame() || sps == null || pps == null) return;
                AvcSpsDimensions dimensions = AvcSpsDimensions.read(sps.getData());
                if (dimensions == null) throw new IllegalStateException("invalid Primary AVC SPS");
                try {
                    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dimensions.width, dimensions.height);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(AvcSampleAdapter.withStartCode(sps)));
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(AvcSampleAdapter.withStartCode(pps)));
                    DocumentFile video = directory.createFile("video/mp4", "primary.mp4");
                    if (video == null) throw new IOException("cannot create Primary MP4");
                    videoDescriptor = resolver.openFileDescriptor(video.getUri(), "rw");
                    if (videoDescriptor == null) throw new IOException("cannot open Primary MP4");
                    muxer = new MediaMuxer(videoDescriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    track = muxer.addTrack(format); muxer.start(); firstPresentationNanos = unit.getReceiveTimeNanos();
                    telemetry.lifecycle("started", firstPresentationNanos, "primary_mp4"); listener.onState(RecordingState.RECORDING, "RECORDING PRIMARY", firstPresentationNanos);
                } catch (IOException error) { throw new IllegalStateException(error); }
            }
            byte[] sample = AvcSampleAdapter.mediaSample(unit); if (sample.length == 0) return;
            long presentationNanos = Math.max(firstPresentationNanos, unit.getReceiveTimeNanos());
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); info.set(0, sample.length, (presentationNanos - firstPresentationNanos) / 1000L, unit.isKeyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, ByteBuffer.wrap(sample), info);
        }
        void close(String event) {
            running = false; synchronized (wakeup) { wakeup.notifyAll(); }
            if (Thread.currentThread() != worker) try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            MediaMuxer current = muxer; muxer = null;
            if (current != null) { try { current.stop(); } catch (RuntimeException ignored) { } try { current.release(); } catch (RuntimeException ignored) { } }
            telemetry.lifecycle(event, SystemClock.elapsedRealtimeNanos(), event); try { telemetry.close(); } catch (IOException ignored) { }
            if (videoDescriptor != null) try { videoDescriptor.close(); } catch (IOException ignored) { }
            RecordingRecoveryStore.markFinalized(context);
        }
        long callbackDrops() { return pipeline.ingestionCounters().droppedChunks; }
        long telemetryDrops() { return telemetry.dropped(); }
        private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    }
}
