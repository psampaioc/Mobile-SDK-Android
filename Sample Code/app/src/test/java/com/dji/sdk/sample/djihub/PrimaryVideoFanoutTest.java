package com.dji.sdk.sample.djihub;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM contract tests for bounded, isolated Primary video delivery. */
public final class PrimaryVideoFanoutTest {
    private PrimaryVideoFanoutTest() { }

    public static void run() throws Exception {
        testBlockedConsumerDoesNotDelayPeer();
        testOverflowSignalsDiscontinuityBeforeNewestPacket();
        testClosedSubscriptionReceivesNothingFurther();
        testCloseWaitsForInFlightDelivery();
    }

    private static void testBlockedConsumerDoesNotDelayPeer() throws Exception {
        PrimaryVideoFanout fanout = new PrimaryVideoFanout(2);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastReceived = new CountDownLatch(1);
        PrimaryVideoFanout.Subscription slow = fanout.add(new PrimaryVideoFanout.Listener() {
            @Override public void onVideo(byte[] data, int size, String source) {
                slowEntered.countDown();
                await(releaseSlow);
            }
        });
        PrimaryVideoFanout.Subscription fast = fanout.add(new PrimaryVideoFanout.Listener() {
            @Override public void onVideo(byte[] data, int size, String source) {
                if (data[0] == 2) fastReceived.countDown();
            }
        });
        fanout.dispatch(new byte[] {1}, 1, "CAMERA");
        check(slowEntered.await(1, TimeUnit.SECONDS), "slow subscriber began work");
        fanout.dispatch(new byte[] {2}, 1, "CAMERA");
        check(fastReceived.await(1, TimeUnit.SECONDS), "fast subscriber remains independent");
        releaseSlow.countDown();
        slow.close();
        fast.close();
        fanout.close();
    }

    private static void testOverflowSignalsDiscontinuityBeforeNewestPacket() throws Exception {
        PrimaryVideoFanout fanout = new PrimaryVideoFanout(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch newestReceived = new CountDownLatch(1);
        AtomicInteger discontinuities = new AtomicInteger();
        PrimaryVideoFanout.Subscription subscription = fanout.add(new PrimaryVideoFanout.Listener() {
            @Override public void onVideo(byte[] data, int size, String source) {
                if (data[0] == 1) {
                    firstEntered.countDown();
                    await(release);
                }
                if (data[0] == 3) {
                    check(discontinuities.get() == 1, "drop signals before newest packet");
                    newestReceived.countDown();
                }
            }
            @Override public void onDiscontinuity() { discontinuities.incrementAndGet(); }
        });
        fanout.dispatch(new byte[] {1}, 1, "CAMERA");
        check(firstEntered.await(1, TimeUnit.SECONDS), "first packet entered worker");
        fanout.dispatch(new byte[] {2}, 1, "CAMERA");
        fanout.dispatch(new byte[] {3}, 1, "CAMERA");
        release.countDown();
        check(newestReceived.await(1, TimeUnit.SECONDS), "newest queued packet delivered");
        equal(1, discontinuities.get(), "one discontinuity for overflow");
        subscription.close();
        fanout.close();
    }

    private static void testClosedSubscriptionReceivesNothingFurther() throws Exception {
        PrimaryVideoFanout fanout = new PrimaryVideoFanout(2);
        AtomicInteger delivered = new AtomicInteger();
        PrimaryVideoFanout.Subscription subscription = fanout.add(new PrimaryVideoFanout.Listener() {
            @Override public void onVideo(byte[] data, int size, String source) { delivered.incrementAndGet(); }
        });
        subscription.close();
        fanout.dispatch(new byte[] {1}, 1, "CAMERA");
        Thread.sleep(50L);
        equal(0, delivered.get(), "closed subscriber receives no packet");
        fanout.close();
    }

    private static void testCloseWaitsForInFlightDelivery() throws Exception {
        PrimaryVideoFanout fanout = new PrimaryVideoFanout(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PrimaryVideoFanout.Subscription subscription = fanout.add(new PrimaryVideoFanout.Listener() {
            @Override public void onVideo(byte[] data, int size, String source) {
                entered.countDown();
                await(release);
            }
        });
        fanout.dispatch(new byte[] {1}, 1, "CAMERA");
        check(entered.await(1, TimeUnit.SECONDS), "delivery is in flight before close");
        AtomicInteger closed = new AtomicInteger();
        Thread closer = new Thread(() -> { subscription.close(); closed.set(1); });
        closer.start();
        Thread.sleep(50L);
        equal(0, closed.get(), "close waits for an in-flight consumer callback");
        release.countDown();
        closer.join(1000L);
        equal(1, closed.get(), "close completes once callback exits");
        fanout.close();
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(1, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
