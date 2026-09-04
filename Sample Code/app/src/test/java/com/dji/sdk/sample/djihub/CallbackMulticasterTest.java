package com.dji.sdk.sample.djihub;

import java.util.concurrent.atomic.AtomicInteger;

/** JVM contract tests for the hub's consumer fan-out boundary. */
public final class CallbackMulticasterTest {
    private CallbackMulticasterTest() { }

    public static void run() {
        CallbackMulticaster<Runnable> callbacks = new CallbackMulticaster<>();
        AtomicInteger delivered = new AtomicInteger();
        Runnable first = delivered::incrementAndGet;
        Runnable second = () -> delivered.addAndGet(10);
        CallbackMulticaster.Subscription firstSubscription = callbacks.add(first);
        callbacks.add(second);

        callbacks.dispatch(Runnable::run);
        equal(11, delivered.get(), "all subscribers receive an event");

        firstSubscription.close();
        callbacks.dispatch(Runnable::run);
        equal(21, delivered.get(), "closed subscriptions stop receiving events");

        callbacks.add(() -> { throw new IllegalStateException("isolated consumer failure"); });
        callbacks.add(() -> delivered.addAndGet(100));
        callbacks.dispatch(Runnable::run);
        equal(131, delivered.get(), "one consumer cannot prevent later consumers");

        CallbackMulticaster<Runnable> duplicates = new CallbackMulticaster<>();
        AtomicInteger duplicateDeliveries = new AtomicInteger();
        Runnable same = duplicateDeliveries::incrementAndGet;
        CallbackMulticaster.Subscription firstDuplicate = duplicates.add(same);
        duplicates.add(same);
        firstDuplicate.close();
        duplicates.dispatch(Runnable::run);
        equal(1, duplicateDeliveries.get(), "closing one duplicate subscription preserves the other");
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected
                + ", got " + actual);
    }
}
