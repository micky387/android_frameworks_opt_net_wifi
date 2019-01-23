/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;
import static android.net.wifi.WifiInfo.INVALID_RSSI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiSsid;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.WifiScoreCardProto.Event;
import com.android.server.wifi.WifiScoreCardProto.Network;
import com.android.server.wifi.WifiScoreCardProto.NetworkList;
import com.android.server.wifi.WifiScoreCardProto.Signal;
import com.android.server.wifi.WifiScoreCardProto.UnivariateStatistic;
import com.android.server.wifi.util.NativeUtil;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Retains statistical information about the performance of various
 * access points, as experienced by this device.
 *
 * The purpose is to better inform future network selection and switching
 * by this device.
 */
@NotThreadSafe
public class WifiScoreCard {

    public static final String DUMP_ARG = "WifiScoreCard";

    private static final String TAG = "WifiScoreCard";
    private static final boolean DBG = false;

    private final Clock mClock;
    private final String mL2KeySeed;
    private MemoryStore mMemoryStore;

    /** Our view of the memory store */
    public interface MemoryStore {
        /** Requests a read, with asynchronous reply */
        void read(String key, BlobListener blobListener);
        /** Requests a write, does not wait for completion */
        void write(String key, byte[] value);
    }
    /** Asynchronous response to a read request */
    public interface BlobListener {
        /** Provides the previously stored value, or null if none */
        void onBlobRetrieved(@Nullable byte[] value);
    }

    /**
     * Installs a memory store.
     *
     * Normally this happens just once, shortly after we start. But wifi can
     * come up before the disk is ready, and we might not yet have a valid wall
     * clock when we start up, so we need to be prepared to begin recording data
     * even if the MemoryStore is not yet available.
     *
     * When the store is installed for the first time, we want to merge any
     * recently recorded data together with data already in the store. But if
     * the store restarts and has to be reinstalled, we don't want to do
     * this merge, because that would risk double-counting the old data.
     *
     */
    public void installMemoryStore(@NonNull MemoryStore memoryStore) {
        Preconditions.checkNotNull(memoryStore);
        if (mMemoryStore == null) {
            mMemoryStore = memoryStore;
            Log.i(TAG, "Installing MemoryStore");
            requestReadForAllChanged();
        } else {
            mMemoryStore = memoryStore;
            Log.e(TAG, "Reinstalling MemoryStore");
            // Our caller will call doWrites() eventually, so nothing more to do here.
        }
    }

    /**
     * Timestamp of the start of the most recent connection attempt.
     *
     * Based on mClock.getElapsedSinceBootMillis().
     *
     * This is for calculating the time to connect and the duration of the connection.
     * Any negative value means we are not currently connected.
     */
    private long mTsConnectionAttemptStart = TS_NONE;
    private static final long TS_NONE = -1;

    /**
     * Timestamp captured when we find out about a firmware roam
     */
    private long mTsRoam = TS_NONE;

    /**
     * Becomes true the first time we see a poll with a valid RSSI in a connection
     */
    private boolean mPolled = false;

    /**
     * A note to ourself that we are attempting a network switch
     */
    private boolean mAttemptingSwitch = false;

    /**
     * @param clock is the time source
     * @param l2KeySeed is for making our L2Keys usable only on this device
     */
    public WifiScoreCard(Clock clock, String l2KeySeed) {
        mClock = clock;
        mL2KeySeed = "" + l2KeySeed;
        mDummyPerBssid = new PerBssid("", MacAddress.fromString(DEFAULT_MAC_ADDRESS));
    }

    /**
     * Resets the connection state
     */
    public void resetConnectionState() {
        if (DBG && mTsConnectionAttemptStart > TS_NONE && !mAttemptingSwitch) {
            Log.v(TAG, "resetConnectionState", new Exception());
        }
        resetConnectionStateInternal(true);
    }

    /**
     * @param calledFromResetConnectionState says the call is from outside the class,
     *        indicating that we need to resepect the value of mAttemptingSwitch.
     */
    private void resetConnectionStateInternal(boolean calledFromResetConnectionState) {
        if (!calledFromResetConnectionState) {
            mAttemptingSwitch = false;
        }
        if (!mAttemptingSwitch) {
            mTsConnectionAttemptStart = TS_NONE;
        }
        mTsRoam = TS_NONE;
        mPolled = false;
    }

    /**
     * Updates the score card using relevant parts of WifiInfo
     *
     * @param wifiInfo object holding relevant values.
     */
    private void update(WifiScoreCardProto.Event event, ExtendedWifiInfo wifiInfo) {
        PerBssid perBssid = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID());
        perBssid.updateEventStats(event,
                wifiInfo.getFrequency(),
                wifiInfo.getRssi(),
                wifiInfo.getLinkSpeed());

