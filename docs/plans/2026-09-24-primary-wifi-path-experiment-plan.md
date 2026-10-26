---
title: "Primary RTP Wi-Fi Path Experiment Plan"
type: investigation
date: 2026-09-24
status: proposed
---

# Primary RTP Wi-Fi Path Experiment Plan

## Objective

Prove whether the Primary RTP gaps are caused by the tablet-to-Edge Wi-Fi path, and identify the smallest operational or code correction that produces reliable low-latency delivery. Preserve the current Primary-only RFC 6184 RTP/UDP contract, ports, MTU 1200, callback ownership, and low-latency decoder policy.

## Current facts

- Android has a Primary RTP worker and a separate telemetry worker/socket. They share the Wi-Fi interface but do not serialize through one application sender thread.
- Android requests a 4 MiB RTP send buffer and reports an effective 8 MiB buffer. Do not increase it blindly.
- Edge `udpsrc` currently uses its default kernel receive buffer. Ubuntu has `net.core.rmem_default=212992` and `net.core.rmem_max=4194304`.
- Edge uses `rtpjitterbuffer latency=20 drop-on-latency=true do-lost=true`. RTP continuity is observed before that element, so the jitterbuffer cannot create upstream RTP sequence gaps.
- Android local diagnostics now record callback freshness, queue depth/drop/age, completed AU freshness, local socket acceptance, one-second RTP sequence windows, maximum send duration, service state, and Wi-Fi lock state.
- During the latest tablet inspection, Battery Saver was enabled. The tablet was on `eduroam` 2.4 GHz while the Ubuntu host was on `ASUS`; that is not a valid end-to-end test topology.
- The raw DJI feed is pass-through H.264. The current MSDK/payload integration does not prove that the app can force CBR, force an IDR, cap the downlink bitrate, or choose a live-feed FPS. Do not add artificial controls or make claims about them.

## Constraints

- No flight, camera, gimbal, mission, RTK, OcuSync, AP-selection, or network-selection commands from the app.
- Do not replace RTP/UDP with TCP during this experiment.
- Do not change the 20 ms jitterbuffer policy, RTP payload type, ports, or 1200-byte MTU.
- No queue-capacity, packet-pacing, or buffer change without a baseline and matching evidence.
- A successful `DatagramSocket.send()` means Android accepted the datagram locally. It is not proof that Wi-Fi or Edge received it.

## Decision model

```text
Android generated + socket-accepted RTP advances
                    │
                    ├── Edge PCAP advances → inspect receiver/decode only
                    └── Edge PCAP absent → tablet/AP/Wi-Fi routing path

Android queue drops rise / AUs stop
                    │
                    └── Android sender-worker backlog; test a bounded queue change only then

Ubuntu UDP RcvbufErrors rises during the same window
                    │
                    └── Receiver socket burst loss; test explicit udpsrc receive buffer
```

## Ordered experiments

### W1. Establish one valid network baseline

**Goal:** Remove known invalid-network variables before attributing a pause to RTP, Android, or Edge.

1. Put the tablet and Ubuntu host on the same dedicated AP/subnet. Confirm each endpoint can reach the other IP before starting transport.
2. Prefer a dedicated 5 GHz AP, nearby, with no heavy clients. Avoid institutional/roaming Wi-Fi and 2.4 GHz for the video experiment.
3. Disable Android Battery Saver for the test. Keep the tablet screen on and the app visible while establishing the first baseline.
4. Confirm the Transport view reports the selected Edge endpoint and `Wi-Fi performance request HELD • LOW_LATENCY`.
5. Record at test start: tablet SSID/BSSID/frequency/RSSI/link rate; Ubuntu SSID, interface, IP; Edge session UUID; Android session UUID.

**Pass condition:** Both endpoints are on the intended LAN, the low-latency request is held, and Primary traffic reaches Edge for 60 seconds before any deliberate stress.

**Failure interpretation:** Do not inspect H.264 or alter queues while the endpoints are on different SSIDs/subnets, Battery Saver is enabled, or the lock is not held.

### W2. Remove the double Wi-Fi hop

**Goal:** Test the highest-value physical topology without changing software.

1. Connect the Ubuntu laptop to the dedicated router/AP by Ethernet.
2. Leave only the tablet on Wi-Fi; ensure the router bridges Ethernet and Wi-Fi on the same subnet.
3. Repeat the same fixed, 3–5 minute capture with the same Primary profile and endpoint.
4. Compare Edge ingress rate, RTP gaps, and Android local accepted RTP against W1.

**Rationale:** Tablet-to-AP-to-Wi-Fi-laptop uses airtime twice for each packet. Tablet-to-AP-to-Ethernet-laptop removes the second wireless transmission and reduces airtime contention.

**Pass condition:** No pause greater than one second, no rising Android queue-drop delta, no rising Ubuntu `RcvbufErrors` delta, and no material Android-vs-Edge RTP divergence during the capture.

