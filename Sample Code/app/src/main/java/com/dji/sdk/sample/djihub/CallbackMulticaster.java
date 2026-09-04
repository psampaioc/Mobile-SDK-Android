package com.dji.sdk.sample.djihub;

import java.util.concurrent.CopyOnWriteArrayList;

/** Small fault-isolating multicast registry used below the single DJI callback boundary. */
public final class CallbackMulticaster<T> {
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    public interface Dispatcher<T> {
        void dispatch(T listener);
    }

    private final CopyOnWriteArrayList<Entry<T>> listeners = new CopyOnWriteArrayList<>();

    public Subscription add(T listener) {
        if (listener == null) throw new NullPointerException("listener");
        Entry<T> entry = new Entry<>(listener);
        listeners.add(entry);
        return () -> {
            if (entry.close()) listeners.remove(entry);
        };
    }

    public void dispatch(Dispatcher<T> dispatcher) {
        for (Entry<T> entry : listeners) {
            try {
                entry.dispatch(dispatcher);
            } catch (RuntimeException ignored) {
                // A visual or network subscriber must never block other consumers or the DJI thread.
            }
        }
    }

    private static final class Entry<T> {
        final T listener;
        private boolean closed;
        Entry(T listener) { this.listener = listener; }

        synchronized boolean close() {
            if (closed) return false;
            closed = true;
            return true;
        }

        synchronized void dispatch(Dispatcher<T> dispatcher) {
            if (!closed) dispatcher.dispatch(listener);
        }
    }
}
