/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbdriver;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry;
import static info.martinmarinov.drivers.DvbException.ErrorCode.NO_DVB_DEVICES_FOUND;
class DeviceController extends Thread {
    private final DvbFrontendActivity dvbFrontendActivity;
    private volatile long desiredFreq, desiredBand;
    private volatile long currFreq, currBand;
    private volatile DeliverySystem currDelSystem, desiredDelSystem;
    private DataHandler dataHandler;
    DeviceController(DvbFrontendActivity dvbFrontendActivity, long desiredFreq, long desiredBand, DeliverySystem deliverySystem) {
        this.dvbFrontendActivity = dvbFrontendActivity;
        tuneTo(desiredFreq, desiredBand, deliverySystem);
    }
    void tuneTo(long desiredFreq, long desiredBand, DeliverySystem deliverySystem) {
        // Avoid accessing the DvbDevice from multiple threads
        this.desiredFreq = desiredFreq;
        this.desiredBand = desiredBand;
        this.desiredDelSystem = deliverySystem;
    }
    DataHandler getDataHandler() {
        return dataHandler;
    }
    @Override
    public void run() {
        try {
            List<DvbDevice> availableFrontends = DvbUsbDeviceRegistry.getUsbDvbDevices(dvbFrontendActivity.getApplicationContext());
            if (availableFrontends.isEmpty())
                throw new DvbException(NO_DVB_DEVICES_FOUND, dvbFrontendActivity.getString(R.string.no_devices_found));
            DvbDevice dvbDevice = availableFrontends.get(0);
            dvbDevice.open();
            dvbFrontendActivity.announceOpen(true, dvbDevice.getDebugString());
            dataHandler = new DataHandler(dvbFrontendActivity, dvbDevice.getTransportStream(new DvbDevice.StreamCallback() {
                @Override
                public void onStreamException(IOException e) {
                    dvbFrontendActivity.handleException(e);
                }
                @Override
                public void onStoppedStreaming() {
                    interrupt();
                }
            }));
            dataHandler.start();
            try {
                while (!isInterrupted()) {
                    if (desiredFreq != currFreq || desiredBand != currBand || desiredDelSystem != currDelSystem) {
                        dvbDevice.tune(desiredFreq, desiredBand, desiredDelSystem);
                        dvbDevice.disablePidFilter();
                        currFreq = desiredFreq;
                        currBand = desiredBand;
                        currDelSystem = desiredDelSystem;
                        dataHandler.setFreqAndBandwidth(currFreq, currBand);
                        dataHandler.reset();
                    }
                    int snr = dvbDevice.readSnr();
                    int qualityPercentage = Math.round(100.0f * (0xFFFF - dvbDevice.readBitErrorRate()) / (float) 0xFFFF);
                    int droppedUsbFps = dvbDevice.readDroppedUsbFps();
                    int rfStrength = dvbDevice.readRfStrengthPercentage();
                    Set<DvbStatus> status = dvbDevice.getStatus();
                    boolean hasSignal = status.contains(DvbStatus.FE_HAS_SIGNAL);
                    boolean hasCarrier = status.contains(DvbStatus.FE_HAS_CARRIER);
                    boolean hasSync = status.contains(DvbStatus.FE_HAS_SYNC);
                    boolean hasLock = status.contains(DvbStatus.FE_HAS_LOCK);
                    dvbFrontendActivity.announceMeasurements(snr, qualityPercentage, droppedUsbFps, rfStrength, hasSignal, hasCarrier, hasSync, hasLock);
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException ie) {
                // Interrupted exceptions are ok
            } finally {
                dataHandler.interrupt();
                //noinspection ThrowFromFinallyBlock
                dataHandler.join();
                try {
                    dvbDevice.close();
                } catch (IOException e) {
                    dvbFrontendActivity.handleException(e);
                }
            }
        } catch (Exception e) {
            dvbFrontendActivity.handleException(e);
        } finally {
            dvbFrontendActivity.announceOpen(false, null);
        }
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers.file;
import android.content.res.Resources;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.usbxfer.ByteSource;
import info.martinmarinov.drivers.tools.io.ThrottledTsSource;
import info.martinmarinov.drivers.DeliverySystem;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_OPEN_USB;
/**
 * This is a DvbDevice that can be used for debugging purposes.
 * It takes a file and streams it as if it is a stream coming from a real USB device.
 */
public class DvbFileDevice extends DvbDevice {
    private final static DvbCapabilities CAPABILITIES = new DvbCapabilities(174000000L, 862000000L, 166667L, SetUtils.setOf(DeliverySystem.values()));
    private final Resources resources;
    private final File file;
    private final long freq;
    private final long bandwidth;
    private boolean isTuned = false;
    public DvbFileDevice(Resources resources, File file, long freq, long bandwidth) {
        super(DvbDemux.DvbDmxSwfilter());
        this.resources = resources;
        this.file = file;
        this.freq = freq;
        this.bandwidth = bandwidth;
    }
    @Override
    public void open() throws DvbException {
        if (!file.canRead()) throw new DvbException(CANNOT_OPEN_USB, new IOException());
    }
    @Override
    public DeviceFilter getDeviceFilter() {
        return new FileDeviceFilter(resources.getString(R.string.rec_name, freq / 1_000_000.0, bandwidth / 1_000_000.0), file);
    }
    @Override
    protected void tuneTo(long freqHz, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        this.isTuned = freqHz == this.freq && bandwidthHz == this.bandwidth;
    }
    @Override
    public DvbCapabilities readCapabilities() throws DvbException {
        return CAPABILITIES;
    }
    @Override
    public int readSnr() throws DvbException {
        return isTuned ? 400 : 0;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        return isTuned ? 100 : 0;
    }
    @Override
    public int readBitErrorRate() throws DvbException {
        return isTuned ? 0 : 0xFFFF;
    }
    @Override
    public Set<DvbStatus> getStatus() throws DvbException {
        if (isTuned) return SetUtils.setOf(DvbStatus.FE_HAS_SIGNAL, DvbStatus.FE_HAS_CARRIER, DvbStatus.FE_HAS_VITERBI, DvbStatus.FE_HAS_SYNC, DvbStatus.FE_HAS_LOCK);
        return SetUtils.setOf();
    }
    @Override
    protected ByteSource createTsSource() {
        return new ThrottledTsSource(file);
    }
    @Override
    public String getDebugString() {
        return "File - "+file.getAbsolutePath();
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_OPEN_USB;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.Serializable;
import java.util.List;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry;
import info.martinmarinov.dvbservice.tools.InetAddressTools;
import info.martinmarinov.dvbservice.tools.TsDumpFileUtils;
public class DvbService extends Service {
    private static final String TAG = DvbService.class.getSimpleName();
    private final static int ONGOING_NOTIFICATION_ID = 743713489; // random id
    public static final String BROADCAST_ACTION = "info.martinmarinov.dvbservice.DvbService.BROADCAST";
    private final static String DEVICE_FILTER = "DeviceFilter";
    private final static String STATUS_MESSAGE = "StatusMessage";
    private static Thread worker;
    static void requestOpen(Activity activity, DeviceFilter deviceFilter) {
        Intent intent = new Intent(activity, DvbService.class)
                .putExtra(DEVICE_FILTER, deviceFilter);
        activity.startService(intent);
    }
    static StatusMessage parseMessage(Intent intent) {
        return (StatusMessage) intent.getSerializableExtra(STATUS_MESSAGE);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final DeviceFilter deviceFilter = (DeviceFilter) intent.getSerializableExtra(DEVICE_FILTER);
        // Kill existing connection
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
            try {
                worker.join(15_000L);
            } catch (InterruptedException ignored) {}
            if (worker != null && worker.isAlive()) {
                throw new RuntimeException("Cannot stop existing service");
            }
        }
        worker = new Thread() {
            @Override
            public void run() {
                DvbServer dvbServer = null;
                try {
                    dvbServer = new DvbServer(getDeviceFromFilter(deviceFilter));
                    DvbServerPorts dvbServerPorts = dvbServer.bind(InetAddressTools.getLocalLoopback());
                    dvbServer.open();
                    // Device was opened! Tell client it's time to connect
                    broadcastStatus(new StatusMessage(null, dvbServerPorts, deviceFilter));
                    startForeground();
                    dvbServer.serve();
                } catch (Exception e) {
                    e.printStackTrace();
                    broadcastStatus(new StatusMessage(e, null, deviceFilter));
                } finally {
                    if (dvbServer != null) dvbServer.close();
                }
                Log.d(TAG, "Finished");
                worker = null;
                stopSelf();
            }
        };
        worker.start();
        return START_NOT_STICKY;
    }
    private void startForeground() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String NOTIFICATION_CHANNEL_ID = "DvbDriver";
        if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "DVB-T driver notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            // Configure the notification channel.
            notificationChannel.setDescription("When DVB-T driver operates");
            notificationChannel.enableVibration(false);
            notificationManager.createNotificationChannel(notificationChannel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.driver_description))
                .setSmallIcon(R.drawable.ic_notification);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder = builder
                    .setPriority(Notification.PRIORITY_MAX);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, builder.build());
        }
    }
    private DvbDevice getDeviceFromFilter(DeviceFilter deviceFilter) throws DvbException {
        List<DvbDevice> dvbDevices = DvbUsbDeviceRegistry.getUsbDvbDevices(this);
        dvbDevices.addAll(TsDumpFileUtils.getDevicesForAllRecordings(this));
        for (DvbDevice dvbDevice : dvbDevices) {
            if (dvbDevice.getDeviceFilter().equals(deviceFilter)) return dvbDevice;
        }
        throw new DvbException(CANNOT_OPEN_USB, getString(R.string.device_no_longer_available));
    }
    private void broadcastStatus(StatusMessage statusMessage) {
        Intent intent = new Intent(BROADCAST_ACTION)
                        .putExtra(STATUS_MESSAGE, statusMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    static class StatusMessage implements Serializable {
        final Exception exception;
        final DvbServerPorts serverAddresses;
        final DeviceFilter deviceFilter;
        private StatusMessage(Exception exception, DvbServerPorts serverAddresses, DeviceFilter deviceFilter) {
            this.exception = exception;
            this.serverAddresses = serverAddresses;
            this.deviceFilter = deviceFilter;
        }
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
class DvbServer implements Closeable {
    private final static int SOCKET_TIMEOUT_MS = 60 * 1_000;
    private final ServerSocket controlSocket = new ServerSocket();
    private final ServerSocket transferSocket = new ServerSocket();
    private final DvbDevice dvbDevice;
    DvbServer(DvbDevice dvbDevice) throws IOException {
        this.dvbDevice = dvbDevice;
    }
    DvbServerPorts bind(InetAddress address) throws IOException {
        return bind(new InetSocketAddress(address, 0));
    }
    private DvbServerPorts bind(InetSocketAddress address) throws IOException {
        try {
            controlSocket.bind(address);
            transferSocket.bind(address);
            controlSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            transferSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            if (!controlSocket.getInetAddress().equals(transferSocket.getInetAddress()))
                throw new IllegalStateException();
            return new DvbServerPorts(controlSocket.getLocalPort(), transferSocket.getLocalPort());
        } catch (IOException e) {
            close();
            throw e;
        }
    }
    void open() throws DvbException {
        dvbDevice.open();
    }
    @Override
    public void close() {
        quietClose(dvbDevice);
        quietClose(controlSocket);
        quietClose(transferSocket);
    }
    void serve() throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        Socket control = null;
        try {
            control = controlSocket.accept();
            control.setTcpNoDelay(true);
            inputStream = control.getInputStream();
            outputStream = control.getOutputStream();
            final InputStream finInputStream = inputStream;
            TransferThread worker = new TransferThread(dvbDevice, transferSocket, new TransferThread.OnClosedCallback() {
                @Override
                public void onClosed() {
                    // Close input stream to cancel the request parsing
                    quietClose(finInputStream);
                }
            });
            try {
                worker.start();
                while (worker.isAlive() && !Thread.currentThread().isInterrupted()) {
                    Request c = Request.parseAndExecute(inputStream, outputStream, dvbDevice);
                    if (c == Request.REQ_EXIT) break;
                }
            } catch (SocketException e) {
                IOException workerException = worker.signalAndWaitToDie();
                if (workerException != null) throw workerException;
                throw e;
            }
            // Finish successfully due to exit command, if worker failed for anything other
            // than a socket exception, throw it
            IOException workerException = worker.signalAndWaitToDie();
            if (workerException != null && !(workerException instanceof SocketException)) throw workerException;
        } finally {
            quietClose(inputStream);
            quietClose(outputStream);
            quietClose(control);
        }
    }
    
    private static void quietClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private static void quietClose(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private static void quietClose(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import info.martinmarinov.drivers.tools.FastIntFilter;
import info.martinmarinov.usbxfer.ByteSink;
import info.martinmarinov.drivers.tools.io.NativePipe;
public class DvbDemux implements ByteSink,Closeable {
    private static final boolean DVB_DEMUX_FEED_ERR_PKTS = true;
    private static final boolean CHECK_PACKET_INTEGRITY = true;
    private final int pktSize;
    private final byte[] tsBuf = new byte[204];
    private final NativePipe pipe;
    private final OutputStream out;
    private final FastIntFilter filter = new FastIntFilter(0x1fff);
    @SuppressWarnings("ConstantConditions")
    private final byte[] cntStorage = CHECK_PACKET_INTEGRITY ? new byte[(0x1fff / 2) + 1] : null;
    private int tsBufP = 0;
    private int droppedUsbFps;
    private long lastUpdated;
    private boolean passFullTsStream = false;
    public static DvbDemux DvbDmxSwfilter() {
        return new DvbDemux(188);
    }
    private DvbDemux(int pktSize) {
        this.pktSize = pktSize;
        this.pipe = new NativePipe();
        this.out = pipe.getOutputStream();
        reset();
    }
    void setPidFilter(int ... pids) {
        passFullTsStream = false;
        filter.setFilter(pids);
    }
    void disablePidFilter() {
        passFullTsStream = true;
    }
    @Override
    public void consume(byte[] buf, int count) throws IOException {
        int p = 0;
        if (tsBufP != 0) { /* tsbuf[0] is now 0x47. */
            int i = tsBufP;
            int j = pktSize - i;
            if (count < j) {
                System.arraycopy(buf, 0, tsBuf, i, count);
                tsBufP += count;
                return;
            }
            System.arraycopy(buf, 0, tsBuf, i, j);
            if ((tsBuf[0] & 0xFF) == 0x47) { /* double check */
                swfilterPacket(tsBuf, 0);
            }
            tsBufP = 0;
            p += j;
        }
        while (true) {
            p = findNextPacket(buf, p, count);
            if (p >= count) {
                break;
            }
            if (count - p < pktSize) {
                break;
            }
            if (pktSize == 204 && (buf[p] & 0xFF) == 0xB8) {
                System.arraycopy(buf, p, tsBuf, 0, 188);
                tsBuf[0] = (byte) 0x47;
                swfilterPacket(tsBuf, 0);
            } else {
                swfilterPacket(buf, p);
            }
            p += pktSize;
        }
        int i = count - p;
        if (i != 0) {
            System.arraycopy(buf, p, tsBuf, 0, i);
            tsBufP = i;
            if (pktSize == 204 && (tsBuf[0] & 0xFF) == 0xB8) {
                tsBuf[0] = (byte) 0x47;
            }
        }
    }
    int getDroppedUsbFps() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdated;
        lastUpdated = now;
        double fps = droppedUsbFps * 1000.0 / elapsed;
        droppedUsbFps = 0;
        return (int) Math.abs(fps);
    }
    private int findNextPacket(byte[] buf, int pos, int count) {
        int start = pos, lost;
        while (pos < count) {
            if ((buf[pos] & 0xFF) == 0x47 ||
                    (pktSize == 204 && (buf[pos] & 0xFF) == 0xB8)) {
                break;
            }
            pos++;
        }
        lost = pos - start;
        if (lost != 0) {
		    /* This garbage is part of a valid packet? */
            int backtrack = pos - pktSize;
            if (backtrack >= 0 && ((buf[backtrack] & 0xFF) == 0x47 ||
                    (pktSize == 204 && (buf[backtrack] & 0xFF) == 0xB8))) {
                return backtrack;
            }
        }
        return pos;
    }
    private void swfilterPacket(byte[] buf, int offset) throws IOException {
        int pid = tsPid(buf, offset);
        if ((buf[offset+1] & 0x80) != 0) {
            droppedUsbFps++; // count this as dropped frame
		    /* data in this packet cant be trusted - drop it unless
		     * constant DVB_DEMUX_FEED_ERR_PKTS is set */
            if (!DVB_DEMUX_FEED_ERR_PKTS) return;
        } else {
            if (CHECK_PACKET_INTEGRITY) {
                if (!checkSequenceIntegrity(pid, buf, offset)) droppedUsbFps++;
            }
        }
        if (passFullTsStream || filter.isFiltered(pid)) out.write(buf, offset, 188);
    }
    private boolean checkSequenceIntegrity(int pid, byte[] buf, int offset) {
        if (pid == 0x1FFF) return true; // This PID is garbage that should be ignored always
        int pidLoc = pid >> 1;
        if ((pid & 1) == 0) {
            // even pids are stored on left
            if ((buf[offset + 3] & 0x10) != 0) {
                int val = ((cntStorage[pidLoc] & 0xF0) + 0x10) & 0xF0;
                cntStorage[pidLoc] = (byte) ((cntStorage[pidLoc] & 0x0F) | val);
            }
            if ((buf[offset + 3] & 0x0F) != ((cntStorage[pidLoc] & 0xF0) >> 4)) {
                int val = (buf[offset + 3] & 0x0F) << 4;
                cntStorage[pidLoc] = (byte) ((cntStorage[pidLoc] & 0x0F) | val);
                return false;
            } else {
                return true;
            }
        } else {
            // odd pids are stored on right
            if ((buf[offset + 3] & 0x10) != 0) {
                int val = ((cntStorage[pidLoc] & 0x0F) + 0x01) & 0x0F;
                cntStorage[pidLoc] = (byte) ((cntStorage[pidLoc] & 0xF0) | val);
            }
            if ((buf[offset + 3] & 0x0F) != (cntStorage[pidLoc] & 0x0F)) {
                int val = buf[offset + 3] & 0x0F;
                cntStorage[pidLoc] = (byte) ((cntStorage[pidLoc] & 0xF0) | val);
                return false;
            } else {
                return true;
            }
        }
    }
    private static int tsPid(byte[] buf, int offset) {
        return ((buf[offset+1] & 0x1F) << 8) + (buf[offset+2] & 0xFF);
    }
    void reset() {
        droppedUsbFps = 0;
        lastUpdated = System.currentTimeMillis();
        if (!passFullTsStream) setPidFilter(0); // by default we let through only pid 0
    }
    @Override
    public void close() throws IOException {
        pipe.close();
    }
    InputStream getInputStream() {
        return pipe.getInputStream();
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers;
import androidx.annotation.NonNull;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import info.martinmarinov.usbxfer.ByteSource;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
public abstract class DvbDevice implements Closeable {
    private final DvbDemux dvbDemux;
    private DataPump dataPump;
    protected DvbDevice(DvbDemux dvbDemux) {
        this.dvbDemux = dvbDemux;
    }
    public abstract void open() throws DvbException;
    public abstract DeviceFilter getDeviceFilter();
    public abstract DvbCapabilities readCapabilities() throws DvbException;
    public abstract int readSnr() throws DvbException;
    public abstract int readRfStrengthPercentage() throws DvbException;
    public abstract int readBitErrorRate() throws DvbException;
    public abstract Set<DvbStatus> getStatus() throws DvbException;
    // Debug string to identify device for debugging purposes
    public abstract String getDebugString();
    protected abstract void tuneTo(long freqHz, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException;
    public final void tune(long freqHz, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        tuneTo(freqHz, bandwidthHz, deliverySystem);
        if (dvbDemux != null) dvbDemux.reset();
    }
    public int readDroppedUsbFps() throws DvbException {
        return dvbDemux.getDroppedUsbFps();
    }
    public void setPidFilter(int... pids) throws DvbException {
        dvbDemux.setPidFilter(pids);
    }
    public void disablePidFilter()throws DvbException {
        dvbDemux.disablePidFilter();
    }
    @Override
    public void close() throws IOException {
        while (dataPump != null && dataPump.isAlive()) {
            dataPump.interrupt();
            try {
                dataPump.join();
            } catch (InterruptedException ignored) {}
        }
        dvbDemux.close();
    }
    public InputStream getTransportStream(StreamCallback streamCallback) throws DvbException {
        if (dataPump != null && dataPump.isAlive()) throw new DvbException(BAD_API_USAGE, "Data stream is still running. Please close the input stream first to start a new one");
        dataPump = new DataPump(streamCallback);
        dataPump.start();
        return dvbDemux.getInputStream();
    }
    public interface StreamCallback {
        void onStreamException(IOException exception);
        void onStoppedStreaming();
    }
    protected abstract ByteSource createTsSource();
    /** This thread reads from the USB device as quickly as possible and puts it into the circular buffer.
     * This thread also does pid filtering. **/
    private class DataPump extends Thread {
        private final StreamCallback callback;
        private DataPump(StreamCallback callback) {
            this.callback = callback;
        }
        @Override
        public void interrupt() {
            super.interrupt();
            try {
                dvbDemux.close();
            } catch (IOException e) {
                e.printStackTrace();
                // Close the pipes
            }
        }
        @Override
        public void run() {
            setName(DataPump.class.getSimpleName());
            setPriority(MAX_PRIORITY);
            ByteSource tsSource = createTsSource();
            try {
                tsSource.open();
                dvbDemux.reset();
                while (!isInterrupted()) {
                    try {
                        tsSource.readNext(dvbDemux);
                    } catch (IOException e) {
                        // Pipe is closed from other end
                        interrupt();
                    }
                }
            } catch (InterruptedException ignored) {
                // interrupted is ok
            } catch (IOException e) {
                callback.onStreamException(e);
            } finally {
                try {
                    tsSource.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                callback.onStoppedStreaming();
            }
        }
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.usbxfer;
import java.io.Closeable;
import java.io.IOException;
public interface ByteSource extends Closeable {
    void open() throws IOException;
    void readNext(ByteSink sink) throws IOException, InterruptedException;
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers;
import androidx.annotation.NonNull;
import java.util.Set;
public class DvbCapabilities {
    private final long frequencyMin;
    private final long frequencyMax;
    private final long frequencyStepSize;
    private final @NonNull Set<DeliverySystem> supportedDeliverySystems;
    public DvbCapabilities(long frequencyMin, long frequencyMax, long frequencyStepSize, @NonNull Set<DeliverySystem> supportedDeliverySystems) {
        this.frequencyMin = frequencyMin;
        this.frequencyMax = frequencyMax;
        this.frequencyStepSize = frequencyStepSize;
        this.supportedDeliverySystems = supportedDeliverySystems;
    }
    public long getFrequencyMin() {
        return frequencyMin;
    }
    public long getFrequencyMax() {
        return frequencyMax;
    }
    public long getFrequencyStepSize() {
        return frequencyStepSize;
    }
    public @NonNull Set<DeliverySystem> getSupportedDeliverySystems() {
        return supportedDeliverySystems;
    }
    @Override
    public String toString() {
        return "DvbCapabilities{" +
                "frequencyMin=" + frequencyMin +
                ", frequencyMax=" + frequencyMax +
                ", frequencyStepSize=" + frequencyStepSize +
                ", supportedDeliverySystems=" + supportedDeliverySystems +
                '}';
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import info.martinmarinov.drivers.DvbDevice;
class TransferThread extends Thread {
    private final DvbDevice dvbDevice;
    private final ServerSocket serverSocket;
    private final OnClosedCallback callback;
    private IOException lastException = null;
    private InputStream transportStream;
    TransferThread(DvbDevice dvbDevice, ServerSocket serverSocket, OnClosedCallback callback) {
        this.dvbDevice = dvbDevice;
        this.serverSocket = serverSocket;
        this.callback = callback;
    }
    @Override
    public void interrupt() {
        super.interrupt();
        quietClose(serverSocket);
        quietClose(transportStream);
    }
    @Override
    public void run() {
        setName(TransferThread.class.getSimpleName());
        setPriority(NORM_PRIORITY);
        OutputStream os = null;
        Socket socket = null;
        try {
            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            byte[] buf = new byte[5 * 188];
            os = socket.getOutputStream();
            transportStream = dvbDevice.getTransportStream(new DvbDevice.StreamCallback() {
                @Override
                public void onStreamException(IOException exception) {
                    lastException = exception;
                    interrupt();
                }
                @Override
                public void onStoppedStreaming() {
                    interrupt();
                }
            });
            while (!isInterrupted()) {
                int inlength = transportStream.read(buf);
                if (inlength > 0) {
                    os.write(buf, 0, inlength);
                } else {
                    // No data, sleep for a bit until available
                    quietSleep(10);
                }
            }
        } catch (IOException e) {
            lastException = e;
        } finally {
            quietClose(os);
            quietClose(socket);
            quietClose(transportStream);
            callback.onClosed();
        }
    }
    private void quietSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            interrupt();
        }
    }
    private void quietClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }
    private void quietClose(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }
    private void quietClose(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }
    IOException signalAndWaitToDie() {
        if (isAlive()) {
            interrupt();
            try {
                join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return lastException;
    }
    interface OnClosedCallback {
        void onClosed();
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers.usb;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.DeviceFilterMatcher;
import info.martinmarinov.drivers.usb.af9035.Af9035DvbDeviceCreator;
import info.martinmarinov.drivers.usb.cxusb.CxUsbDvbDeviceCreator;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl2xx2DvbDeviceCreator;
public class DvbUsbDeviceRegistry {
    public static DvbUsbDevice.Creator[] AVAILABLE_DRIVERS = new DvbUsbDevice.Creator[] {
            new Rtl2xx2DvbDeviceCreator(),
            new CxUsbDvbDeviceCreator(),
            new Af9035DvbDeviceCreator()
    };
    /**
     * Checks if the {@link UsbDevice} could be handled by the available drivers and returns a
     * {@link DvbDevice} if the device is supported.
     * @param usbDevice a {@link UsbDevice} device that is attached to the system
     * @param context the application context, used for accessing usb system service and obtaining permissions
     * @return a valid {@link DvbDevice} to control the {@link UsbDevice} as a DVB frontend or
     * a null if none of the available drivers can handle the provided {@link UsbDevice}
     */
    private static DvbDevice getDvbUsbDeviceFor(UsbDevice usbDevice, Context context) throws DvbException {
        for (DvbUsbDevice.Creator c : AVAILABLE_DRIVERS) {
            DeviceFilterMatcher deviceFilterMatcher = new DeviceFilterMatcher(c.getSupportedDevices());
            DeviceFilter filter = deviceFilterMatcher.getFilter(usbDevice);
            if (filter != null) {
                DvbDevice dvbDevice = c.create(usbDevice, context, filter);
                if (dvbDevice != null) return dvbDevice;
            }
        }
        return null;
    }
    /**
     * Gets a {@link DvbDevice} if a supported DVB USB dongle is connected to the system.
     * If multiple dongles are connected, a {@link Collection} would be returned
     * @param context a context for obtaining {@link Context#USB_SERVICE}
     * @return a {@link Collection} of available {@link DvbDevice} devices. Can be empty.
     */
    public static List<DvbDevice> getUsbDvbDevices(Context context) throws DvbException {
        List<DvbDevice> availableDvbDevices = new ArrayList<>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        Collection<UsbDevice> availableDevices = manager.getDeviceList().values();
        DvbException lastException = null;
        for (UsbDevice usbDevice : availableDevices) {
            try {
                DvbDevice frontend = getDvbUsbDeviceFor(usbDevice, context);
                if (frontend != null) availableDvbDevices.add(frontend);
            } catch (DvbException e) {
                // Failed to initialize this device, try next and capture exception
                e.printStackTrace();
                lastException = e;
            }
        }
        if (availableDvbDevices.isEmpty()) {
            if (lastException != null) throw  lastException;
        }
        return availableDvbDevices;
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice.tools;
import android.content.Context;
import android.content.res.Resources;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.file.DvbFileDevice;
public class TsDumpFileUtils {
    private final static String TAG = TsDumpFileUtils.class.getSimpleName();
    private final static Locale DEFAULT_LOCALE = Locale.US;
    private final static DateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss", DEFAULT_LOCALE);
    private static File getRoot(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }
    public static File getFor(Context ctx, long freq, long bandwidth, Date date) {
        File root = getRoot(ctx);
        String timestamp = DATE_FORMAT.format(date);
        String filename = String.format(DEFAULT_LOCALE, "mux_%d_%d_%s.ts", freq, bandwidth, timestamp);
        return new File(root, filename);
    }
    public static List<DvbDevice> getDevicesForAllRecordings(Context ctx) {
        LinkedList<DvbDevice> devices = new LinkedList<>();
        Resources resources = ctx.getResources();
        File root = getRoot(ctx);
        if (root == null) return devices;
        Log.d(TAG, "You can place ts files in "+root.getPath());
        File[] files = root.listFiles();
        for (File file : files) {
            FreqBandwidth freqAndBandwidth = getFreqAndBandwidth(file);
            if (freqAndBandwidth != null) {
                devices.add(new DvbFileDevice(resources, file, freqAndBandwidth.freq, freqAndBandwidth.bandwidth));
            }
        }
        return devices;
    }
    @VisibleForTesting
    static FreqBandwidth getFreqAndBandwidth(File file) {
        if (!"ts".equals(getExtension(file))) return null;
        String[] parts = file.getName().toLowerCase().split("_");
        if (parts.length != 4) return null;
        if (!"mux".equals(parts[0])) return null;
        try {
            long freq = Long.parseLong(parts[1]);
            long bandwidth = Long.parseLong(parts[2]);
            return new FreqBandwidth(freq, bandwidth);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    private static String getExtension(File file) {
        String[] parts = file.getName().toLowerCase().split("\\.");
        if (parts.length == 0) return null;
        return parts[parts.length-1];
    }
    @VisibleForTesting
    static class FreqBandwidth {
        @VisibleForTesting
        final long freq, bandwidth;
        private FreqBandwidth(long freq, long bandwidth) {
            this.freq = freq;
            this.bandwidth = bandwidth;
        }
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers.usb;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_PLATFORM;
import static info.martinmarinov.drivers.DvbException.ErrorCode.USB_PERMISSION_DENIED;
import static info.martinmarinov.drivers.tools.Retry.retry;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.Check;
import info.martinmarinov.drivers.tools.ThrowingCallable;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.tools.UsbPermissionObtainer;
import info.martinmarinov.usbxfer.AlternateUsbInterface;
import info.martinmarinov.usbxfer.ByteSource;
import info.martinmarinov.usbxfer.UsbBulkSource;
import info.martinmarinov.usbxfer.UsbHiSpeedBulk;
public abstract class DvbUsbDevice extends DvbDevice {
    private final static int RETRIES = 4;
    public interface Creator {
        /**
         * Try to instantiate a {@link DvbDevice} with the provided {@link UsbDevice} instance.
         * @param usbDevice a usb device that is attached to the system
         * @param context the application context, used for accessing usb system service and obtaining permissions
         * @param filter
         * @return a {@link DvbDevice} instance to control the device if the current creator supports it
         * or null if the {@link UsbDevice} is not supported by the creator.
         */
        DvbDevice create(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException;
        Set<DeviceFilter> getSupportedDevices();
    }
    private final static String TAG = DvbUsbDevice.class.getSimpleName();
    private final UsbDevice usbDevice;
    protected final Resources resources;
    private final Context context;
    private final DeviceFilter deviceFilter;
    public final boolean isRtlSdrBlogV4;
    protected DvbFrontend frontend;
    protected DvbTuner tuner;
    protected UsbDeviceConnection usbDeviceConnection;
    private AlternateUsbInterface usbInterface;
    private DvbCapabilities capabilities;
    protected DvbUsbDevice(UsbDevice usbDevice, Context context, DeviceFilter deviceFilter, DvbDemux dvbDemux) throws DvbException {
        super(dvbDemux);
        this.usbDevice = usbDevice;
        this.isRtlSdrBlogV4 = isRtlSdrBlogV4(usbDevice);
        this.context = context;
        this.resources = context.getResources();
        this.deviceFilter = deviceFilter;
        if (!UsbHiSpeedBulk.IS_PLATFORM_SUPPORTED) throw new DvbException(UNSUPPORTED_PLATFORM, resources.getString(R.string.unsuported_platform));
    }
    private static boolean isRtlSdrBlogV4(UsbDevice usbDevice) {
        return "RTLSDRBlog".equals(usbDevice.getManufacturerName()) && "Blog V4".equals(usbDevice.getProductName());
    }
    @Override
    public final void open() throws DvbException {
        try {
            usbDeviceConnection = UsbPermissionObtainer.obtainFdFor(context, usbDevice).get();
            if (usbDeviceConnection == null)
                throw new DvbException(USB_PERMISSION_DENIED, resources.getString(R.string.cannot_open_usb_connection));
            usbInterface = getUsbInterface();
            retry(RETRIES, new ThrowingRunnable<DvbException>() {
                @Override
                public void run() throws DvbException {
                    powerControl(true);
                    readConfig();
                    frontend = frontendAttatch();
                    frontend.attach();
                    // capabilities should only be accessed after frontend is attached
                    capabilities = frontend.getCapabilities();
                    tuner = tunerAttatch();
                    tuner.attatch();
                    frontend.init(tuner);
                    init();
                }
            });
        } catch (DvbException e) {
            throw e;
        } catch (Exception e) {
            throw new DvbException(BAD_API_USAGE, e);
        }
    }
    @Override
    public final void close() throws IOException {
        super.close();
        if (usbDeviceConnection != null) {
            if (frontend != null) frontend.release();
            if (tuner != null) tuner.release();
            try {
                powerControl(false);
            } catch (DvbException e) {
                e.printStackTrace();
            }
            usbDeviceConnection.close();
        }
        Log.d(TAG, "closed");
    }
    @Override
    public DeviceFilter getDeviceFilter() {
        return deviceFilter;
    }
    @Override
    public String toString() {
        return deviceFilter.getName();
    }
    @Override
    public void setPidFilter(int... pids) throws DvbException {
        super.setPidFilter(pids);
        frontend.setPids(pids);
    }
    @Override
    public void disablePidFilter() throws DvbException {
        super.disablePidFilter();
        frontend.disablePidFilter();
    }
    @Override
    public DvbCapabilities readCapabilities() throws DvbException {
        Check.notNull(capabilities, "Frontend not initialized");
        return capabilities;
    }
    @Override
    protected void tuneTo(final long freqHz, final long bandwidthHz, @NonNull final DeliverySystem deliverySystem) throws DvbException {
        Check.notNull(frontend, "Frontend not initialized");
        retry(RETRIES, new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                frontend.setParams(freqHz, bandwidthHz, deliverySystem);
            }
        });
    }
    @Override
    public int readSnr() throws DvbException {
        Check.notNull(frontend, "Frontend not initialized");
        return retry(RETRIES, new ThrowingCallable<Integer, DvbException>() {
            @Override
            public Integer call() throws DvbException {
                return frontend.readSnr();
            }
        });
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        Check.notNull(frontend, "Frontend not initialized");
        return retry(RETRIES, new ThrowingCallable<Integer, DvbException>() {
            @Override
            public Integer call() throws DvbException {
                return frontend.readRfStrengthPercentage();
            }
        });
    }
    @Override
    public int readBitErrorRate() throws DvbException {
        Check.notNull(frontend, "Frontend not initialized");
        return retry(RETRIES, new ThrowingCallable<Integer, DvbException>() {
            @Override
            public Integer call() throws DvbException {
                return frontend.readBer();
            }
        });
    }
    @Override
    public Set<DvbStatus> getStatus() throws DvbException {
        Check.notNull(frontend, "Frontend not initialized");
        return retry(RETRIES, new ThrowingCallable<Set<DvbStatus>, DvbException>() {
            @Override
            public Set<DvbStatus> call() throws DvbException {
                return frontend.getStatus();
            }
        });
    }
    protected int getNumRequests() {
        return 40;
    }
    protected int getNumPacketsPerRequest() {
        return 10;
    }
    @Override
    protected ByteSource createTsSource() {
        return new UsbBulkSource(usbDeviceConnection, getUsbEndpoint(), usbInterface, getNumRequests(), getNumPacketsPerRequest());
    }
    /** API for drivers to implement **/
    // Turn tuner on or off
    protected abstract void powerControl(boolean turnOn) throws DvbException;
    // Allows determining the tuner type so correct commands could be used later
    protected abstract void readConfig() throws DvbException;
    protected abstract DvbFrontend frontendAttatch() throws DvbException;
    protected abstract DvbTuner tunerAttatch() throws DvbException;
    protected abstract void init() throws DvbException;
    protected abstract AlternateUsbInterface getUsbInterface();
    protected abstract UsbEndpoint getUsbEndpoint();
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry;
import info.martinmarinov.dvbservice.dialogs.ListPickerFragmentDialog;
import info.martinmarinov.dvbservice.tools.StackTraceSerializer;
import info.martinmarinov.dvbservice.tools.TsDumpFileUtils;
import static info.martinmarinov.drivers.DvbException.ErrorCode.NO_DVB_DEVICES_FOUND;
public class DeviceChooserActivity extends FragmentActivity implements ListPickerFragmentDialog.OnSelected<DeviceFilter> {
    private final static int RESULT_ERROR = RESULT_FIRST_USER;
    private final static String CONTRACT_ERROR_CODE = "ErrorCode";
    private final static String CONTRACT_CONTROL_PORT = "ControlPort";
    private final static String CONTRACT_TRANSFER_PORT = "TransferPort";
    private final static String CONTRACT_DEVICE_NAME = "DeviceName";
    private final static String CONTRACT_RAW_TRACE = "RawTrace";
    private final static String CONTRACT_USB_PRODUCT_IDS = "ProductIds";
    private final static String CONTRACT_USB_VENDOR_IDS = "VendorIds";
    private final Intent response = new Intent();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.progress);
        // Set to receive info from the DvbService
        IntentFilter statusIntentFilter = new IntentFilter(DvbService.BROADCAST_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(new DvbServiceResponseReceiver(), statusIntentFilter);
    }
    @Override
    protected void onStart() {
        super.onStart();
        try {
            List<DvbDevice> dvbDevices = DvbUsbDeviceRegistry.getUsbDvbDevices(this);
            List<DvbDevice> dvbFileDevices = TsDumpFileUtils.getDevicesForAllRecordings(this); // these are only for debugging purposes
            dvbDevices.addAll(dvbFileDevices);
            if (dvbDevices.isEmpty()) throw new DvbException(NO_DVB_DEVICES_FOUND, getString(info.martinmarinov.drivers.R.string.no_devices_found));
            List<DeviceFilter> deviceFilters = new ArrayList<>(dvbDevices.size());
            for (DvbDevice dvbDevice : dvbDevices) {
                deviceFilters.add(dvbDevice.getDeviceFilter());
            }
            if (deviceFilters.size() > 1 || !dvbFileDevices.isEmpty()) {
                ListPickerFragmentDialog.showOneInstanceOnly(getSupportFragmentManager(), deviceFilters);
            } else {
                DvbService.requestOpen(this, deviceFilters.get(0));
            }
        } catch (DvbException e) {
            handleException(e);
        }
    }
    @Override
    public void onListPickerDialogItemSelected(@NonNull DeviceFilter deviceFilter) {
        DvbService.requestOpen(this, deviceFilter);
    }
    @Override
    public void onListPickerDialogCanceled() {
        finishWith(RESULT_CANCELED);
    }
    // Open selected device
    private class DvbServiceResponseReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            DvbService.StatusMessage statusMessage = DvbService.parseMessage(intent);
            if (statusMessage.exception != null) {
                Exception exception = statusMessage.exception;
                if (exception instanceof DvbException) {
                    handleException((DvbException) exception);
                } else if (exception instanceof IOException) {
                    handleException(new DvbException(DvbException.ErrorCode.IO_EXCEPTION, exception));
                } else if (exception instanceof RuntimeException) {
                    throw (RuntimeException) exception;
                } else {
                    throw new RuntimeException(exception);
                }
            } else {
                handleSuccess(statusMessage.deviceFilter, statusMessage.serverAddresses);
            }
        }
    }
    // API for returning response to caller
    private void handleSuccess(DeviceFilter deviceFilter, DvbServerPorts addresses) {
        int[] productIds = new int[] {deviceFilter.getProductId()};
        int[] vendorIds = new int[] {deviceFilter.getVendorId()};
        response.putExtra(CONTRACT_DEVICE_NAME, deviceFilter.getName());
        response.putExtra(CONTRACT_USB_PRODUCT_IDS, productIds);
        response.putExtra(CONTRACT_USB_VENDOR_IDS, vendorIds);
        response.putExtra(CONTRACT_CONTROL_PORT, addresses.getControlPort());
        response.putExtra(CONTRACT_TRANSFER_PORT, addresses.getTransferPort());
        finishWith(RESULT_OK);
    }
    private void handleException(DvbException e) {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Collection<UsbDevice> availableDevices = manager.getDeviceList().values();
        int[] productIds = new int[availableDevices.size()];
        int[] vendorIds = new int[availableDevices.size()];
        int id = 0;
        for (UsbDevice usbDevice : availableDevices) {
            productIds[id] = usbDevice.getProductId();
            vendorIds[id] = usbDevice.getVendorId();
            id++;
        }
        response.putExtra(CONTRACT_ERROR_CODE, e.getErrorCode().name());
        response.putExtra(CONTRACT_RAW_TRACE, StackTraceSerializer.serialize(e));
        response.putExtra(CONTRACT_USB_PRODUCT_IDS, productIds);
        response.putExtra(CONTRACT_USB_VENDOR_IDS, vendorIds);
        finishWith(RESULT_ERROR);
    }
    private void finishWith(int code) {
        if (getParent() == null) {
            setResult(code, response);
        } else {
            getParent().setResult(code, response);
        }
        finish();
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.drivers.usb.af9035;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import java.util.Set;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import info.martinmarinov.drivers.usb.DvbUsbIds;
import static info.martinmarinov.drivers.tools.SetUtils.setOf;
public class Af9035DvbDeviceCreator implements DvbUsbDevice.Creator {
    private final static Set<DeviceFilter> AF9035_DEVICES = setOf(
            /* AF9035 devices */
            new DeviceFilter(DvbUsbIds.USB_VID_AFATECH, DvbUsbIds.USB_PID_AFATECH_AF9035_9035, "Afatech AF9035 reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_AFATECH, DvbUsbIds.USB_PID_AFATECH_AF9035_1000, "Afatech AF9035 reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_AFATECH, DvbUsbIds.USB_PID_AFATECH_AF9035_1001, "Afatech AF9035 reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_AFATECH, DvbUsbIds.USB_PID_AFATECH_AF9035_1002, "Afatech AF9035 reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_AFATECH, DvbUsbIds.USB_PID_AFATECH_AF9035_1003, "Afatech AF9035 reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, DvbUsbIds.USB_PID_TERRATEC_CINERGY_T_STICK, "TerraTec Cinergy T Stick"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A835, "AVerMedia AVerTV Volar HD/PRO (A835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_B835, "AVerMedia AVerTV Volar HD/PRO (A835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_1867, "AVerMedia HD Volar (A867)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A867, "AVerMedia HD Volar (A867)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_TWINSTAR, "AVerMedia Twinstar (A825)"),
            new DeviceFilter(DvbUsbIds.USB_VID_ASUS, DvbUsbIds.USB_PID_ASUS_U3100MINI_PLUS, "Asus U3100Mini Plus"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, 0x00aa, "TerraTec Cinergy T Stick (rev. 2)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, 0x0337, "AVerMedia HD Volar (A867)"),
            new DeviceFilter(DvbUsbIds.USB_VID_GTEK, DvbUsbIds.USB_PID_EVOLVEO_XTRATV_STICK, "EVOLVEO XtraTV stick"),
	        /* IT9135 devices */
            new DeviceFilter(DvbUsbIds.USB_VID_ITETECH, DvbUsbIds.USB_PID_ITETECH_IT9135, "ITE 9135 Generic"),
            new DeviceFilter(DvbUsbIds.USB_VID_ITETECH, DvbUsbIds.USB_PID_ITETECH_IT9135_9005, "ITE 9135(9005) Generic"),
            new DeviceFilter(DvbUsbIds.USB_VID_ITETECH, DvbUsbIds.USB_PID_ITETECH_IT9135_9006, "ITE 9135(9006) Generic"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A835B_1835, "Avermedia A835B(1835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A835B_2835, "Avermedia A835B(2835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A835B_3835, "Avermedia A835B(3835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_A835B_4835, "Avermedia A835B(4835)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_TD110, "Avermedia AverTV Volar HD 2 (TD110)"),
            new DeviceFilter(DvbUsbIds.USB_VID_AVERMEDIA, DvbUsbIds.USB_PID_AVERMEDIA_H335, "Avermedia H335"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_KWORLD_UB499_2T_T09, "Kworld UB499-2T T09"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_SVEON_STV22_IT9137, "Sveon STV22 Dual DVB-T HDTV"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_CTVDIGDUAL_V2, "Digital Dual TV Receiver CTVDIGDUAL_V2"),
            /* XXX: that same ID [0ccd:0099] is used by af9015 driver too */
            // new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, 0x0099, "TerraTec Cinergy T Stick Dual RC (rev. 2)"), // This is not supported since there are two devices with same ids with only manufacturer being different meaning we can't use this due to auto start issues
            new DeviceFilter(DvbUsbIds.USB_VID_LEADTEK, 0x6a05, "Leadtek WinFast DTV Dongle Dual"),
            new DeviceFilter(DvbUsbIds.USB_VID_HAUPPAUGE, 0xf900, "Hauppauge WinTV-MiniStick 2"),
            new DeviceFilter(DvbUsbIds.USB_VID_PCTV, DvbUsbIds.USB_PID_PCTV_78E, "PCTV AndroiDTV (78e)"),
            new DeviceFilter(DvbUsbIds.USB_VID_PCTV, DvbUsbIds.USB_PID_PCTV_79E, "PCTV microStick (79e)")
            /* IT930x devices not supported by this driver */
    );
    @Override
    public Set<DeviceFilter> getSupportedDevices() {
        return AF9035_DEVICES;
    }
    @Override
    public DvbDevice create(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException {
        return new Af9035DvbDevice(usbDevice, context, filter);
    }
}
/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package info.martinmarinov.dvbservice;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
/**
 * The client sends a command consisting of a variable number of Longs in the following format:
 * <p>
 * byte 0 will be the Request.ordinal of the request
 * byte 1 will be N the number of longs in the payload
 * byte 2 to 8*N+1 will consist the actual values of the payload
 * <p>
 * After the request has been processed it returns a Response, which is in a similar format.
 * Please refer to the Response documentation for more info
 * <p>
 * Warning: For backwards compatibility order of the enum will be always preserved
 */
enum Request {
    REQ_PROTOCOL_VERSION(
            new Executor() {
                @Override
                public Response execute(DvbDevice dvbDevice, long... payload) throws DvbException {
                    // Clients can use it to determine whether new features
                    // are available.
                    // WARNING: Backward compatibility should always be ensured
                    return Response.success(
                            0L, // parameter 1, version, when adding capabilities, change that number.
                            ALL_REQUESTS.length // parameter 2, can be useful for determining supported commands
                    );
                }
            }
    ),
    REQ_EXIT(new Executor() {
        @Override
        public Response execute(DvbDevice dvbDevice, long... payload) {
            Log.d(TAG, "Client requested to close the connection");
            return Response.SUCCESS;
        }
    }),
    REQ_TUNE(new Executor() {
        @Override
        public Response execute(DvbDevice dvbDevice, long... payload) throws DvbException {
            long frequency = payload[0];            // frequency in herz
            long bandwidth = payload[1];            // bandwidth in herz.
            // Typical value for DVB-T is 8_000_000
            DeliverySystem deliverySystem = DeliverySystem.values()[(int) payload[2]];
            // Check enum for actual values
            Log.d(TAG, "Client requested tune to " + frequency + " Hz with bandwidth " + bandwidth + " Hz with delivery system " + deliverySystem);
            dvbDevice.tune(frequency, bandwidth, deliverySystem);
            return Response.SUCCESS;
        }
    }),
    REQ_GET_STATUS(new Executor() {
        @Override
        public Response execute(DvbDevice dvbDevice, long... ignored) throws DvbException {
            int snr = dvbDevice.readSnr();
            int bitErrorRate = dvbDevice.readBitErrorRate();
            int droppedUsbFps = dvbDevice.readDroppedUsbFps();
            int rfStrengthPercentage = dvbDevice.readRfStrengthPercentage();
            Set<DvbStatus> status = dvbDevice.getStatus();
            boolean hasSignal = status.contains(DvbStatus.FE_HAS_SIGNAL);
            boolean hasCarrier = status.contains(DvbStatus.FE_HAS_CARRIER);
            boolean hasSync = status.contains(DvbStatus.FE_HAS_SYNC);
            boolean hasLock = status.contains(DvbStatus.FE_HAS_LOCK);
            return Response.success(
                    (long) snr, // parameter 1
                    (long) bitErrorRate, // parameter 2
                    (long) droppedUsbFps, // parameter 3
                    (long) rfStrengthPercentage, // parameter 4
                    hasSignal ? 1L : 0L, // parameter 5
                    hasCarrier ? 1L : 0L, // parameter 6
                    hasSync ? 1L : 0L, // parameter 7
                    hasLock ? 1L : 0L // parameter 8
            );
        }
    }),
    REQ_SET_PIDS(new Executor() {
        @Override
        public Response execute(DvbDevice dvbDevice, long... payload) throws DvbException {
            int[] pids = new int[payload.length];
            for (int i = 0; i < payload.length; i++) pids[i] = (int) payload[i];
            dvbDevice.setPidFilter(pids);
            return Response.SUCCESS;
        }
    }),
    REQ_GET_CAPABILITIES(new Executor() {
        @Override
        public Response execute(DvbDevice dvbDevice, long... payload) throws DvbException {
            DvbCapabilities frontendProperties = dvbDevice.readCapabilities();
            // Only up to 62 deliverySystems are supported under current encoding method
            if (frontendProperties.getSupportedDeliverySystems().size() > 62)
                throw new IllegalStateException();
            long supportedDeliverySystems = 0;
            for (DeliverySystem deliverySystem : frontendProperties.getSupportedDeliverySystems()) {
                supportedDeliverySystems |= 1 << deliverySystem.ordinal();
            }
            return Response.success(
                    supportedDeliverySystems, // parameter 1
                    frontendProperties.getFrequencyMin(), // parameter 2
                    frontendProperties.getFrequencyMax(), // parameter 3
                    frontendProperties.getFrequencyStepSize(), // parameter 4
                    (long) dvbDevice.getDeviceFilter().getVendorId(), // parameter 5
                    (long) dvbDevice.getDeviceFilter().getProductId() // parameter 6
            );
        }
    });
    private final static String TAG = Request.class.getSimpleName();
    private final static Request[] ALL_REQUESTS = values();
    private final Executor executor;
    Request(Executor executor) {
        this.executor = executor;
    }
    private Response execute(DvbDevice dvbDevice, long... payload) {
        try {
            return executor.execute(dvbDevice, payload);
        } catch (Exception e) {
            e.printStackTrace();
            return Response.ERROR;
        }
    }
    private static void readBytes(InputStream inputStream, byte[] read_buffer) throws IOException {
        int bytesRead = 0;
        while (bytesRead < read_buffer.length) {
            int count = inputStream.read(read_buffer, bytesRead, read_buffer.length - bytesRead);
            if (count < 0) {
                throw new SocketException("End of stream reached before reading required number of bytes");
            }
            bytesRead += count;
        }
    }
    private static long readLongAtByte(byte[] read_buffer, int offset) {
        return (((long) read_buffer[offset] << 56) +
                ((long) (read_buffer[offset + 1] & 255) << 48) +
                ((long) (read_buffer[offset + 2] & 255) << 40) +
                ((long) (read_buffer[offset + 3] & 255) << 32) +
                ((long) (read_buffer[offset + 4] & 255) << 24) +
                ((read_buffer[offset + 5] & 255) << 16) +
                ((read_buffer[offset + 6] & 255) << 8) +
                ((read_buffer[offset + 7] & 255)));
    }
    private static long[] parsePayload(InputStream inputStream, int size) throws IOException {
        byte[] payload_buffer = new byte[size * 8];
        readBytes(inputStream, payload_buffer);
        long[] payload = new long[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = readLongAtByte(payload_buffer, i * 8);
        }
        return payload;
    }
    public static Request parseAndExecute(InputStream inputStream, OutputStream outputStream, DvbDevice dvbDevice) throws IOException {
        Request req = null;
        byte[] head_buffer = new byte[2];
        readBytes(inputStream, head_buffer);
        int ordinal = head_buffer[0] & 0xFF;
        int size = head_buffer[1] & 0xFF;
        long[] payload = parsePayload(inputStream, size);
        if (ordinal < ALL_REQUESTS.length) {
            req = ALL_REQUESTS[ordinal];
            // This command is recognized, execute and get response
            Response r = req.execute(dvbDevice, payload);
            // Send response back over the wire
            r.serialize(req, outputStream);
        }
        return req;
    }
    private interface Executor {
        Response execute(DvbDevice dvbDevice, long... payload) throws DvbException;
    }
}