### W3. Capture the boundary, not just the decoder result

**Goal:** Produce paired evidence for one healthy interval and any pause.

1. On Ubuntu, before the run, note `/proc/net/snmp` UDP counters. After it, note them again; calculate deltas, especially `RcvbufErrors`.
2. Capture only the transport ports at the NIC for 3–5 minutes:

   ```sh
   sudo timeout 300 tcpdump -ni wlp3s0 -s 0 -w /tmp/matrice-primary.pcap \
     'udp port 5600 or udp port 5500 or udp port 5501 or udp port 5502'
   ```

3. Retain the Android session directory and use `wall_clock_epoch_ms` in its local pulses to align it with PCAP time.
4. For every pause, compare, in this order: DJI callback deltas, queue drop/high-water/age, AU delta, Android local RTP generated/accepted delta, telemetry delta, Edge PCAP RTP delta, and Ubuntu UDP error deltas.

**Pass condition:** The first counter that stops advancing identifies a specific boundary. Do not classify from dashboard freshness alone.

### W4. Test explicit Edge receive-buffer absorption only if W3 shows bursts or receive-buffer errors

**Goal:** Determine whether short receiver-side socket bursts are being dropped before the jitterbuffer.

1. Keep the existing 20 ms jitterbuffer policy unchanged.
2. Change only the Edge GStreamer source to request `udpsrc buffer-size=2097152` for Primary RTP.
3. Do not change global sysctls initially: the existing `rmem_max=4194304` already permits this request.
4. Repeat the identical W3 capture and compare `RcvbufErrors`, Edge ingress, latency behavior, and jitterbuffer drop counters.

**Rationale:** This increases short kernel burst absorption before GStreamer; it does not deliberately add jitterbuffer latency or hide a multi-second outage.

**Reject condition:** Revert if it merely moves the failure into stale video, increases visible latency materially, or does not improve socket-drop evidence.

### W5. Test controlled Android packet pacing only if W3/W4 prove burst-related post-socket loss

**Goal:** Smooth a confirmed IDR/FU-A burst without blocking the DJI callback or creating backlog.

1. Require evidence that Android local RTP acceptance progresses but Edge loses a concentrated burst, with no Android queue pressure attributable to another fault.
2. Implement a feature-flagged worker-only pacing experiment; never sleep on `DjiDataHub`/DJI callback threads.
3. Pace only fragments in a large access unit with a measured, coarse worker policy. Do not assume a 100 µs park is precise on Android.
4. Compare one baseline and one paced capture using identical AP, laptop Ethernet topology, video profile, and duration.
5. Keep the variant only if it reduces PCAP/Edge losses without raising Android queue drops or producing stale-video latency.

**Reject condition:** Do not ship arbitrary micro-sleeps merely because they sound plausible. If pacing fills the 64-chunk queue or increases IDR recovery, discard the experiment.

### W6. USB tethering comparison, only when hardware allows the actual DJI path

**Goal:** Isolate Wi-Fi from transport delivery.

1. First establish whether a USB-C hub/adapter can simultaneously preserve the Cendence DJI accessory connection and provide an Android tether link to Ubuntu.
2. If the full hardware path works, run the same Primary capture over tethering and compare to W2.
3. If the tablet cannot simultaneously connect to Cendence and tether, label this a synthetic-network experiment only. It cannot prove a real DJI-video solution.

**Interpretation:** Stable tethering with unstable dedicated Wi-Fi strongly supports an AP/radio path fix. An unstable tether run does not automatically prove an Android sender fault without matching Android local evidence.

## Non-actions during this plan

- Do not attempt CBR/VBR, live bitrate, source FPS, or IDR controls unless the exact M210/Zenmuse hardware exposes and proves a supported live-feed API.
- Do not enlarge the 64-chunk queue before a capture ties overflow to a short sender stall. A larger queue can exchange packet loss for delayed video.
- Do not use TCP as a diagnostic shortcut; it introduces head-of-line blocking and changes the failure behavior being measured.
- Do not treat telemetry/metadata as the primary bandwidth cause. They share the interface but are tiny compared with Primary RTP; retain their separate workers/sockets and use W3 to prove any shared-path fault.

## Definition of done

The work is ready to move from investigation to a correction only when:

1. W1 and W2 have produced an aligned Android/Edge capture on one valid network;
2. W3 identifies whether loss occurs before Android local socket acceptance, after Android acceptance, or at the Ubuntu receive socket;
3. any W4/W5 change is justified by a before/after counter and PCAP comparison; and
4. the selected operational topology is documented with its tested AP, band, endpoint, power, and cable requirements.

## Sources

- Android `WifiManager` documentation: low-latency `WifiLock` is a radio-performance request, not a delivery acknowledgement.
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/TransportWifiLock.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/network/VideoStreamSender.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/video/BoundedVideoIngestor.java`
- Companion Edge repository: Primary GStreamer pipeline and transport configuration (external evidence; no Android repository file is changed by this plan).