        if (DBG) Log.d(TAG, event.toString() + " ID: " + perBssid.id + " " + wifiInfo);
    }

    /**
     * Updates the score card after a signal poll
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteSignalPoll(ExtendedWifiInfo wifiInfo) {
        if (!mPolled && wifiInfo.getRssi() != INVALID_RSSI) {
            update(Event.FIRST_POLL_AFTER_CONNECTION, wifiInfo);
            mPolled = true;
        }
        update(Event.SIGNAL_POLL, wifiInfo);
        if (mTsRoam > TS_NONE && wifiInfo.getRssi() != INVALID_RSSI) {
            long duration = mClock.getElapsedSinceBootMillis() - mTsRoam;
            if (duration >= SUCCESS_MILLIS_SINCE_ROAM) {
                update(Event.ROAM_SUCCESS, wifiInfo);
                mTsRoam = TS_NONE;
            }
        }
    }
    /** Wait a few seconds before considering the roam successful */
    private static final long SUCCESS_MILLIS_SINCE_ROAM = 4_000;

    /**
     * Updates the score card after IP configuration
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpConfiguration(ExtendedWifiInfo wifiInfo) {
        update(Event.IP_CONFIGURATION_SUCCESS, wifiInfo);
        mAttemptingSwitch = false;
    }

    /**
     * Records the start of a connection attempt
     *
     * @param wifiInfo may have state about an existing connection
     */
    public void noteConnectionAttempt(ExtendedWifiInfo wifiInfo) {
        // We may or may not be currently connected. If not, simply record the start.
        // But if we are connected, wrap up the old one first.
        if (mTsConnectionAttemptStart > TS_NONE) {
            if (mPolled) {
                update(Event.LAST_POLL_BEFORE_SWITCH, wifiInfo);
            }
            mAttemptingSwitch = true;
        }
        mTsConnectionAttemptStart = mClock.getElapsedSinceBootMillis();
        mPolled = false;

        if (DBG) Log.d(TAG, "CONNECTION_ATTEMPT" + (mAttemptingSwitch ? " X " : " ") + wifiInfo);
    }

    /**
     * Updates the score card after a failed connection attempt
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteConnectionFailure(ExtendedWifiInfo wifiInfo,
                int codeMetrics, int codeMetricsProto) {
        if (DBG) {
            Log.d(TAG, "noteConnectionFailure(..., " + codeMetrics + ", " + codeMetricsProto + ")");
        }
        // TODO(b/112196799) Need to sort out the reasons better. Also, we get here
        // when we disconnect from below, so it should sometimes get counted as a
        // disconnection rather than a connection failure.
        update(Event.CONNECTION_FAILURE, wifiInfo);
        resetConnectionStateInternal(false);
    }

    /**
     * Updates the score card after network reachability failure
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpReachabilityLost(ExtendedWifiInfo wifiInfo) {
        update(Event.IP_REACHABILITY_LOST, wifiInfo);
        if (mTsRoam > TS_NONE) {
            mTsConnectionAttemptStart = mTsRoam; // just to update elapsed
            update(Event.ROAM_FAILURE, wifiInfo);
        }
        resetConnectionStateInternal(false);
    }

    /**
     * Updates the score card before a roam
     *
     * We may have already done a firmware roam, but wifiInfo has not yet
     * been updated, so we still have the old state.
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteRoam(ExtendedWifiInfo wifiInfo) {
        update(Event.LAST_POLL_BEFORE_ROAM, wifiInfo);
        mTsRoam = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Called when the supplicant state is about to change, before wifiInfo is updated
     *
     * @param wifiInfo object holding old values
     * @param state the new supplicant state
     */
    public void noteSupplicantStateChanging(ExtendedWifiInfo wifiInfo, SupplicantState state) {
        if (DBG) {
            Log.d(TAG, "Changing state to " + state + " " + wifiInfo);
        }
    }

    /**
     * Called after the supplicant state changed
     *
     * @param wifiInfo object holding old values
     */
    public void noteSupplicantStateChanged(ExtendedWifiInfo wifiInfo) {
        if (DBG) {
            Log.d(TAG, "STATE " + wifiInfo);
        }
    }

    /**
     * Updates the score card after wifi is disabled
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteWifiDisabled(ExtendedWifiInfo wifiInfo) {
        update(Event.WIFI_DISABLED, wifiInfo);
        resetConnectionStateInternal(false);
    }

    final class PerBssid {
        public int id;
        public final UUID l2Key;
        public final String ssid;
        public final MacAddress bssid;
        public boolean changed;
        private final Map<Pair<Event, Integer>, PerSignal>
                mSignalForEventAndFrequency = new ArrayMap<>();
        PerBssid(String ssid, MacAddress bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.l2Key = computeHashedL2Key(ssid, bssid);
            this.id = (int) l2Key.getLeastSignificantBits() & 0x7fffffff;
            this.changed = false;
        }
        void updateEventStats(Event event, int frequency, int rssi, int linkspeed) {
            PerSignal perSignal = lookupSignal(event, frequency);
            if (rssi != INVALID_RSSI) {
                perSignal.rssi.update(rssi);
            }
            if (linkspeed > 0) {
                perSignal.linkspeed.update(linkspeed);
            }
            if (perSignal.elapsedMs != null && mTsConnectionAttemptStart > TS_NONE) {
                long millis = mClock.getElapsedSinceBootMillis() - mTsConnectionAttemptStart;
                if (millis >= 0) {
                    perSignal.elapsedMs.update(millis);
                }
            }
            changed = true;
        }
        PerSignal lookupSignal(Event event, int frequency) {
            finishPendingRead();
            Pair<Event, Integer> key = new Pair<>(event, frequency);
            PerSignal ans = mSignalForEventAndFrequency.get(key);
            if (ans == null) {
                ans = new PerSignal(event, frequency);
                mSignalForEventAndFrequency.put(key, ans);
            }
            return ans;
        }
        AccessPoint toAccessPoint() {
            return toAccessPoint(false);
        }
        AccessPoint toAccessPoint(boolean obfuscate) {
            finishPendingRead();
            AccessPoint.Builder builder = AccessPoint.newBuilder();
            builder.setId(id);
            if (!obfuscate) {
                builder.setBssid(ByteString.copyFrom(bssid.toByteArray()));
            }
            for (PerSignal sig: mSignalForEventAndFrequency.values()) {
                builder.addEventStats(sig.toSignal());
            }
            return builder.build();
        }
        PerBssid merge(AccessPoint ap) {
            if (ap.hasId() && this.id != ap.getId()) {
                return this;
            }
            for (Signal signal: ap.getEventStatsList()) {
                Pair<Event, Integer> key = new Pair<>(signal.getEvent(), signal.getFrequency());
                PerSignal perSignal = mSignalForEventAndFrequency.get(key);
                if (perSignal == null) {
                    mSignalForEventAndFrequency.put(key, new PerSignal(signal));
                    // No need to set changed for this, since we are in sync with what's stored
                } else {
                    perSignal.merge(signal);
                    changed = true;
                }
            }
            return this;
        }
        String getL2Key() {
            return l2Key.toString();
        }
        /**
         * Called when the (asynchronous) answer to a read request comes back.
         */
        void lazyMerge(byte[] serialized) {
            if (serialized == null) return;
            byte[] old = mPendingReadFromStore.getAndSet(serialized);
            if (old != null) {
                Log.e(TAG, "More answers than we expected!");
            }
        }
        /**
         * Handles (when convenient) the arrival of previously stored data.
         *
         * The response from IpMemoryStore arrives on a different thread, so we
         * defer handling it until here, when we're on our favorite thread and
         * in a good position to deal with it. We may have already collected some
         * data before now, so we need to be prepared to merge the new and old together.
         */
        void finishPendingRead() {
            final byte[] serialized = mPendingReadFromStore.getAndSet(null);
            if (serialized == null) return;
            AccessPoint ap;
            try {
                ap = AccessPoint.parseFrom(serialized);
            } catch (InvalidProtocolBufferException e) {
                Log.e(TAG, "Failed to deserialize", e);
                return;
            }
            merge(ap);
        }
        private final AtomicReference<byte[]> mPendingReadFromStore = new AtomicReference<>();
    }

    // Returned by lookupBssid when the BSSID is not available,
    // for instance when we are not associated.
    private final PerBssid mDummyPerBssid;

    private final Map<MacAddress, PerBssid> mApForBssid = new ArrayMap<>();

    private @NonNull PerBssid lookupBssid(String ssid, String bssid) {
        MacAddress mac;
        if (ssid == null || WifiSsid.NONE.equals(ssid) || bssid == null) {
            return mDummyPerBssid;
        }
        try {
            mac = MacAddress.fromString(bssid);
        } catch (IllegalArgumentException e) {
            return mDummyPerBssid;
        }
        PerBssid ans = mApForBssid.get(mac);
        if (ans == null || !ans.ssid.equals(ssid)) {
            ans = new PerBssid(ssid, mac);
            PerBssid old = mApForBssid.put(mac, ans);
            if (old != null) {
                Log.i(TAG, "Discarding stats for score card (ssid changed) ID: " + old.id);
            }
            requestReadForPerBssid(ans);
        }
        return ans;
    }

    private void requestReadForPerBssid(final PerBssid perBssid) {
        if (mMemoryStore != null) {
            mMemoryStore.read(perBssid.getL2Key(), (value) -> perBssid.lazyMerge(value));
        }
    }

    private void requestReadForAllChanged() {
        for (PerBssid perBssid : mApForBssid.values()) {
            if (perBssid.changed) {
                requestReadForPerBssid(perBssid);
            }
        }
    }

    /**
     * Issues write requests for all changed entries
     *
     * This should be called from time to time to save the state to persistent
     * storage. Since we always check internal state first, this does not need
     * to be called very often, but it should be called before shutdown.
     *
     * @returns number of writes issued.
     */
    public int doWrites() {
        if (mMemoryStore == null) return 0;
        int count = 0;
        for (PerBssid perBssid : mApForBssid.values()) {
            if (perBssid.changed) {
                perBssid.finishPendingRead();
                byte[] serialized = perBssid.toAccessPoint(/* No BSSID */ true).toByteArray();
                mMemoryStore.write(perBssid.getL2Key(), serialized);
                perBssid.changed = false;
                count++;
            }
        }
        return count;
    }

    private UUID computeHashedL2Key(String ssid, MacAddress mac) {
        byte[][] parts = {
                // Our seed keeps the L2Keys specific to this device
                mL2KeySeed.getBytes(),
                // ssid is either quoted utf8 or hex-encoded bytes; turn it into plain bytes.
                NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(ssid)),
                // And the BSSID
                mac.toByteArray()
        };
        // Assemble the parts into one, with single-byte lengths before each.
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += 1 + parts[i].length;
        }
        byte[] mashed = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            byte[] part = parts[i];
            mashed[p++] = (byte) part.length;
            for (int j = 0; j < part.length; j++) {
                mashed[p++] = part[j];
            }
        }
        // Finally, turn that into a UUID
        return UUID.nameUUIDFromBytes(mashed);
    }

    @VisibleForTesting
    PerBssid fetchByBssid(MacAddress mac) {
        return mApForBssid.get(mac);
    }

    @VisibleForTesting
    PerBssid perBssidFromAccessPoint(String ssid, AccessPoint ap) {
        MacAddress bssid = MacAddress.fromBytes(ap.getBssid().toByteArray());
        return new PerBssid(ssid, bssid).merge(ap);
    }

    final class PerSignal {
        public final Event event;
        public final int frequency;
        public final PerUnivariateStatistic rssi;
        public final PerUnivariateStatistic linkspeed;
        @Nullable public final PerUnivariateStatistic elapsedMs;
        PerSignal(Event event, int frequency) {
            this.event = event;
            this.frequency = frequency;
            this.rssi = new PerUnivariateStatistic();
            this.linkspeed = new PerUnivariateStatistic();
            switch (event) {
                case FIRST_POLL_AFTER_CONNECTION:
                case IP_CONFIGURATION_SUCCESS:
                case CONNECTION_FAILURE:
                case WIFI_DISABLED:
                case ROAM_FAILURE:
                    this.elapsedMs = new PerUnivariateStatistic();
                    break;
                default:
                    this.elapsedMs = null;
                    break;
            }
        }
        PerSignal(Signal signal) {
            this.event = signal.getEvent();
            this.frequency = signal.getFrequency();
            this.rssi = new PerUnivariateStatistic(signal.getRssi());
            this.linkspeed = new PerUnivariateStatistic(signal.getLinkspeed());
            if (signal.hasElapsedMs()) {
                this.elapsedMs = new PerUnivariateStatistic(signal.getElapsedMs());
            } else {
                this.elapsedMs = null;
            }
        }
        void merge(Signal signal) {
            Preconditions.checkArgument(event == signal.getEvent());
            Preconditions.checkArgument(frequency == signal.getFrequency());
            rssi.merge(signal.getRssi());
            linkspeed.merge(signal.getLinkspeed());
            if (signal.hasElapsedMs()) {
                elapsedMs.merge(signal.getElapsedMs());
            }
        }
        Signal toSignal() {
            Signal.Builder builder = Signal.newBuilder();
            builder.setEvent(event)
                    .setFrequency(frequency)
                    .setRssi(rssi.toUnivariateStatistic())
                    .setLinkspeed(linkspeed.toUnivariateStatistic());
            if (elapsedMs != null) {
                builder.setElapsedMs(elapsedMs.toUnivariateStatistic());
            }
            return builder.build();
        }
    }

    final class PerUnivariateStatistic {
        public long count = 0;
        public double sum = 0.0;
        public double sumOfSquares = 0.0;
        public double minValue = Double.POSITIVE_INFINITY;
        public double maxValue = Double.NEGATIVE_INFINITY;
        public double historicalMean = 0.0;
        public double historicalVariance = Double.POSITIVE_INFINITY;
        PerUnivariateStatistic() {}
        PerUnivariateStatistic(UnivariateStatistic stats) {
            if (stats.hasCount()) {
                this.count = stats.getCount();
                this.sum = stats.getSum();
                this.sumOfSquares = stats.getSumOfSquares();
            }
            if (stats.hasMinValue()) {
                this.minValue = stats.getMinValue();
            }
            if (stats.hasMaxValue()) {
                this.maxValue = stats.getMaxValue();
            }
            if (stats.hasHistoricalMean()) {
                this.historicalMean = stats.getHistoricalMean();
            }
            if (stats.hasHistoricalVariance()) {
                this.historicalVariance = stats.getHistoricalVariance();
            }
        }
        void update(double value) {
            count++;
            sum += value;
            sumOfSquares += value * value;
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
        }
        void age() {
            //TODO  Fold the current stats into the historical stats
        }
        void merge(UnivariateStatistic stats) {
            if (stats.hasCount()) {
                count += stats.getCount();
                sum += stats.getSum();
                sumOfSquares += stats.getSumOfSquares();
            }
            if (stats.hasMinValue()) {
                minValue = Math.min(minValue, stats.getMinValue());
            }
            if (stats.hasMaxValue()) {
                maxValue = Math.max(maxValue, stats.getMaxValue());
            }
            if (stats.hasHistoricalVariance()) {
                if (historicalVariance < Double.POSITIVE_INFINITY) {
                    // Combine the estimates; c.f.
                    // Maybeck, Stochasic Models, Estimation, and Control, Vol. 1
                    // equations (1-3) and (1-4)
                    double numer1 = stats.getHistoricalVariance();
                    double numer2 = historicalVariance;
                    double denom = numer1 + numer2;
                    historicalMean = (numer1 * historicalMean
                                    + numer2 * stats.getHistoricalMean())
                                    / denom;
                    historicalVariance = numer1 * numer2 / denom;
                } else {
                    historicalMean = stats.getHistoricalMean();
                    historicalVariance = stats.getHistoricalVariance();
                }
            }
        }
        UnivariateStatistic toUnivariateStatistic() {
            UnivariateStatistic.Builder builder = UnivariateStatistic.newBuilder();
            if (count != 0) {
                builder.setCount(count)
                        .setSum(sum)
                        .setSumOfSquares(sumOfSquares)
                        .setMinValue(minValue)
                        .setMaxValue(maxValue);
            }
            if (historicalVariance < Double.POSITIVE_INFINITY) {
                builder.setHistoricalMean(historicalMean)
                        .setHistoricalVariance(historicalVariance);
            }
            return builder.build();
        }
    }

    /**
     * Returns the current scorecard in the form of a protobuf com_android_server_wifi.NetworkList
     *
     * Synchronization is the caller's responsibility.
     *
     * @param obfuscate - if true, bssids are omitted (short id only)
     */
    public byte[] getNetworkListByteArray(boolean obfuscate) {
        Map<Pair<String, Integer>, Network.Builder> networks = new ArrayMap<>();
        for (PerBssid perBssid: mApForBssid.values()) {
            int securityType = 0; //TODO(b/112196799) See ScanResultMatchInfo
            Pair<String, Integer> key = new Pair<>(perBssid.ssid, securityType);
            Network.Builder network = networks.get(key);
            if (network == null) {
                network = Network.newBuilder();
                networks.put(key, network);
                network.setSsid(perBssid.ssid);
            }
            network.addAccessPoints(perBssid.toAccessPoint(obfuscate));
        }
        NetworkList.Builder builder = NetworkList.newBuilder();
        for (Network.Builder network: networks.values()) {
            builder.addNetworks(network);
        }
        return builder.build().toByteArray();
    }

    /**
     * Returns the current scorecard as a base64-encoded protobuf
     *
     * Synchronization is the caller's responsibility.
     *
     * @param obfuscate - if true, bssids are omitted (short id only)
     */
    public String getNetworkListBase64(boolean obfuscate) {
        byte[] raw = getNetworkListByteArray(obfuscate);
        return Base64.encodeToString(raw, Base64.DEFAULT);
    }

}