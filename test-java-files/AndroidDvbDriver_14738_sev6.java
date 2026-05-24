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
package info.martinmarinov.drivers.usb.cxusb;
import static android.hardware.usb.UsbConstants.USB_DIR_IN;
import static android.hardware.usb.UsbConstants.USB_DIR_OUT;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.usb.DvbUsbIds.USB_VID_MEDION;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.usb.generic.AbstractGenericDvbUsbDevice;
import info.martinmarinov.usbxfer.AlternateUsbInterface;
public abstract class CxUsbDvbDevice extends AbstractGenericDvbUsbDevice {
    private final static String TAG = CxUsbDvbDevice.class.getSimpleName();
    private final static byte CMD_I2C_WRITE = 0x08;
    private final static byte CMD_I2C_READ  = 0x09;
    private final static byte CMD_GPIO_WRITE = 0x0e;
    private final static byte CMD_GPIO_READ  = 0x0d;
    private final static byte GPIO_TUNER     = 0x02;
    private final static byte CMD_POWER_OFF = (byte) 0xdc;
    private final static byte CMD_POWER_ON  = (byte) 0xde;
    private final static byte CMD_STREAMING_ON  = 0x36;
    private final static byte CMD_STREAMING_OFF = 0x37;
    private final static byte CMD_AVER_STREAM_ON  = 0x18;
    private final static byte CMD_AVER_STREAM_OFF = 0x19;
    private final static byte CMD_GET_IR_CODE = 0x47;
    public final static byte CMD_ANALOG  = 0x50;
    public final static byte CMD_DIGITAL = 0x51;
    public static final int SI2168_TS_PARALLEL = 0x06;
    public static final int SI2168_TS_SERIAL   = 0x03;
    public static final int SI2168_MP_NOT_USED = 1;
    public static final int SI2168_MP_A = 2;
    public static final int  SI2168_MP_B = 3;
    public static final int SI2168_MP_C = 4;
    public static final int SI2168_MP_D = 5;
    public final static int SI2168_TS_CLK_AUTO_FIXED =	0;
    public final static int SI2168_TS_CLK_AUTO_ADAPT =	1;
    public final static int SI2168_TS_CLK_MANUAL	  =	2;
    public final static int SI2168_ARGLEN = 30;
    /* Max transfer size done by I2C transfer functions */
    private final static int MAX_XFER_SIZE = 80;
    private boolean gpio_tuner_write_state = false;
    private final UsbInterface iface;
    private final UsbEndpoint endpoint;
    final I2cAdapter i2CAdapter = new CxUsbDvbDeviceI2cAdapter();
    CxUsbDvbDevice(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException {
        super(
                usbDevice,
                context,
                filter,
                DvbDemux.DvbDmxSwfilter(),
                usbDevice.getInterface(0).getEndpoint(0),
                usbDevice.getInterface(0).getEndpoint(1)
        );
        iface = usbDevice.getInterface(0);
        endpoint = iface.getEndpoint(2);
        if (controlEndpointIn.getAddress() != 0x81 || controlEndpointIn.getDirection() != USB_DIR_IN) throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
        if (controlEndpointOut.getAddress() != 0x01 || controlEndpointOut.getDirection() != USB_DIR_OUT) throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
        if (endpoint.getAddress() != 0x82 || endpoint.getDirection() != USB_DIR_IN) throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
    }
    @Override
    protected AlternateUsbInterface getUsbInterface() {
        return AlternateUsbInterface.forUsbInterface(usbDeviceConnection, iface).get(0);
    }
    @Override
    protected UsbEndpoint getUsbEndpoint() {
        return endpoint;
    }
    public void cxusb_streaming_ctrl(boolean onoff) throws DvbException {
        byte[] buf = new byte[]{ 0x03, 0x00 };
        if (onoff) {
            cxusb_ctrl_msg(CMD_STREAMING_ON, buf, 2);
        } else {
            cxusb_ctrl_msg(CMD_STREAMING_OFF, new byte[0], 0);
        }
    }
    void cxusb_power_ctrl(boolean onoff) throws DvbException {
        if (onoff) {
            cxusb_ctrl_msg(CMD_POWER_ON, new byte[1], 1);
        } else {
            cxusb_ctrl_msg(CMD_POWER_OFF, new byte[1], 1);
        }
    }
    @SuppressWarnings("WeakerAccess")
    synchronized void cxusb_ctrl_msg(byte cmd, @NonNull byte[] wbuf, int wlen) throws DvbException {
        cxusb_ctrl_msg(cmd, wbuf, wlen, null, Integer.MIN_VALUE);
    }
    synchronized void cxusb_ctrl_msg(byte cmd, @NonNull byte[] wbuf, int wlen, @Nullable byte[] rbuf, int rlen) throws DvbException {
        if (1 + wlen > MAX_XFER_SIZE) {
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage) + "\n" + "i2c wr: len=" + wlen + " is too big!");
        }
        if (rlen > MAX_XFER_SIZE) {
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage) + "\n" + "i2c rd: len=" + rlen + " is too big!");
        }
        byte[] data = new byte[Math.max(wlen + 1, rlen)];
        data[0] = cmd;
        System.arraycopy(wbuf, 0, data, 1, wlen);
        dvb_usb_generic_rw(data, 1 + wlen, data, rlen);
        if (rbuf != null) {
            System.arraycopy(data, 0, rbuf, 0, rlen);
        }
    }
    private void cxusb_gpio_tuner(boolean onoff) throws DvbException {
        if (gpio_tuner_write_state == onoff) {
            return;
        }
        byte[] o = new byte[] {GPIO_TUNER, (onoff ? (byte) 1 : 0)};
        byte[] i = new byte[1];
        cxusb_ctrl_msg(CMD_GPIO_WRITE, o, 2, i, 1);
        if (i[0] != 0x01) {
            Log.w(TAG, "gpio_write failed.");
        }
        gpio_tuner_write_state = onoff;
    }
    private class CxUsbDvbDeviceI2cAdapter extends I2cAdapter {
        @Override
        protected int masterXfer(I2cMessage[] msg) throws DvbException {
            for (int i = 0; i < msg.length; i++) {
                if (getDeviceFilter().getVendorId() == USB_VID_MEDION) {
                    switch (msg[i].addr) {
                        case 0x63:
                            cxusb_gpio_tuner(false);
                            break;
                        default:
                            cxusb_gpio_tuner(true);
                            break;
                    }
                }
                if ((msg[i].flags & I2C_M_RD) != 0) {
			        /* read only */
                    byte[] obuf = new byte[3];
                    byte[] ibuf = new byte[MAX_XFER_SIZE];
                    if (1 + msg[i].len > ibuf.length) {
                        throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
                    }
                    obuf[0] = 0;
                    obuf[1] = (byte) msg[i].len;
                    obuf[2] = (byte) msg[i].addr;
                    cxusb_ctrl_msg(CMD_I2C_READ, obuf, 3, ibuf, 1+msg[i].len);
                    System.arraycopy(ibuf, 1, msg[i].buf, 0,  msg[i].len);
                } else if (i+1 < msg.length && ((msg[i+1].flags & I2C_M_RD) != 0) && msg[i].addr == msg[i+1].addr) {
                    /* write to then read from same address */
                    byte[] obuf = new byte[MAX_XFER_SIZE];
                    byte[] ibuf = new byte[MAX_XFER_SIZE];
                    if (3 + msg[i].len > obuf.length) {
                        throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
                    }
                    if (1 + msg[i + 1].len > ibuf.length) {
                        throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
                    }
                    obuf[0] = (byte) msg[i].len;
                    obuf[1] = (byte) msg[i+1].len;
                    obuf[2] = (byte) msg[i].addr;
                    System.arraycopy(msg[i].buf, 0, obuf, 3, msg[i].len);
                    cxusb_ctrl_msg(CMD_I2C_READ, obuf, 3+msg[i].len, ibuf, 1+msg[i+1].len);
//                    if (ibuf[0] != 0x08) {
//                        Log.w(TAG, "i2c read may have failed");
//                    }
                    System.arraycopy(ibuf, 1, msg[i+1].buf, 0, msg[i+1].len);
                    i++;
                } else {
			        /* write only */
                    byte[] obuf = new byte[MAX_XFER_SIZE];
                    byte[] ibuf = new byte[1];
                    if (2 + msg[i].len > obuf.length) {
                        throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
                    }
                    obuf[0] = (byte) msg[i].addr;
                    obuf[1] = (byte) msg[i].len;
                    System.arraycopy(msg[i].buf, 0, obuf, 2, msg[i].len);
                    cxusb_ctrl_msg(CMD_I2C_WRITE, obuf, 2+msg[i].len, ibuf, 1);
//                    if (ibuf[0] != 0x08){
//                        Log.w(TAG, "i2c read may have failed");
//                    }
                }
            }
            return msg.length;
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
package info.martinmarinov.drivers.usb.silabs;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.IO_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_CARRIER;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_LOCK;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SIGNAL;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SYNC;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_VITERBI;
import static info.martinmarinov.drivers.usb.cxusb.CxUsbDvbDevice.SI2168_ARGLEN;
import android.content.res.Resources;
import android.util.Log;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import info.martinmarinov.drivers.usb.cxusb.CxUsbDvbDevice;
public class Si2168 implements DvbFrontend {
    private final static String TAG = Si2168.class.getSimpleName();
    private final static byte[] EMPTY = new byte[0];
    private final static int TIMEOUT_MS = 70;
    private final static int NO_STREAM_ID_FILTER = 0xFFFFFFFF;
    private final static int DVBT2_STREAM_ID = 0;
    private final static int DVBC_SYMBOL_RATE = 0;
    private final Resources resources;
    private final I2cAdapter i2c;
    private final int addr;
    private final int ts_mode;
    private final int ts_clock_mode;
    private final boolean ts_clock_inv;
    private final boolean ts_clock_gapped;
    private DvbUsbDevice usbDevice;
    private int version;
    private boolean warm = false;
    private boolean active = false;
    private Si2168Data.Si2168Chip chip;
    private DvbTuner tuner;
    private DeliverySystem deliverySystem;
    private boolean hasLockStatus = false;
    public Si2168(DvbUsbDevice usbDevice, Resources resources, I2cAdapter i2c, int addr, int ts_mode, boolean ts_clock_inv, int ts_clock_mode, boolean ts_clock_gapped) {
        this.usbDevice = usbDevice;
        this.resources = resources;
        this.i2c = i2c;
        this.addr = addr;
        this.ts_mode = ts_mode;
        this.ts_clock_inv = ts_clock_inv;
        this.ts_clock_mode = ts_clock_mode;
        this.ts_clock_gapped = ts_clock_gapped;
    }
    private void si2168_cmd_execute_wr(byte[] wargs, int wlen) throws DvbException {
        si2168_cmd_execute(wargs, wlen, 0);
    }
    private synchronized @NonNull byte[] si2168_cmd_execute(@NonNull byte[] wargs, int wlen, int rlen) throws DvbException {
        if (wlen > 0 && wlen == wargs.length) {
            i2c.send(addr, wargs, wlen);
        } else {
            if (wlen != 0) throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        }
        if (rlen > 0) {
            byte[] rout = new byte[rlen];
            long endTime = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
            while (System.nanoTime() < endTime) {
                i2c.recv(addr, rout, rlen);
                if ((((rout[0] & 0xFF) >> 7) & 0x01) != 0) {
                    break;
                }
            }
            if ((((rout[0] & 0xFF) >> 6) & 0x01) != 0) {
                throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.failed_to_read_from_register));
            }
            if ((((rout[0] & 0xFF) >> 7) & 0x01) == 0) {
                throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.timed_out_read_from_register));
            }
            return rout;
        }
        return EMPTY;
    }
    @Override
    public synchronized DvbCapabilities getCapabilities() {
        return Si2168Data.CAPABILITIES;
    }
    @Override
    public synchronized void attach() throws DvbException {
        /* Initialize */
        si2168_cmd_execute_wr(
                new byte[] {(byte) 0xc0, (byte) 0x12, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x0d, (byte) 0x16, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00},
                13
        );
        /* Power up */
        si2168_cmd_execute(
                new byte[] {(byte) 0xc0, (byte) 0x06, (byte) 0x01, (byte) 0x0f, (byte) 0x00, (byte) 0x20, (byte) 0x20, (byte) 0x01},
                8, 1
        );
        /* Query chip revision */
        byte[] chipInfo = si2168_cmd_execute(new byte[] {0x02}, 1, 13);
        int chipId = ((chipInfo[1] & 0xFF) << 24) | ((chipInfo[2] & 0xFF) << 16) | ((chipInfo[3] & 0xFF) << 8) | (chipInfo[4] & 0xFF);
        chip = Si2168Data.Si2168Chip.fromId(chipId);
        if (chip == null) {
            Log.w(TAG, String.format("unknown chip version Si21%d-%c%c%c", chipInfo[2] & 0xFF, chipInfo[1] & 0xFF, chipInfo[3] & 0xFF, chipInfo[4] & 0xFF));
            throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
        }
        version = ((chipInfo[1] & 0xFF) << 24) | (((chipInfo[3] & 0xFF) - '0') << 16) | (((chipInfo[4] & 0xFF) - '0') << 8) | (chipInfo[5] & 0xFF);
        Log.d(TAG, "Chip " + chip + " successfully identified");
    }
    @Override
    public synchronized void release() {
        active = false;
        /* Firmware B 4.0-11 or later loses warm state during sleep */
        if (version >= ('B' << 24 | 4 << 16 | 11)) {
            warm = false;
        }
        try {
            si2168_cmd_execute_wr(new byte[]{(byte) 0x13}, 1);
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        /* initialize */
        si2168_cmd_execute_wr(new byte[] {(byte) 0xc0, (byte) 0x12, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x0d, (byte) 0x16, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00}, 13);
        if (warm) {
		    /* resume */
            si2168_cmd_execute(new byte[] {(byte) 0xc0, (byte) 0x06, (byte) 0x08, (byte) 0x0f, (byte) 0x00, (byte) 0x20, (byte) 0x21, (byte) 0x01}, 8, 1);
            si2168_cmd_execute(new byte[] {(byte) 0x85}, 1, 1);
        } else {
	        /* power up */
            si2168_cmd_execute(new byte[] {(byte) 0xc0, (byte) 0x06, (byte) 0x01, (byte) 0x0f, (byte) 0x00, (byte) 0x20, (byte) 0x20, (byte) 0x01}, 8, 1);
            Log.d(TAG, "Uploading firmware to "+chip);
            try {
                byte[] fw = readFirmware(chip.firmwareFile);
                if ((fw.length % 17 == 0) && ((fw[0] & 0xFF) > 5)) {
                    Log.d(TAG, "firmware is in the new format");
                    for (int remaining = fw.length; remaining > 0; remaining -= 17) {
                        int len = fw[fw.length - remaining] & 0xFF;
                        if (len > SI2168_ARGLEN) {
                            throw new DvbException(IO_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
                        }
                        byte[] args = new byte[len];
                        System.arraycopy(fw, fw.length - remaining + 1, args, 0, len);
                        si2168_cmd_execute(args, len, 1);
                    }
                } else if (fw.length % 8 == 0) {
                    Log.d(TAG, "firmware is in the old format");
                    for (int remaining = fw.length; remaining > 0; remaining -= 8) {
                        int len = 8;
                        byte[] args = new byte[len];
                        System.arraycopy(fw, fw.length - remaining, args, 0, len);
                        si2168_cmd_execute(args, len, 1);
                    }
                } else {
                    throw new DvbException(IO_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
                }
            } catch (IOException e) {
                throw new DvbException(IO_EXCEPTION, e);
            }
            si2168_cmd_execute(new byte[] {0x01, 0x01}, 2, 1);
        	/* query firmware version */
            byte[] fwVerRaw = si2168_cmd_execute(new byte[] {(byte) 0x11}, 1, 10);
            version = (((fwVerRaw[9] & 0xFF) + '@') << 24) | (((fwVerRaw[6] & 0xFF) - '0') << 16) | (((fwVerRaw[7] & 0xFF) - '0') << 8) | (fwVerRaw[8] & 0xFF);
            Log.d(TAG, "firmware version: "+((char) ((version >> 24) & 0xff))+" "+((version >> 16) & 0xff)+"."+((version >> 8) & 0xff)+"."+(version & 0xff));
        	/* set ts mode */
            byte[] args = new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x01, (byte) 0x10, (byte) 0x00, (byte) 0x00};
            args[4] |= ts_mode;
            args[4] |= ts_clock_mode << 4;
            if (ts_clock_gapped) {
                args[4] |= 0x40;
            }
            si2168_cmd_execute(args, 6, 4);
            /* set ts freq to 10Mhz*/
            si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x0d, (byte) 0x10, (byte) 0xe8, (byte) 0x03}, 6, 4);
            warm = true;
        }
        tuner.init();
        active = true;
    }
    private byte[] readFirmware(int resource) throws IOException {
        InputStream inputStream = resources.openRawResource(resource);
        //noinspection TryFinallyCanBeTryWithResources
        try {
            byte[] fw = new byte[inputStream.available()];
            if (inputStream.read(fw) != fw.length) {
                throw new DvbException(IO_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
            }
            return fw;
        } finally {
            inputStream.close();
        }
    }
    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        hasLockStatus = false;
        if (!active) {
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        }
        int delivery_system;
        switch (deliverySystem) {
            case DVBT:
                delivery_system = 0x20;
                break;
            case DVBC:
                delivery_system = 0x30;
                break;
            case DVBT2:
                delivery_system = 0x70;
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        int bandwidth;
        if (bandwidthHz == 0) {
            throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        } else if (bandwidthHz <= 2_000_000L) {
            bandwidth = 0x02;
        } else if (bandwidthHz <= 5_000_000L) {
            bandwidth = 0x05;
        } else if (bandwidthHz <= 6_000_000L) {
            bandwidth = 0x06;
        } else if (bandwidthHz <= 7_000_000L) {
            bandwidth = 0x07;
        } else if (bandwidthHz <= 8_000_000L) {
            bandwidth = 0x08;
        } else if (bandwidthHz <= 9_000_000L) {
            bandwidth = 0x09;
        } else if (bandwidthHz <= 10_000_000L) {
            bandwidth = 0x0a;
        } else {
            bandwidth = 0x0f;
        }
        /* program tuner */
        tuner.setParams(frequency, bandwidthHz, deliverySystem);
        si2168_cmd_execute(new byte[] {(byte) 0x88, (byte) 0x02, (byte) 0x02, (byte) 0x02, (byte) 0x02}, 5, 5);
        /* that has no big effect */
        switch (deliverySystem) {
            case DVBT:
                si2168_cmd_execute(new byte[] {(byte) 0x89, (byte) 0x21, (byte) 0x06, (byte) 0x11, (byte) 0xff, (byte) 0x98}, 6, 3);
                break;
            case DVBC:
                si2168_cmd_execute(new byte[] {(byte) 0x89, (byte) 0x21, (byte) 0x06, (byte) 0x11, (byte) 0x89, (byte) 0xf0}, 6, 3);
                break;
            case DVBT2:
                si2168_cmd_execute(new byte[] {(byte) 0x89, (byte) 0x21, (byte) 0x06, (byte) 0x11, (byte) 0x89, (byte) 0x20}, 6, 3);
                break;
        }
        if (deliverySystem == DeliverySystem.DVBT2) {
            /* select PLP */
            //noinspection PointlessBitwiseExpression,ConstantConditions
            si2168_cmd_execute(new byte[] {
                    (byte) 0x52, (byte) (DVBT2_STREAM_ID & 0xFF), DVBT2_STREAM_ID == NO_STREAM_ID_FILTER ? 0 : (byte) 1
            }, 3, 1);
        }
        si2168_cmd_execute(new byte[] {(byte) 0x51, (byte) 0x03}, 2, 12);
        si2168_cmd_execute(new byte[] {(byte) 0x12, (byte) 0x08, (byte) 0x04}, 3, 3);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x0c, (byte) 0x10, (byte) 0x12, (byte) 0x00}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x06, (byte) 0x10, (byte) 0x24, (byte) 0x00}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x07, (byte) 0x10, (byte) 0x00, (byte) 0x24}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x0a, (byte) 0x10, (byte) (delivery_system | bandwidth), (byte) 0x00}, 6, 4);
        /* set DVB-C symbol rate */
        if (deliverySystem == DeliverySystem.DVBC) {
            //noinspection PointlessBitwiseExpression
            si2168_cmd_execute(new byte[] {
                    (byte) 0x14, (byte) 0x00, (byte) 0x02 , (byte) 0x11, (byte) (((DVBC_SYMBOL_RATE / 1000) >> 0) & 0xff), (byte) ( ((DVBC_SYMBOL_RATE / 1000) >> 8) & 0xff)
            }, 6, 4);
        }
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x0f, (byte) 0x10, (byte) 0x10, (byte) 0x00}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x09, (byte) 0x10, (byte) 0xe3, (byte) (0x08 | (ts_clock_inv ? 0x00 : 0x10))}, 6, 4);
        byte[] cmd = new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x08, (byte) 0x10, (byte) 0xd7, (byte) 0x05};
        cmd[5] |= ts_clock_inv ? 0xd7 : 0xcf;
        si2168_cmd_execute(cmd, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x01, (byte) 0x12, (byte) 0x00, (byte) 0x00}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x14, (byte) 0x00, (byte) 0x01, (byte) 0x03, (byte) 0x0c, (byte) 0x00}, 6, 4);
        si2168_cmd_execute(new byte[] {(byte) 0x85}, 1, 1);
        this.deliverySystem = deliverySystem;
    }
    @Override
    public int readSnr() throws DvbException {
        return -1;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        if (!getStatus().contains(FE_HAS_SIGNAL)) return 0;
        return tuner.readRfStrengthPercentage();
    }
    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        if (!active) {
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        }
        if (deliverySystem == null) return SetUtils.setOf();
        byte[] res;
        switch (deliverySystem) {
            case DVBT:
                res = si2168_cmd_execute(new byte[] {(byte) 0xa0, (byte) 0x01}, 2, 13);
                break;
            case DVBC:
                res = si2168_cmd_execute(new byte[] {(byte) 0x90, (byte) 0x01}, 2, 9);
                break;
            case DVBT2:
                res = si2168_cmd_execute(new byte[] {(byte) 0x50, (byte) 0x01}, 2, 14);
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        Set<DvbStatus> resultStatus;
        switch (((res[2] & 0xFF) >> 1) & 0x03) {
            case 0x01:
                resultStatus = SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER);
                break;
            case 0x03:
                resultStatus = SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER, FE_HAS_VITERBI, FE_HAS_SYNC, FE_HAS_LOCK);
                break;
            default:
                return SetUtils.setOf();
        }
        /* hook fe: need to resync the slave fifo when signal locks. */
        /* it need resync slave fifo when signal change from unlock to lock.*/
        if (!hasLockStatus && resultStatus.contains(FE_HAS_LOCK)) {
            ((CxUsbDvbDevice)usbDevice).cxusb_streaming_ctrl(true);
            hasLockStatus = true;
        }
        return resultStatus;
    }
    @Override
    public synchronized int readBer() throws DvbException {
        if (!getStatus().contains(FE_HAS_VITERBI)) return 0xFFFF;
        byte[] res = si2168_cmd_execute(new byte[] { (byte) 0x82, (byte) 0x00 }, 2, 3);
        /*
		 * Firmware returns [0, 255] mantissa and [0, 8] exponent.
		 * Convert to DVB API: mantissa * 10^(8 - exponent) / 10^8
		 */
        int diff = 8 - (res[1] & 0xFF);
        int utmp = diff < 0 ? 0 : (diff > 8 ? 8 : diff);
        long bitErrors = 1;
        for (int i = 0; i < utmp; i++) {
            bitErrors = bitErrors * 10;
        }
        bitErrors = (res[2] & 0xFF) * bitErrors;
        long bitCount = 10_000_000L; /* 10^8 */
        return (int) ((bitErrors * 0xFFFF) / bitCount);
    }
    @Override
    public void setPids(int... pids) throws DvbException {
        // no-op
    }
    @Override
    public void disablePidFilter() throws DvbException {
        // no-op
    }
    public I2cAdapter.I2GateControl gateControl() {
        return gateControl;
    }
    private final I2cAdapter.I2GateControl gateControl = new I2cAdapter.I2GateControl() {
        @Override
        protected synchronized  void i2cGateCtrl(boolean enable) throws DvbException {
            if (enable) {
                si2168_cmd_execute_wr(new byte[] {(byte) 0xc0, (byte) 0x0d, (byte) 0x01}, 3);
            } else {
                si2168_cmd_execute_wr(new byte[] {(byte) 0xc0, (byte) 0x0d, (byte) 0x00}, 3);
            }
        }
    };
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
import androidx.annotation.NonNull;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
public interface DvbFrontend {
    // TODO these capabilities contain frequency min and max which is actually determined by tuner
    DvbCapabilities getCapabilities();
    void attach() throws DvbException;
    void release(); // release should always succeed or fail catastrophically
    // don't forget to call tuner.init() from here!
    void init(DvbTuner tuner) throws DvbException;
    void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException;
    int readSnr() throws DvbException;
    int readRfStrengthPercentage() throws DvbException;
    int readBer() throws DvbException;
    Set<DvbStatus> getStatus() throws DvbException;
    void setPids(int ... pids) throws DvbException;
    void disablePidFilter() throws DvbException;
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
package info.martinmarinov.drivers.usb.generic;
import static android.hardware.usb.UsbConstants.USB_DIR_OUT;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.locks.ReentrantLock;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
public abstract class AbstractGenericDvbUsbDevice extends DvbUsbDevice {
    private final static int DEFAULT_USB_COMM_TIMEOUT_MS = 100;
    private final static long DEFAULT_READ_OR_WRITE_TIMEOUT_MS = 700L;
    private final ReentrantLock usbReentrantLock = new ReentrantLock();
    protected final UsbEndpoint controlEndpointIn;
    protected final UsbEndpoint controlEndpointOut;
    protected AbstractGenericDvbUsbDevice(
            UsbDevice usbDevice,
            Context context,
            DeviceFilter deviceFilter,
            DvbDemux dvbDemux,
            UsbEndpoint controlEndpointIn,
            UsbEndpoint controlEndpointOut
    ) throws DvbException {
        super(usbDevice, context, deviceFilter, dvbDemux);
        this.controlEndpointIn = controlEndpointIn;
        this.controlEndpointOut = controlEndpointOut;
    }
    private int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return usbDeviceConnection.bulkTransfer(endpoint, buffer, offset, length, DEFAULT_USB_COMM_TIMEOUT_MS);
        } else if (offset == 0) {
            return usbDeviceConnection.bulkTransfer(endpoint, buffer, length, DEFAULT_USB_COMM_TIMEOUT_MS);
        } else {
            byte[] tempbuff = new byte[length - offset];
            if (endpoint.getDirection() == USB_DIR_OUT) {
                System.arraycopy(buffer, offset, tempbuff, 0, length - offset);
                return usbDeviceConnection.bulkTransfer(endpoint, tempbuff, tempbuff.length, DEFAULT_USB_COMM_TIMEOUT_MS);
            } else {
                int read = usbDeviceConnection.bulkTransfer(endpoint, tempbuff, tempbuff.length, DEFAULT_USB_COMM_TIMEOUT_MS);
                if (read <= 0) {
                    return read;
                }
                System.arraycopy(tempbuff, 0, buffer, offset, read);
                return read;
            }
        }
    }
    protected void dvb_usb_generic_rw(@NonNull byte[] wbuf, int wlen, @Nullable byte[] rbuf, int rlen) throws DvbException {
        long startTime = System.currentTimeMillis();
        usbReentrantLock.lock();
        try {
            int bytesTransferred = 0;
            while (bytesTransferred < wlen) {
                int actlen = bulkTransfer(controlEndpointOut, wbuf, bytesTransferred, wlen - bytesTransferred);
                if (System.currentTimeMillis() - startTime > DEFAULT_READ_OR_WRITE_TIMEOUT_MS) {
                    actlen = -99999999;
                }
                if (actlen < 0) {
                    throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_send_control_message, actlen));
                }
                bytesTransferred += actlen;
            }
            if (rbuf != null && rlen >= 0) {
                bytesTransferred = 0;
                while (bytesTransferred < rlen) {
                    int actlen = bulkTransfer(controlEndpointIn, rbuf, bytesTransferred, rlen - bytesTransferred);
                    if (System.currentTimeMillis() - startTime > 2*DEFAULT_READ_OR_WRITE_TIMEOUT_MS) {
                        actlen = -99999999;
                    }
                    if (actlen < 0) {
                        throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_send_control_message, actlen));
                    }
                    bytesTransferred += actlen;
                }
            }
        } finally {
            usbReentrantLock.unlock();
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
package info.martinmarinov.drivers.usb;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
public interface DvbTuner {
    void attatch() throws DvbException;
    void release(); // release should always succeed or fail catastrophically
    void init() throws DvbException;
    void setParams(long frequency, long bandwidthHz, DeliverySystem deliverySystem) throws DvbException;
    long getIfFrequency() throws DvbException;
    int readRfStrengthPercentage() throws DvbException;
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
package info.martinmarinov.drivers.usb.rtl28xx;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_DEMOD_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_I2C_DA_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_I2C_DA_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_I2C_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_I2C_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_IR_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_IR_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_SYS_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_SYS_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_USB_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_USB_WR;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.CMD_WR_FLAG;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.SYS_GPIO_OUT_VAL;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.USB_EPA_FIFO_CFG;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.USB_EPA_MAXPKT;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.USB_SYSCTL_0;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Build;
import java.util.concurrent.locks.ReentrantLock;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import info.martinmarinov.usbxfer.AlternateUsbInterface;
abstract class Rtl28xxDvbDevice extends DvbUsbDevice {
    private final static int DEFAULT_USB_COMM_TIMEOUT_MS = 100;
    private final static long DEFAULT_READ_OR_WRITE_TIMEOUT_MS = 1000L;
    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final UsbInterface iface;
    private final UsbEndpoint endpoint;
    final Rtl28xxI2cAdapter i2CAdapter = new Rtl28xxI2cAdapter();
    final TunerCallbackBuilder tunerCallbackBuilder = new TunerCallbackBuilder();
    Rtl28xxDvbDevice(UsbDevice usbDevice, Context context, DeviceFilter deviceFilter) throws DvbException {
        super(usbDevice, context, deviceFilter, DvbDemux.DvbDmxSwfilter());
        iface = usbDevice.getInterface(0);
        endpoint = iface.getEndpoint(0);
        if (endpoint.getAddress() != 0x81) throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
    }
    private int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int offset, int length) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return usbDeviceConnection.controlTransfer(requestType, request, value, index, buffer, offset, buffer.length, DEFAULT_USB_COMM_TIMEOUT_MS);
        } else if (offset == 0) {
            return usbDeviceConnection.controlTransfer(requestType, request, value, index, buffer, buffer.length, DEFAULT_USB_COMM_TIMEOUT_MS);
        } else {
            byte[] tempbuff = new byte[length - offset];
            if ((requestType & UsbConstants.USB_DIR_IN) == 0) {
                System.arraycopy(buffer, offset, tempbuff, 0, length - offset);
                return usbDeviceConnection.controlTransfer(requestType, request, value, index, tempbuff, tempbuff.length, DEFAULT_USB_COMM_TIMEOUT_MS);
            } else {
                int read = usbDeviceConnection.controlTransfer(requestType, request, value, index, tempbuff, tempbuff.length, DEFAULT_USB_COMM_TIMEOUT_MS);
                if (read <= 0) {
                    return read;
                }
                System.arraycopy(tempbuff, 0, buffer, offset, read);
                return read;
            }
        }
    }
    synchronized void ctrlMsg(int value, int index, byte[] data) throws DvbException {
        ctrlMsg(value, index, data, data.length);
    }
    synchronized void ctrlMsg(int value, int index, byte[] data, int length) throws DvbException {
        long startTime = System.currentTimeMillis();
        int requestType;
        if ((index & CMD_WR_FLAG) != 0) {
            // write
            requestType = UsbConstants.USB_TYPE_VENDOR;
        } else {
            // read
            requestType = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;
        }
        reentrantLock.lock();
        try {
            int bytesTransferred = 0;
            while (bytesTransferred < length) {
                int actlen = controlTransfer(requestType, 0, value, index, data, bytesTransferred, length - bytesTransferred);
                if (System.currentTimeMillis() - startTime > DEFAULT_READ_OR_WRITE_TIMEOUT_MS) {
                    actlen = -99999999;
                }
                if (actlen < 0) {
                    throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_send_control_message, actlen));
                }
                bytesTransferred += actlen;
            }
        } finally {
            reentrantLock.unlock();
        }
    }
    synchronized void wrReg(int reg, byte[] val) throws DvbException {
        int index;
        if (reg < 0x3000) {
            index = CMD_USB_WR;
        } else if (reg < 0x4000) {
            index = CMD_SYS_WR;
        } else {
            index = CMD_IR_WR;
        }
        ctrlMsg(reg, index, val);
    }
    synchronized void wrReg(int reg, int onebyte) throws DvbException {
        byte[] data = new byte[] { (byte) onebyte };
        wrReg(reg, data);
    }
    synchronized void wrReg(int reg, int val, int mask) throws DvbException {
        if (mask != 0xff) {
            int tmp = rdReg(reg);
            val &= mask;
            tmp &= ~mask;
            val |= tmp;
        }
        wrReg(reg, val);
    }
    synchronized private void rdReg(int reg, byte[] val) throws DvbException {
        int index;
        if (reg < 0x3000) {
            index = CMD_USB_RD;
        } else if (reg < 0x4000) {
            index = CMD_SYS_RD;
        } else {
            index = CMD_IR_RD;
        }
        ctrlMsg(reg, index, val);
    }
    synchronized int rdReg(int reg) throws DvbException {
        byte[] result = new byte[1];
        rdReg(reg, result);
        return result[0] & 0xFF;
    }
    @Override
    protected synchronized void init() throws DvbException {
        /* init USB endpoints */
        int val = rdReg(USB_SYSCTL_0);
        /* enable DMA and Full Packet Mode*/
        val |= 0x09;
        wrReg(USB_SYSCTL_0, val);
        /* set EPA maximum packet size to 0x0200 */
        wrReg(USB_EPA_MAXPKT, new byte[] { 0x00, 0x02, 0x00, 0x00 });
        /* change EPA FIFO length */
        wrReg(USB_EPA_FIFO_CFG, new byte[] { 0x14, 0x00, 0x00, 0x00 });
    }
    class Rtl28xxI2cAdapter extends I2cAdapter {
        int page = -1;
        @Override
        protected int masterXfer(I2cMessage[] msg) throws DvbException {
            /*
	         * It is not known which are real I2C bus xfer limits, but testing
	         * with RTL2831U + MT2060 gives max RD 24 and max WR 22 bytes.
	         */
	        /*
	         * I2C adapter logic looks rather complicated due to fact it handles
	         * three different access methods. Those methods are;
	         * 1) integrated demod access
	         * 2) old I2C access
	         * 3) new I2C access
	         *
	         * Used method is selected in order 1, 2, 3. Method 3 can handle all
	         * requests but there is two reasons why not use it always;
	         * 1) It is most expensive, usually two USB messages are needed
	         * 2) At least RTL2831U does not support it
	         *
	         * Method 3 is needed in case of I2C write+read (typical register read)
	         * where write is more than one byte.
	         */
            if (msg.length == 2 && (msg[0].flags & I2cMessage.I2C_M_RD) == 0 &&
                    (msg[1].flags & I2cMessage.I2C_M_RD) != 0) {
                if (msg[0].len > 24 || msg[1].len > 24) {
                    throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
                } else if (msg[0].addr == 0x10) {
			            /* method 1 - integrated demod */
                    ctrlMsg(((msg[0].buf[0] & 0xFF) << 8) | (msg[0].addr << 1),
                            page,
                            msg[1].buf,
                            msg[1].len);
                } else if (msg[0].len < 2) {
                        /* method 2 - old I2C */
                    ctrlMsg(((msg[0].buf[0] & 0xFF) << 8) | (msg[0].addr << 1),
                            CMD_I2C_RD,
                            msg[1].buf,
                            msg[1].len);
                } else {
                        /* method 3 - new I2C */
                    ctrlMsg(msg[0].addr << 1, CMD_I2C_DA_WR, msg[0].buf, msg[0].len);
                    ctrlMsg(msg[0].addr << 1, CMD_I2C_DA_RD, msg[1].buf, msg[1].len);
                }
            } else if (msg.length == 1 && (msg[0].flags & I2cMessage.I2C_M_RD) == 0) {
                if (msg[0].len > 22) {
                    throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
                } else if (msg[0].addr == 0x10) {
			            /* method 1 - integrated demod */
                    if (msg[0].buf[0] == 0x00) {
				            /* save demod page for later demod access */
                        page = msg[0].buf[1] & 0xFF;
                    } else {
                        byte[] newdata = new byte[msg[0].len-1];
                        System.arraycopy(msg[0].buf, 1, newdata, 0, newdata.length);
                        ctrlMsg(((msg[0].buf[0] & 0xFF) << 8) | (msg[0].addr << 1),
                                CMD_DEMOD_WR | page,
                                newdata);
                    }
                } else if (msg[0].len < 23) {
                        /* method 2 - old I2C */
                    byte[] newdata = new byte[msg[0].len-1];
                    System.arraycopy(msg[0].buf, 1, newdata, 0, newdata.length);
                    ctrlMsg(((msg[0].buf[0] & 0xFF) << 8) | (msg[0].addr << 1),
                            CMD_I2C_WR,
                            newdata);
                } else {
                        /* method 3 - new I2C */
                    ctrlMsg(msg[0].addr << 1, CMD_I2C_DA_WR, msg[0].buf, msg[0].len);
                }
            } else {
                throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
            }
            return msg.length;
        }
    }
    @Override
    protected UsbEndpoint getUsbEndpoint() {
        return endpoint;
    }
    @Override
    protected AlternateUsbInterface getUsbInterface() {
        return AlternateUsbInterface.forUsbInterface(usbDeviceConnection, iface).get(0);
    }
    @SuppressWarnings("WeakerAccess") // This is a false warning
    class TunerCallbackBuilder {
        TunerCallback forTuner(final Rtl28xxTunerType tuner) {
            return new TunerCallback() {
                @Override
                public void onFeVhfEnable(boolean enable) throws DvbException {
                    if (tuner == Rtl28xxTunerType.RTL2832_FC0012) {
                    /* set output values */
                        int val = rdReg(SYS_GPIO_OUT_VAL);
                        if (enable) {
                            val &= 0xbf; /* set GPIO6 low */
                        } else {
                            val |= 0x40; /* set GPIO6 high */
                        }
                        wrReg(SYS_GPIO_OUT_VAL, val);
                    } else {
                        throw new DvbException(BAD_API_USAGE, "Unexpected tuner asks callback");
                    }
                }
            };
        }
    }
    interface TunerCallback {
        void onFeVhfEnable(boolean enable) throws DvbException;
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
package info.martinmarinov.drivers.usb.cxusb;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import java.util.Set;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import static info.martinmarinov.drivers.tools.SetUtils.setOf;
import static info.martinmarinov.drivers.usb.cxusb.MygicaT230.MYGICA_T230;
import static info.martinmarinov.drivers.usb.cxusb.MygicaT230C.MYGICA_T230C;
public class CxUsbDvbDeviceCreator implements DvbUsbDevice.Creator {
    private final static Set<DeviceFilter> CXUSB_DEVICES = setOf(MYGICA_T230, MYGICA_T230C);
    @Override
    public Set<DeviceFilter> getSupportedDevices() {
        return CXUSB_DEVICES;
    }
    @Override
    public DvbUsbDevice create(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException {
        if (MYGICA_T230C.matches(usbDevice)) {
            return new MygicaT230C(usbDevice, context);
        } else {
            return new MygicaT230(usbDevice, context);
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
package info.martinmarinov.drivers.usb.cxusb;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.DvbUsbIds;
import info.martinmarinov.drivers.usb.silabs.Si2157;
import info.martinmarinov.drivers.usb.silabs.Si2168;
import static info.martinmarinov.drivers.usb.silabs.Si2157.Type.SI2157_CHIPTYPE_SI2157;
class MygicaT230 extends CxUsbDvbDevice {
    private final static String MYGICA_NAME = "Mygica T230 DVB-T/T2/C";
    final static DeviceFilter MYGICA_T230 = new DeviceFilter(DvbUsbIds.USB_VID_CONEXANT, DvbUsbIds.USB_PID_MYGICA_T230, MYGICA_NAME);
    private Si2168 frontend;
    MygicaT230(UsbDevice usbDevice, Context context) throws DvbException {
        super(usbDevice, context, MYGICA_T230);
    }
    @Override
    public String getDebugString() {
        return MYGICA_NAME;
    }
    @Override
    synchronized protected void powerControl(boolean onoff) throws DvbException {
        cxusb_d680_dmb_power_ctrl(onoff);
        cxusb_streaming_ctrl(onoff);
    }
    private void cxusb_d680_dmb_power_ctrl(boolean onoff) throws DvbException {
        cxusb_power_ctrl(onoff);
        if (!onoff) return;
        SleepUtils.mdelay(128);
        cxusb_ctrl_msg(CMD_DIGITAL, new byte[0], 0, new byte[1], 1);
        SleepUtils.mdelay(100);
    }
    @Override
    protected void readConfig() throws DvbException {
        // no-op
    }
    @Override
    protected DvbFrontend frontendAttatch() throws DvbException {
        return frontend = new Si2168(this, resources, i2CAdapter, 0x64, SI2168_TS_PARALLEL, true, SI2168_TS_CLK_AUTO_FIXED, false);
    }
    @Override
    protected DvbTuner tunerAttatch() throws DvbException {
        return new Si2157(resources, i2CAdapter, frontend.gateControl(), 0x60, true, SI2157_CHIPTYPE_SI2157);
    }
    @Override
    protected void init() throws DvbException {
        // no-op
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
package info.martinmarinov.drivers.usb.rtl28xx;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.tools.SleepUtils.mdelay;
import static info.martinmarinov.drivers.usb.rtl28xx.R820tTuner.RafaelChip.CHIP_R820T;
import static info.martinmarinov.drivers.usb.rtl28xx.R820tTuner.RafaelChip.CHIP_R828D;
import android.content.res.Resources;
import androidx.annotation.NonNull;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.Check;
import info.martinmarinov.drivers.tools.I2cAdapter.I2GateControl;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxDvbDevice.Rtl28xxI2cAdapter;
enum Rtl28xxTunerType {
    RTL2832_E4000(
            device -> {
                byte[] data = new byte[1];
                device.ctrlMsg(0x02c8, Rtl28xxConst.CMD_I2C_RD, data);
                return (data[0] & 0xff) == 0x40;
            },
            (resources, device) -> Rtl28xxSlaveType.SLAVE_DEMOD_NONE, (device, adapter, i2GateControl, resources, tunerCallback) -> {
                // The tuner uses sames XTAL as the frontend at 28.8 MHz
                return new E4000Tuner(0x64, adapter, 28_800_000L, i2GateControl, resources);
            }
    ),
    RTL2832_FC0012(
            device -> {
                byte[] data = new byte[1];
                device.ctrlMsg(0x00c6, Rtl28xxConst.CMD_I2C_RD, data);
                return (data[0] & 0xff) == 0xa1;
            },
            (resources, device) -> Rtl28xxSlaveType.SLAVE_DEMOD_NONE, (device, adapter, i2GateControl, resources, tunerCallback) -> {
                // The tuner uses sames XTAL as the frontend at 28.8 MHz
                return new FC0012Tuner(0xc6>>1, adapter, 28_800_000L, i2GateControl, tunerCallback);
            }
    ),
    RTL2832_FC0013(
            device -> {
                byte[] data = new byte[1];
                device.ctrlMsg(0x00c6, Rtl28xxConst.CMD_I2C_RD, data);
                return (data[0] & 0xff) == 0xa3;
            },
            (resources, device) -> Rtl28xxSlaveType.SLAVE_DEMOD_NONE, (device, adapter, i2GateControl, resources, tunerCallback) -> {
                // The tuner uses sames XTAL as the frontend at 28.8 MHz
                return new FC0013Tuner(0xc6>>1, adapter, 28_800_000L, i2GateControl);
            }
    ),
    RTL2832_R820T(
            device -> {
                byte[] data = new byte[1];
                device.ctrlMsg(0x0034, Rtl28xxConst.CMD_I2C_RD, data);
                return (data[0] & 0xff) == 0x69;
            },
            (resources, device) -> Rtl28xxSlaveType.SLAVE_DEMOD_NONE, (device, adapter, i2GateControl, resources, tunerCallback) -> {
                // The tuner uses sames XTAL as the frontend at 28.8 MHz
                return new R820tTuner(0x1a, adapter, CHIP_R820T, 28_800_000L, i2GateControl, resources);
            }
    ),
    RTL2832_R828D(
            device -> {
                byte[] data = new byte[1];
                device.ctrlMsg(0x0074, Rtl28xxConst.CMD_I2C_RD, data);
                return (data[0] & 0xff) == 0x69;
            },
            (resources, device) -> {
                /* power off slave demod on GPIO0 to reset CXD2837ER */
                device.wrReg(Rtl28xxConst.SYS_GPIO_OUT_VAL, 0x00, 0x01);
                device.wrReg(Rtl28xxConst.SYS_GPIO_OUT_EN, 0x00, 0x01);
                mdelay(50);
                /* power on MN88472 demod on GPIO0 */
                device.wrReg(Rtl28xxConst.SYS_GPIO_OUT_VAL, 0x01, 0x01);
                device.wrReg(Rtl28xxConst.SYS_GPIO_DIR, 0x00, 0x01);
                device.wrReg(Rtl28xxConst.SYS_GPIO_OUT_EN, 0x01, 0x01);
                /* check MN88472 answers */
                byte[] data = new byte[1];
                try {
                    device.ctrlMsg(0xff38, Rtl28xxConst.CMD_I2C_RD, data);
                } catch (DvbException e) {
                    try {
                        // cxd2837er fails to read the mn88472/ mn88473 register
                        device.ctrlMsg(0xfdd8, Rtl28xxConst.CMD_I2C_RD, data);
                    } catch (DvbException ee) {
                        if (device.isRtlSdrBlogV4) {
                            // I've failed so far to make RTL SDR Blog V4 dongle working
                            // It seems to lock to frequency but doesn't send any data back
                            // If anyone wants to pick this up, just uncomment the line below and give it a go. Happy to accept pull request.
                            throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
                        }
                        return Rtl28xxSlaveType.SLAVE_DEMOD_NONE;
                    }
                }
                switch (data[0] & 0xFF) {
                    case 0x02:
                        return Rtl28xxSlaveType.SLAVE_DEMOD_MN88472;
                    case 0x03:
                        return Rtl28xxSlaveType.SLAVE_DEMOD_MN88473;
                    case 0xb1:
                        return Rtl28xxSlaveType.SLAVE_DEMOD_CXD2837ER;
                    default:
                        throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_slave_on_tuner));
                }
            }, (device, adapter, i2GateControl, resources, tunerCallback) -> {
                long xtal = 16_000_000L;
                if (device.isRtlSdrBlogV4) {
                    xtal = 28_800_000L;
                }
                // Actual tuner xtal and frontend crystals are different
                return new R820tTuner(0x3a, adapter, CHIP_R828D, xtal, i2GateControl, resources);
            }
    );
    private final IsPresent isPresent;
    private final SlaveParser slaveParser;
    private final DvbTunerCreator creator;
    Rtl28xxTunerType(IsPresent isPresent, SlaveParser slaveParser, DvbTunerCreator creator) {
        this.isPresent = isPresent;
        this.slaveParser = slaveParser;
        this.creator = creator;
    }
    public static @NonNull Rtl28xxTunerType detectTuner(Resources resources, Rtl28xxDvbDevice device) throws DvbException {
        for (Rtl28xxTunerType tuner : values()) {
            try {
                if (tuner.isPresent.isPresent(device)) return tuner;
            } catch (DvbException ignored) {
                // Do nothing, if it is not the correct tuner, the control message
                // will throw an exception and that's ok
            }
        }
        throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unrecognized_tuner_on_device));
    }
    public @NonNull Rtl28xxSlaveType detectSlave(Resources resources, Rtl28xxDvbDevice device) throws DvbException {
        return slaveParser.getSlave(resources, device);
    }
    public @NonNull DvbTuner createTuner(Rtl28xxDvbDevice device, Rtl28xxI2cAdapter adapter, I2GateControl i2GateControl, Resources resources, Rtl28xxDvbDevice.TunerCallback tunerCallback) throws DvbException {
        return creator.create(device, adapter, Check.notNull(i2GateControl), resources, tunerCallback);
    }
    private interface IsPresent {
        boolean isPresent(Rtl28xxDvbDevice device) throws DvbException;
    }
    private interface DvbTunerCreator {
        @NonNull DvbTuner create(Rtl28xxDvbDevice device, Rtl28xxI2cAdapter adapter, I2GateControl i2GateControl, Resources resources, Rtl28xxDvbDevice.TunerCallback tunerCallback) throws DvbException;
    }
    private interface SlaveParser {
        @NonNull Rtl28xxSlaveType getSlave(Resources resources, Rtl28xxDvbDevice device) throws DvbException;
    }
}
package info.martinmarinov.drivers.usb.cxusb;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.DvbUsbIds;
import info.martinmarinov.drivers.usb.silabs.Si2157;
import info.martinmarinov.drivers.usb.silabs.Si2168;
import static info.martinmarinov.drivers.usb.silabs.Si2157.Type.SI2157_CHIPTYPE_SI2141;
/**
 * @author dmgouriev
 */
public class MygicaT230C extends CxUsbDvbDevice {
    public final static String MYGICA_NAME = "Mygica T230C DVB-T/T2/C";
    final static DeviceFilter MYGICA_T230C = new DeviceFilter(DvbUsbIds.USB_VID_CONEXANT, DvbUsbIds.USB_PID_GENIATECH_T230C, MYGICA_NAME);
    private Si2168 frontend;
    MygicaT230C(UsbDevice usbDevice, Context context) throws DvbException {
        super(usbDevice, context, MYGICA_T230C);
    }
    @Override
    public String getDebugString() {
        return MYGICA_NAME;
    }
    @Override
    synchronized protected void powerControl(boolean onoff) throws DvbException {
        cxusb_d680_dmb_power_ctrl(onoff);
        cxusb_streaming_ctrl(onoff);
    }
    private void cxusb_d680_dmb_power_ctrl(boolean onoff) throws DvbException {
        cxusb_power_ctrl(onoff);
        if (!onoff) return;
        SleepUtils.mdelay(128);
        cxusb_ctrl_msg(CMD_DIGITAL, new byte[0], 0, new byte[1], 1);
        SleepUtils.mdelay(100);
    }
    @Override
    protected void readConfig() throws DvbException {
        // no-op
    }
    @Override
    protected DvbFrontend frontendAttatch() throws DvbException {
        return frontend = new Si2168(this, resources, i2CAdapter, 0x64, SI2168_TS_PARALLEL, true, SI2168_TS_CLK_AUTO_ADAPT, false);
    }
    @Override
    protected DvbTuner tunerAttatch() throws DvbException {
        return new Si2157(resources, i2CAdapter, frontend.gateControl(), 0x60, false, SI2157_CHIPTYPE_SI2141);
    }
    @Override
    protected void init() throws DvbException {
        // no-op
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
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
/**
 * Android USB API drops packets when high speeds are needed.
 *
 * This is why we need this framework to allow us to use the kernel to handle the transfer for us
 *
 * Inspired by http://www.source-code.biz/snippets/java/UsbIso
 *
 * This is not thread safe! Call only from one thread.
 */
public class UsbHiSpeedBulk {
    public final static boolean IS_PLATFORM_SUPPORTED;
    static {
        boolean isPlatformSupported = false;
        try {
            System.loadLibrary("UsbXfer");
            isPlatformSupported = true;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        IS_PLATFORM_SUPPORTED = isPlatformSupported;
    }
    private final int fileDescriptor;
    private final UsbDeviceConnection usbDeviceConnection;
    private final List<IsoRequest> requests;
    private final int nrequests, packetsPerRequests, packetSize;
    private final UsbEndpoint usbEndpoint;
    private final Buffer buffer;
    public UsbHiSpeedBulk(UsbDeviceConnection usbDeviceConnection, UsbEndpoint usbEndpoint, int nrequests, int packetsPerRequests) {
        this.usbDeviceConnection = usbDeviceConnection;
        this.fileDescriptor = usbDeviceConnection.getFileDescriptor();
        this.nrequests = nrequests;
        this.requests = new ArrayList<>(nrequests);
        this.packetSize = usbEndpoint.getMaxPacketSize();
        this.usbEndpoint = usbEndpoint;
        this.packetsPerRequests = packetsPerRequests;
        this.buffer = new Buffer(packetsPerRequests * packetSize);
    }
    // API
    public void setInterface(AlternateUsbInterface usbInterface) throws IOException {
        IoctlUtils.res(jni_setInterface(fileDescriptor, usbInterface.getUsbInterface().getId(), usbInterface.getAlternateSettings()));
    }
    public void unsetInterface(AlternateUsbInterface usbInterface) throws IOException {
        // According to original UsbHiSpeedBulk alternatesetting of 0 stops the streaming
        IoctlUtils.res(jni_setInterface(fileDescriptor, usbInterface.getUsbInterface().getId(), 0));
    }
    public void start() throws IOException {
        for (int i = 0; i < nrequests; i++) {
            IsoRequest req = new IsoRequest(usbDeviceConnection, usbEndpoint, i, packetsPerRequests, packetSize);
            try {
                req.submit();
                requests.add(req);
            } catch (IOException e) {
                // No more memory for allocating packets
                e.printStackTrace();
                break;
            }
        }
        if (requests.isEmpty()) throw new IOException("Cannot initialize any USB requests");
    }
    /**
     * Buffer is not immutable! Next time you call #read, its value will be invalid and overwriten.
     * The buffer is reused for efficiency.
     * @param wait whther to block until data is available
     * @return a buffer or null if nothing is available
     * @throws IOException
     */
    public Buffer read(boolean wait) throws IOException {
        IsoRequest req = getReadyRequest(wait);
        if (req == null) return null;
        buffer.length = req.read(buffer.data);
        req.reset();
        req.submit();
        return buffer;
    }
    public void stop() throws IOException {
        for (IsoRequest r : requests) {
            r.cancel();
        }
        requests.clear();
    }
    public class Buffer {
        private final byte[] data;
        private int length;
        private Buffer(int bufferSize) {
            this.data = new byte[bufferSize];
        }
        public byte[] getData() {
            return data;
        }
        public int getLength() {
            return length;
        }
    }
    // helpers
    private IsoRequest getReadyRequest(boolean wait) throws IOException {
        int readyRequestId = IsoRequest.getReadyRequestId(usbDeviceConnection, wait);
        if (readyRequestId < 0) return null;
        return requests.get(readyRequestId);
    }
    // native
    private static native int jni_setInterface(int fd, int interfaceId, int alternateSettings);
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
package info.martinmarinov.usbxfer;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import java.io.IOException;
public class UsbBulkSource implements ByteSource {
    private final static int INITIAL_DELAY_BEFORE_BACKOFF = 1_000;
    private final static int MAX_BACKOFF = 10;
    private final UsbDeviceConnection usbDeviceConnection;
    private final UsbEndpoint usbEndpoint;
    private final AlternateUsbInterface usbInterface;
    private final int numRequests;
    private final int numPacketsPerReq;
    private UsbHiSpeedBulk usbHiSpeedBulk;
    private int backoff = -INITIAL_DELAY_BEFORE_BACKOFF;
    public UsbBulkSource(UsbDeviceConnection usbDeviceConnection, UsbEndpoint usbEndpoint, AlternateUsbInterface usbInterface, int numRequests, int numPacketsPerReq) {
        this.usbDeviceConnection = usbDeviceConnection;
        this.usbEndpoint = usbEndpoint;
        this.usbInterface = usbInterface;
        this.numRequests = numRequests;
        this.numPacketsPerReq = numPacketsPerReq;
    }
    @Override
    public void open() throws IOException {
        usbHiSpeedBulk = new UsbHiSpeedBulk(usbDeviceConnection, usbEndpoint, numRequests, numPacketsPerReq);
        usbHiSpeedBulk.setInterface(usbInterface);
        usbDeviceConnection.claimInterface(usbInterface.getUsbInterface(), true);
        usbHiSpeedBulk.start();
    }
    @Override
    public void readNext(ByteSink sink) throws IOException, InterruptedException {
        UsbHiSpeedBulk.Buffer read = usbHiSpeedBulk.read(false);
        if (read == null) {
            backoff++;
            if (backoff > 0) {
                Thread.sleep(backoff);
            } else {
                if (backoff % 3 == 0) Thread.sleep(1);
            }
            if (backoff > MAX_BACKOFF) {
                backoff = MAX_BACKOFF;
            }
        } else {
            backoff = -INITIAL_DELAY_BEFORE_BACKOFF;
            sink.consume(read.getData(), read.getLength());
        }
    }
    @Override
    public void close() throws IOException {
        usbHiSpeedBulk.stop();
        usbHiSpeedBulk.unsetInterface(usbInterface);
        usbDeviceConnection.releaseInterface(usbInterface.getUsbInterface());
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
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import java.util.HashSet;
import java.util.Set;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
@RunWith(AndroidJUnit4.class)
@MediumTest
public class DeviceFilterXmlEquivalenceTester {
    @Test
    public void allDevicesAreInXml() {
        Set<DeviceFilter> devicesInXml = getDeviceData(getApplicationContext().getResources(), R.xml.device_filter);
        Set<DeviceFilter> supportedDecvices = getDevicesInApp();
        if (!supportedDecvices.equals(devicesInXml)) {
            System.out.println("Devices needed to be added to the xml");
            for (DeviceFilter supportedDevice : supportedDecvices) {
                if (!devicesInXml.contains(supportedDevice)) {
                    System.out.printf("    <usb-device vendor-id=\"0x%x\" product-id=\"0x%x\"/> <!-- %s -->\n",
                            supportedDevice.getVendorId(), supportedDevice.getProductId(), supportedDevice.getName()
                    );
                }
            }
            System.out.println("Devices in xml but not supported");
            for (DeviceFilter deviceInXml : devicesInXml) {
                if (!supportedDecvices.contains(deviceInXml)) {
                    System.out.printf("vendor-id=0x%x, product-id=0x%x\n",
                            deviceInXml.getVendorId(), deviceInXml.getProductId()
                    );
                }
            }
        }
        assertThat(supportedDecvices, equalTo(devicesInXml));
    }
    private static HashSet<DeviceFilter> getDevicesInApp() {
        HashSet<DeviceFilter> supportedDevices = new HashSet<>();
        for (DvbUsbDevice.Creator d : DvbUsbDeviceRegistry.AVAILABLE_DRIVERS) {
            supportedDevices.addAll(d.getSupportedDevices());
        }
        return supportedDevices;
    }
    private static Set<DeviceFilter> getDeviceData(Resources resources, int xmlResourceId) {
        Set<DeviceFilter> ans = new HashSet<>();
        try {
            XmlResourceParser xml = resources.getXml(xmlResourceId);
            xml.next();
            int eventType;
            while ((eventType = xml.getEventType()) != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (xml.getName().equals("usb-device")) {
                        AttributeSet as = Xml.asAttributeSet(xml);
                        Integer vendorId = parseInt(as.getAttributeValue(null, "vendor-id"));
                        Integer productId = parseInt(as.getAttributeValue(null, "product-id"));
                        ans.add(new DeviceFilter(vendorId, productId, null));
                    }
                }
                xml.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ans;
    }
    private static Integer parseInt(String number) {
        if (number.startsWith("0x")) {
            return Integer.valueOf( number.substring(2), 16);
        } else {
            return Integer.valueOf( number, 10);
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
package info.martinmarinov.drivers.usb.rtl28xx;
import static info.martinmarinov.drivers.tools.Check.notNull;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.SYS_DEMOD_CTL;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.SYS_DEMOD_CTL1;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.SYS_GPIO_OUT_VAL;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.USB_EPA_CTL;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.I2cAdapter.I2GateControl;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
class Rtl2832DvbDevice extends Rtl28xxDvbDevice {
    private final static String TAG = Rtl2832DvbDevice.class.getSimpleName();
    private Rtl28xxTunerType tuner;
    private Rtl28xxSlaveType slave;
    Rtl2832DvbDevice(UsbDevice usbDevice, Context context, DeviceFilter deviceFilter) throws DvbException {
        super(usbDevice, context, deviceFilter);
    }
    @Override
    protected synchronized void powerControl(boolean turnOn) throws DvbException {
        Log.d(TAG, "Turning "+(turnOn ? "on" : "off"));
        if (turnOn) {
		    /* GPIO3=1, GPIO4=0 */
            wrReg(SYS_GPIO_OUT_VAL, 0x08, 0x18);
		    /* suspend? */
            wrReg(SYS_DEMOD_CTL1, 0x00, 0x10);
		    /* enable PLL */
            wrReg(SYS_DEMOD_CTL, 0x80, 0x80);
		    /* disable reset */
            wrReg(SYS_DEMOD_CTL, 0x20, 0x20);
		    /* streaming EP: clear stall & reset */
		    wrReg(USB_EPA_CTL, new byte[] {(byte) 0x00, (byte) 0x00});
            /* enable ADC */
            wrReg(SYS_DEMOD_CTL, 0x48, 0x48);
        } else {
		    /* GPIO4=1 */
            wrReg(SYS_GPIO_OUT_VAL, 0x10, 0x10);
		    /* disable PLL */
            wrReg(SYS_DEMOD_CTL, 0x00, 0x80);
		    /* streaming EP: set stall & reset */
            wrReg(USB_EPA_CTL, new byte[] {(byte) 0x10, (byte) 0x02});
            /* disable ADC */
            wrReg(SYS_DEMOD_CTL, 0x00, 0x48);
        }
    }
    @Override
    protected synchronized void readConfig() throws DvbException {
        /* enable GPIO3 and GPIO6 as output */
        wrReg(Rtl28xxConst.SYS_GPIO_DIR, 0x00, 0x40);
        wrReg(Rtl28xxConst.SYS_GPIO_OUT_EN, 0x48, 0x48);
        /*
	    * Probe used tuner. We need to know used tuner before demod attach
	    * since there is some demod params needed to set according to tuner.
	    */
	    /* open demod I2C gate */
	    i2GateController.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                tuner = Rtl28xxTunerType.detectTuner(resources, Rtl2832DvbDevice.this);
                slave = tuner.detectSlave(resources, Rtl2832DvbDevice.this);
            }
        });
        Log.d(TAG, "Detected tuner " + tuner + " with slave demod "+slave);
    }
    @Override
    protected synchronized DvbFrontend frontendAttatch() throws DvbException {
        notNull(tuner, "Initialize tuner first!");
        return slave.createFrontend(this, tuner, i2CAdapter, resources);
    }
    @Override
    protected synchronized DvbTuner tunerAttatch() throws DvbException {
        notNull(tuner, "Initialize tuner first!");
        notNull(frontend, "Initialize frontend first!");
        return tuner.createTuner(this, i2CAdapter, i2GateController, resources, tunerCallbackBuilder.forTuner(tuner));
    }
    @Override
    public String getDebugString() {
        StringBuilder sb = new StringBuilder("RTL2832 ");
        if (tuner != null) sb.append(tuner.name()).append(' ');
        if (slave != null) sb.append(slave.name()).append(' ');
        return sb.toString();
    }
    private final I2GateControl i2GateController = new I2GateControl() {
        private boolean i2cGateState = false;
        @Override
        protected synchronized void i2cGateCtrl(boolean enable) throws DvbException {
            if (i2cGateState == enable) return;
            if (enable) {
                ctrlMsg(0x0120, 0x0011, new byte[] {(byte) 0x18});
            } else {
                ctrlMsg(0x0120, 0x0011, new byte[] {(byte) 0x10});
            }
            i2cGateState = enable;
        }
    };
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
import static android.hardware.usb.UsbConstants.USB_DIR_IN;
import static android.hardware.usb.UsbConstants.USB_DIR_OUT;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.IO_EXCEPTION;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.usb.DvbUsbIds.USB_PID_AVERMEDIA_A867;
import static info.martinmarinov.drivers.usb.DvbUsbIds.USB_PID_AVERMEDIA_TWINSTAR;
import static info.martinmarinov.drivers.usb.DvbUsbIds.USB_VID_AVERMEDIA;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_ADC_MULTIPLIER_2X;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TS_MODE_SERIAL;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TS_MODE_USB;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_FC0011;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_FC0012;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_FC2580;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_38;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_51;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_52;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_60;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_61;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_IT9135_62;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_MXL5007T;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_TDA18218;
import static info.martinmarinov.drivers.usb.af9035.Af9033Config.AF9033_TUNER_TUA9001;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CLOCK_LUT_AF9035;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CLOCK_LUT_IT9135;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_BOOT;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_DL;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_DL_BEGIN;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_DL_END;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_QUERYINFO;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_FW_SCATTER_WR;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_GENERIC_I2C_RD;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_GENERIC_I2C_WR;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_I2C_RD;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_I2C_WR;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_MEM_RD;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.CMD_MEM_WR;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.EEPROM_1_IF_H;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.EEPROM_1_IF_L;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.EEPROM_1_TUNER_ID;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.EEPROM_2ND_DEMOD_ADDR;
import static info.martinmarinov.drivers.usb.af9035.Af9035Data.EEPROM_TS_MODE;
import static info.martinmarinov.drivers.usb.af9035.It913x.IT9133AX_TUNER;
import static info.martinmarinov.drivers.usb.af9035.It913x.IT9133BX_TUNER;
import static info.martinmarinov.drivers.usb.af9035.It913x.IT913X_ROLE_DUAL_MASTER;
import static info.martinmarinov.drivers.usb.af9035.It913x.IT913X_ROLE_SINGLE;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDemux;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.generic.AbstractGenericDvbUsbDevice;
import info.martinmarinov.usbxfer.AlternateUsbInterface;
class Af9035DvbDevice extends AbstractGenericDvbUsbDevice {
    private final static String TAG = Af9035DvbDevice.class.getSimpleName();
    private final static boolean USB_SPEED_FULL = false; // this is tue only for USB 1.1 which is not on Android
    private final UsbInterface iface;
    private final UsbEndpoint endpoint;
    private final I2cAdapter i2CAdapter = new Af9035I2cAdapter();
    private int chip_version;
    private int chip_type;
    private int firmware;
    private boolean dual_mode;
    private boolean no_eeprom;
    private boolean no_read;
    private byte[] eeprom = new byte[256];
    private int[] af9033_i2c_addr = new int[2];
    private Af9033Config[] af9033_config;
    Af9035DvbDevice(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException {
        super(
                usbDevice,
                context,
                filter,
                DvbDemux.DvbDmxSwfilter(),
                usbDevice.getInterface(0).getEndpoint(0),
                usbDevice.getInterface(0).getEndpoint(1));
        iface = usbDevice.getInterface(0);
        endpoint = iface.getEndpoint(2);
        // Endpoint 3 is a TS USB_DIR_IN endpoint with address 0x85
        // but I don't know what it is used for
        if (controlEndpointIn.getAddress() != 0x81 || controlEndpointIn.getDirection() != USB_DIR_IN)
            throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
        if (controlEndpointOut.getAddress() != 0x02 || controlEndpointOut.getDirection() != USB_DIR_OUT)
            throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
        if (endpoint.getAddress() != 0x84 || endpoint.getDirection() != USB_DIR_IN)
            throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_usb_endpoint));
    }
    @Override
    public String getDebugString() {
        return "AF9035 device " + getDeviceFilter().getName();
    }
    @Override
    protected void powerControl(boolean turnOn) throws DvbException {
        // no-op
    }
    private boolean identifyState() throws DvbException {
        byte[] rbuf = new byte[4];
        rd_regs(0x1222, rbuf, 3);
        chip_version = rbuf[0] & 0xFF;
        chip_type = (rbuf[2] & 0xFF) << 8 | (rbuf[1] & 0xFF);
        int prechip_version = rd_reg(0x384f);
        Log.d(TAG, String.format("prechip_version=%02x chip_version=%02x chip_type=%04x", prechip_version, chip_version, chip_type));
        int utmp;
        no_eeprom = false;
        int eeprom_addr = -1;
        if (chip_type == 0x9135) {
            if (chip_version == 0x02) {
                firmware = R.raw.dvbusbit913502fw;
                utmp = 0x00461d;
            } else {
                firmware = R.raw.dvbusbit913501fw;
                utmp = 0x00461b;
            }
		    /* Check if eeprom exists */
            int tmp = rd_reg(utmp);
            if (tmp == 0x00) {
                Log.d(TAG, "no eeprom");
                no_eeprom = true;
            }
            eeprom_addr = 0x4994; // EEPROM_BASE_IT9135
        } else if (chip_type == 0x9306) {
            firmware = R.raw.dvbusbit930301fw;
            no_eeprom = true;
        } else {
            firmware = R.raw.dvbusbaf903502fw;
            eeprom_addr = 0x42f5; //EEPROM_BASE_AF9035;
        }
        if (!no_eeprom) {
	        /* Read and store eeprom */
            byte[] tempbuff = new byte[32];
            for (int i = 0; i < 256; i += 32) {
                rd_regs(eeprom_addr + i, tempbuff, 32);
                System.arraycopy(tempbuff, 0, eeprom, i, 32);
            }
	        /* check for dual tuner mode */
            int tmp = eeprom[EEPROM_TS_MODE] & 0xFF;
            boolean ts_mode_invalid = false;
            switch (tmp) {
                case 0:
                    break;
                case 1:
                case 3:
                    dual_mode = true;
                    break;
                case 5:
                    if (chip_type != 0x9135 && chip_type != 0x9306) {
                        dual_mode = true;	/* AF9035 */
                    } else {
                        ts_mode_invalid = true;
                    }
                    break;
                default:
                    ts_mode_invalid = true;
            }
            Log.d(TAG, "ts mode=" + tmp + " dual mode=" + dual_mode);
            if (ts_mode_invalid) {
                Log.d(TAG, "ts mode=" + tmp + " not supported, defaulting to single tuner mode!");
            }
        }
        ctrlMsg(CMD_FW_QUERYINFO, 0, 1, new byte[]{1}, rbuf.length, rbuf);
        return rbuf[0] != 0 || rbuf[1] != 0 || rbuf[2] != 0 || rbuf[3] != 0;
    }
    private void download_firmware_old(byte[] fw_data) throws DvbException {
        /*
         * Thanks to Daniel GlÃ¶ckner <daniel-gl@gmx.net> about that info!
         *
         * byte 0: MCS 51 core
         *  There are two inside the AF9035 (1=Link and 2=OFDM) with separate
         *  address spaces
         * byte 1-2: Big endian destination address
         * byte 3-4: Big endian number of data bytes following the header
         * byte 5-6: Big endian header checksum, apparently ignored by the chip
         *  Calculated as ~(h[0]*256+h[1]+h[2]*256+h[3]+h[4]*256)
         */
        final int HDR_SIZE = 7;
        final int MAX_DATA = 58;
        byte[] tbuff = new byte[MAX_DATA];
        int i;
        for (i = fw_data.length; i > HDR_SIZE; ) {
            int hdr_core = fw_data[fw_data.length - i] & 0xFF;
            int hdr_addr = (fw_data[fw_data.length - i + 1] & 0xFF) << 8;
            hdr_addr |= fw_data[fw_data.length - i + 2] & 0xFF;
            int hdr_data_len = (fw_data[fw_data.length - i + 3] & 0xFF) << 8;
            hdr_data_len |= fw_data[fw_data.length - i + 4] & 0xFF;
            int hdr_checksum = (fw_data[fw_data.length - i + 5] & 0xFF) << 8;
            hdr_checksum |= fw_data[fw_data.length - i + 6] & 0xFF;
            Log.d(TAG, String.format("core=%d addr=%04x data_len=%d checksum=%04x",
                    hdr_core, hdr_addr, hdr_data_len, hdr_checksum));
            if (((hdr_core != 1) && (hdr_core != 2)) || (hdr_data_len > i)) {
                Log.e(TAG, "bad firmware");
                break;
            }
		    /* download begin packet */
            ctrlMsg(CMD_FW_DL_BEGIN, 0, 0, null, 0, null);
		    /* download firmware packet(s) */
            for (int j = HDR_SIZE + hdr_data_len; j > 0; j -= MAX_DATA) {
                int len = j;
                if (len > MAX_DATA) len = MAX_DATA;
                System.arraycopy(fw_data, fw_data.length - i + HDR_SIZE + hdr_data_len - j, tbuff, 0, len);
                ctrlMsg(CMD_FW_DL, 0, 0, tbuff, 0, null);
            }
		    /* download end packet */
            ctrlMsg(CMD_FW_DL_END, 0, 0, null, 0, null);
            i -= hdr_data_len + HDR_SIZE;
            Log.d(TAG, "data uploaded=" + (fw_data.length - i));
        }
	    /* print warn if firmware is bad, continue and see what happens */
        if (i != 0) {
            Log.e(TAG, "bad firmware");
        }
    }
    private void download_firmware_new(byte[] fw_data) throws DvbException {
        final int HDR_SIZE = 7;
        byte[] tbuff = new byte[fw_data.length];
        /*
         * There seems to be following firmware header. Meaning of bytes 0-3
         * is unknown.
         *
         * 0: 3
         * 1: 0, 1
         * 2: 0
         * 3: 1, 2, 3
         * 4: addr MSB
         * 5: addr LSB
         * 6: count of data bytes ?
         */
        for (int i = HDR_SIZE, i_prev = 0; i <= fw_data.length; i++) {
            if (i == fw_data.length || (fw_data[i] == 0x03 && (fw_data[i + 1] == 0x00 || fw_data[i + 1] == 0x01) && fw_data[i + 2] == 0x00)) {
                System.arraycopy(fw_data, i_prev, tbuff, 0, i - i_prev);
                ctrlMsg(CMD_FW_SCATTER_WR, 0, i - i_prev, tbuff, 0, null);
                i_prev = i;
            }
        }
    }
    private void download_firmware(byte[] fw_data) throws DvbException {
        /*
         * In case of dual tuner configuration we need to do some extra
         * initialization in order to download firmware to slave demod too,
         * which is done by master demod.
         * Master feeds also clock and controls power via GPIO.
         */
        if (dual_mode) {
		    /* configure gpioh1, reset & power slave demod */
            wr_reg_mask(0x00d8b0, 0x01, 0x01);
            wr_reg_mask(0x00d8b1, 0x01, 0x01);
            wr_reg_mask(0x00d8af, 0x00, 0x01);
            SleepUtils.usleep(50_000);
            wr_reg_mask(0x00d8af, 0x01, 0x01);
		    /* tell the slave I2C address */
            int tmp = eeprom[EEPROM_2ND_DEMOD_ADDR] & 0xFF;
		    /* Use default I2C address if eeprom has no address set */
            if (tmp == 0) {
                tmp = 0x1d << 1; /* 8-bit format used by chip */
            }
            if ((chip_type == 0x9135) || (chip_type == 0x9306)) {
                wr_reg(0x004bfb, tmp);
            } else {
                wr_reg(0x00417f, tmp);
			    /* enable clock out */
                wr_reg_mask(0x00d81a, 0x01, 0x01);
            }
        }
        if (fw_data[0] == 0x01) {
            download_firmware_old(fw_data);
        } else {
            download_firmware_new(fw_data);
        }
	    /* firmware loaded, request boot */
        ctrlMsg(CMD_FW_BOOT, 0, 0, null, 0, null);
	    /* ensure firmware starts */
        byte[] rbuf = new byte[4];
        ctrlMsg(CMD_FW_QUERYINFO, 0, 1, new byte[]{1}, 4, rbuf);
        if (rbuf[0] == 0 && rbuf[1] == 0 && rbuf[2] == 0 && rbuf[3] == 0) {
            throw new DvbException(IO_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
        }
        Log.d(TAG, String.format("firmware version=%d.%d.%d.%d", rbuf[0], rbuf[1], rbuf[2], rbuf[3]));
    }
    private byte[] readFirmware(int resource) throws DvbException {
        InputStream inputStream = resources.openRawResource(resource);
        //noinspection TryFinallyCanBeTryWithResources
        try {
            byte[] fw = new byte[inputStream.available()];
            if (inputStream.read(fw) != fw.length) {
                throw new DvbException(IO_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
            }
            return fw;
        } catch (IOException e) {
            throw new DvbException(IO_EXCEPTION, e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    @Override
    protected synchronized void readConfig() throws DvbException {
        boolean isWarm = identifyState();
        Log.d(TAG, "Device is " + (isWarm ? "WARM" : "COLD"));
        if (!isWarm) {
            byte[] fw_data = readFirmware(firmware);
            download_firmware(fw_data);
            isWarm = identifyState();
            if (!isWarm) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
            Log.d(TAG, "Device is WARM");
        }
        // actual read_config
        /* Demod I2C address */
        af9033_i2c_addr[0] = 0x1c;
        af9033_i2c_addr[1] = 0x1d;
        int[] af9033_configadc_multiplier = new int[] { AF9033_ADC_MULTIPLIER_2X, AF9033_ADC_MULTIPLIER_2X };
        int[] af9033_configts_mode = new int[] { AF9033_TS_MODE_USB, AF9033_TS_MODE_SERIAL };
        boolean[] af9033_configdyn0_clk = new boolean[2];
        boolean[] af9033_configspec_inv = new boolean[2];
        int[] af9033_configtuner = new int[2];
        int[] af9033_configclock = new int[2];
        boolean skip_eeprom = false;
        if (chip_type == 0x9135) {
		    /* feed clock for integrated RF tuner */
            af9033_configdyn0_clk[0] = true;
            af9033_configdyn0_clk[1] = true;
            if (chip_version == 0x02) {
                af9033_configtuner[0] = AF9033_TUNER_IT9135_60;
                af9033_configtuner[1] = AF9033_TUNER_IT9135_60;
            } else {
                af9033_configtuner[0] = AF9033_TUNER_IT9135_38;
                af9033_configtuner[1] = AF9033_TUNER_IT9135_38;
            }
            if (no_eeprom) {
			    /* skip rc stuff */
                skip_eeprom = true;
            }
        } else if (chip_type == 0x9306) {
            /*
             * IT930x is an USB bridge, only single demod-single tuner
             * configurations seen so far.
             */
            return;
        }
        if (!skip_eeprom) {
	        /* Skip remote controller */
            if (dual_mode) {
		        /* Read 2nd demodulator I2C address. 8-bit format on eeprom */
                int tmp = eeprom[EEPROM_2ND_DEMOD_ADDR] & 0xFF;
                if (tmp != 0) {
                    af9033_i2c_addr[1] = tmp >> 1;
                }
            }
            int eeprom_offset = 0;
            for (int i = 0; i < (dual_mode ? 2 : 1); i++) {
		        /* tuner */
                int tmp = eeprom[EEPROM_1_TUNER_ID + eeprom_offset] & 0xFF;
		        /* tuner sanity check */
                if (chip_type == 0x9135) {
                    if (chip_version == 0x02) {
				        /* IT9135 BX (v2) */
                        switch (tmp) {
                            case AF9033_TUNER_IT9135_60:
                            case AF9033_TUNER_IT9135_61:
                            case AF9033_TUNER_IT9135_62:
                                af9033_configtuner[i] = tmp;
                                break;
                        }
                    } else {
				    /* IT9135 AX (v1) */
                        switch (tmp) {
                            case AF9033_TUNER_IT9135_38:
                            case AF9033_TUNER_IT9135_51:
                            case AF9033_TUNER_IT9135_52:
                                af9033_configtuner[i] = tmp;
                                break;
                        }
                    }
                } else {
			    /* AF9035 */
                    af9033_configtuner[i] = tmp;
                }
                if (af9033_configtuner[i] != tmp) {
                    Log.d(TAG, String.format("[%d] overriding tuner from %02x to %02x", i, tmp, af9033_configtuner[i]));
                }
                switch (af9033_configtuner[i]) {
                    case AF9033_TUNER_TUA9001:
                    case AF9033_TUNER_FC0011:
                    case AF9033_TUNER_MXL5007T:
                    case AF9033_TUNER_TDA18218:
                    case AF9033_TUNER_FC2580:
                    case AF9033_TUNER_FC0012:
                        af9033_configspec_inv[i] = true;
                        break;
                    case AF9033_TUNER_IT9135_38:
                    case AF9033_TUNER_IT9135_51:
                    case AF9033_TUNER_IT9135_52:
                    case AF9033_TUNER_IT9135_60:
                    case AF9033_TUNER_IT9135_61:
                    case AF9033_TUNER_IT9135_62:
                        break;
                    default:
                        throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
                }
		        /* disable dual mode if driver does not support it */
                if (i == 1) {
                    switch (af9033_configtuner[i]) {
                        case AF9033_TUNER_FC0012:
                        case AF9033_TUNER_IT9135_38:
                        case AF9033_TUNER_IT9135_51:
                        case AF9033_TUNER_IT9135_52:
                        case AF9033_TUNER_IT9135_60:
                        case AF9033_TUNER_IT9135_61:
                        case AF9033_TUNER_IT9135_62:
                        case AF9033_TUNER_MXL5007T:
                            break;
                        default:
                            dual_mode = false;
                            Log.w(TAG, "driver does not support 2nd tuner and will disable it");
                    }
                }
		        /* tuner IF frequency */
                tmp = eeprom[EEPROM_1_IF_L + eeprom_offset] & 0xFF;
                int tmp16 = tmp;
                tmp = eeprom[EEPROM_1_IF_H + eeprom_offset] & 0xFF;
                tmp16 |= tmp << 8;
                Log.d(TAG, String.format("[%d]IF=%d", i, tmp16));
                eeprom_offset += 0x10; /* shift for the 2nd tuner params */
            }
        }
	    /* get demod clock */
        int tmp = rd_reg(0x00d800) & 0x0f;
        for (int i = 0; i < 2; i++) {
            if (chip_type == 0x9135) {
                af9033_configclock[i] = CLOCK_LUT_IT9135[tmp];
            } else {
                af9033_configclock[i] = CLOCK_LUT_AF9035[tmp];
            }
        }
        no_read = false;
	    /* Some MXL5007T devices cannot properly handle tuner I2C read ops. */
        if (af9033_configtuner[0] == AF9033_TUNER_MXL5007T && getDeviceFilter().getVendorId() == USB_VID_AVERMEDIA)
            switch (getDeviceFilter().getProductId()) {
                case USB_PID_AVERMEDIA_A867:
                case USB_PID_AVERMEDIA_TWINSTAR:
                    Log.w(TAG, "Device may have issues with I2C read operations. Enabling fix.");
                    no_read = true;
                    break;
            }
        af9033_config = new Af9033Config[] {
                new Af9033Config(af9033_configdyn0_clk[0], af9033_configadc_multiplier[0], af9033_configtuner[0], af9033_configts_mode[0], af9033_configclock[0], af9033_configspec_inv[0]),
                new Af9033Config(af9033_configdyn0_clk[1], af9033_configadc_multiplier[1], af9033_configtuner[1], af9033_configts_mode[1], af9033_configclock[1], af9033_configspec_inv[1])
        };
        // init
        int frame_size = (USB_SPEED_FULL ? 5 : 87) * 188 / 4;
        int packet_size = (USB_SPEED_FULL ? 64 : 512) / 4;
        int[][] tab = Af9035Data.reg_val_mask_tab(frame_size, packet_size, dual_mode);
        /* init endpoints */
        for (int[] aTab : tab) {
            wr_reg_mask(aTab[0], aTab[1], aTab[2]);
        }
    }
    @Override
    protected DvbFrontend frontendAttatch() throws DvbException {
        return new Af9033Frontend(resources, af9033_config[0], af9033_i2c_addr[0], i2CAdapter);
    }
    @Override
    protected DvbTuner tunerAttatch() throws DvbException {
        int role = dual_mode ? IT913X_ROLE_DUAL_MASTER : IT913X_ROLE_SINGLE;
        switch (af9033_config[0].tuner) {
            case AF9033_TUNER_IT9135_38:
            case AF9033_TUNER_IT9135_51:
            case AF9033_TUNER_IT9135_52:
                return new It913x(resources, ((Af9033Frontend) frontend).regMap, IT9133AX_TUNER, role);
            case AF9033_TUNER_IT9135_60:
            case AF9033_TUNER_IT9135_61:
            case AF9033_TUNER_IT9135_62:
                return new It913x(resources, ((Af9033Frontend) frontend).regMap, IT9133BX_TUNER, role);
            default:
                throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
        }
    }
    @Override
    protected synchronized void init() throws DvbException {
    }
    @Override
    protected AlternateUsbInterface getUsbInterface() {
        return AlternateUsbInterface.forUsbInterface(usbDeviceConnection, iface).get(0);
    }
    @Override
    protected UsbEndpoint getUsbEndpoint() {
        return endpoint;
    }
    // communication
    private final static int MAX_XFER_SIZE = 64;
    private final static int BUF_LEN = 64;
    private final static int REQ_HDR_LEN = 4;
    private final static int ACK_HDR_LEN = 3;
    private final static int CHECKSUM_LEN = 2;
    private final Object usbLock = new Object();
    private final byte[] sbuf = new byte[BUF_LEN];
    private int seq = 0;
    private int checksum(byte[] buf, int len) {
        int checksum = 0;
        for (int i = 1; i < len; i++) {
            if ((i % 2) != 0) {
                checksum += (buf[i] & 0xFF) << 8;
            } else {
                checksum += (buf[i] & 0xFF);
            }
            // simulate 16-bit overflow
            if (checksum > 0xFFFF) {
                checksum -= 0x10000;
            }
        }
        checksum = ~checksum;
        return checksum & 0xFFFF;
    }
    private void ctrlMsg(int cmd, int mbox, int o_wlen, byte[] wbuf, int o_rlen, byte[] rbuf) throws DvbException {
	    /* buffer overflow check */
        if (o_wlen > (BUF_LEN - REQ_HDR_LEN - CHECKSUM_LEN) || o_rlen > (BUF_LEN - ACK_HDR_LEN - CHECKSUM_LEN)) {
            Log.e(TAG, "too much data wlen=" + o_wlen + " rlen=" + o_rlen);
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        }
        synchronized (sbuf) {
            sbuf[0] = (byte) (REQ_HDR_LEN + o_wlen + CHECKSUM_LEN - 1);
            sbuf[1] = (byte) mbox;
            sbuf[2] = (byte) cmd;
            sbuf[3] = (byte) seq++;
            // simulate 8-bit overflow
            if (seq > 0xFF) seq -= 0x100;
            if (o_wlen > 0) {
                System.arraycopy(wbuf, 0, sbuf, REQ_HDR_LEN, o_wlen);
            }
            int wlen = REQ_HDR_LEN + o_wlen + CHECKSUM_LEN;
            int rlen = ACK_HDR_LEN + o_rlen + CHECKSUM_LEN;
    	    /* calc and add checksum */
            int checksum = checksum(sbuf, (sbuf[0] & 0xFF) - 1);
            sbuf[(sbuf[0] & 0xFF) - 1] = (byte) (checksum >> 8);
            sbuf[sbuf[0] & 0xFF] = (byte) (checksum & 0xFF);
	        /* no ack for these packets */
            if (cmd == CMD_FW_DL) {
                rlen = 0;
            }
            dvb_usb_generic_rw(sbuf, wlen, sbuf, rlen);
	        /* no ack for those packets */
            if (cmd == CMD_FW_DL) return;
	        /* verify checksum */
            checksum = checksum(sbuf, rlen - 2);
            int tmp_checksum = ((sbuf[rlen - 2] & 0xFF) << 8) | (sbuf[rlen - 1] & 0xFF);
            if (tmp_checksum != checksum) {
                Log.e(TAG, "command=0x" + Integer.toHexString(cmd) + " checksum mismatch (0x" + Integer.toHexString(tmp_checksum) + " != 0x" + Integer.toHexString(checksum) + ")");
                throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_send_control_message_checksum));
            }
	        /* check status */
            if (sbuf[2] != 0) {
                Log.e(TAG, "command=0x" + Integer.toHexString(cmd) + " failed fw error=" + (sbuf[2] & 0xFF));
                throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_send_control_message, sbuf[2] & 0xFF));
            }
	        /* read request, copy returned data to return buf */
            if (o_rlen > 0) {
                System.arraycopy(sbuf, ACK_HDR_LEN, rbuf, 0, o_rlen);
            }
        }
    }
    /* write multiple registers */
    private void wr_regs(int reg, byte[] val, int len) throws DvbException {
        if (6 + len > MAX_XFER_SIZE) {
            Log.e(TAG, "i2c wr: len=" + len + " is too big!");
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        }
        byte[] wbuf = new byte[6 + len];
        wbuf[0] = (byte) len;
        wbuf[1] = 2;
        wbuf[2] = 0;
        wbuf[3] = 0;
        wbuf[4] = (byte) (reg >> 8);
        wbuf[5] = (byte) reg;
        System.arraycopy(val, 0, wbuf, 6, len);
        int mbox = (reg >> 16) & 0xff;
        ctrlMsg(CMD_MEM_WR, mbox, 6 + len, wbuf, 0, null);
    }
    /* read multiple registers */
    private void rd_regs(int reg, byte[] val, int len) throws DvbException {
        byte[] wbuf = new byte[]{(byte) len, 2, 0, 0, (byte) (reg >> 8), (byte) reg};
        int mbox = (reg >> 16) & 0xff;
        ctrlMsg(CMD_MEM_RD, mbox, wbuf.length, wbuf, len, val);
    }
    /* write single register */
    private void wr_reg(int reg, int val) throws DvbException {
        wr_regs(reg, new byte[]{(byte) val}, 1);
    }
    /* read single register */
    private int rd_reg(int reg) throws DvbException {
        byte[] res = new byte[1];
        rd_regs(reg, res, 1);
        return res[0] & 0xFF;
    }
    /* write single register with mask */
    private void wr_reg_mask(int reg, int val, int mask) throws DvbException {
        /* no need for read if whole reg is written */
        if (mask != 0xff) {
            int tmp = rd_reg(reg);
            val &= mask;
            tmp &= ~mask;
            val |= tmp;
        }
        wr_reg(reg, val);
    }
    // I2C
    private static boolean AF9035_IS_I2C_XFER_WRITE_READ(I2cAdapter.I2cMessage[] _msg) {
        return _msg.length == 2 && ((_msg[0].flags & I2C_M_RD) == 0) && ((_msg[1].flags & I2C_M_RD) != 0);
    }
    private static boolean AF9035_IS_I2C_XFER_WRITE(I2cAdapter.I2cMessage[] _msg) {
        return _msg.length == 1 && ((_msg[0].flags & I2C_M_RD) == 0);
    }
    private static boolean AF9035_IS_I2C_XFER_READ(I2cAdapter.I2cMessage[] _msg) {
        return _msg.length == 1 && ((_msg[0].flags & I2C_M_RD) != 0);
    }
    private class Af9035I2cAdapter extends I2cAdapter {
        @Override
        protected int masterXfer(I2cMessage[] msg) throws DvbException {
            /*
             * AF9035 I2C sub header is 5 bytes long. Meaning of those bytes are:
             * 0: data len
             * 1: I2C addr << 1
             * 2: reg addr len
             *    byte 3 and 4 can be used as reg addr
             * 3: reg addr MSB
             *    used when reg addr len is set to 2
             * 4: reg addr LSB
             *    used when reg addr len is set to 1 or 2
             *
             * For the simplify we do not use register addr at all.
             * NOTE: As a firmware knows tuner type there is very small possibility
             * there could be some tuner I2C hacks done by firmware and this may
             * lead problems if firmware expects those bytes are used.
             *
             * TODO: Here is few hacks. AF9035 chip integrates AF9033 demodulator.
             * IT9135 chip integrates AF9033 demodulator and RF tuner. For dual
             * tuner devices, there is also external AF9033 demodulator connected
             * via external I2C bus. All AF9033 demod I2C traffic, both single and
             * dual tuner configuration, is covered by firmware - actual USB IO
             * looks just like a memory access.
             * In case of IT913x chip, there is own tuner driver. It is implemented
             * currently as a I2C driver, even tuner IP block is likely build
             * directly into the demodulator memory space and there is no own I2C
             * bus. I2C subsystem does not allow register multiple devices to same
             * bus, having same slave address. Due to that we reuse demod address,
             * shifted by one bit, on that case.
             *
             * For IT930x we use a different command and the sub header is
             * different as well:
             * 0: data len
             * 1: I2C bus (0x03 seems to be only value used)
             * 2: I2C addr << 1
             */
            if (AF9035_IS_I2C_XFER_WRITE_READ(msg)) {
                if (msg[0].len > 40 || msg[1].len > 40) {
			        /* TODO: correct limits > 40 */
                    throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
                } else if ((msg[0].addr == af9033_i2c_addr[0]) || (msg[0].addr == af9033_i2c_addr[1])){
			        /* demod access via firmware interface */
                    int reg = ((msg[0].buf[0] & 0xFF) << 16) | ((msg[0].buf[1] & 0xFF) << 8) | (msg[0].buf[2] & 0xFF);
                    if (msg[0].addr == af9033_i2c_addr[1]) {
                        reg |= 0x100000;
                    }
                    rd_regs(reg, msg[1].buf, msg[1].len);
                } else if (no_read) {
                    for (int i = 0; i < msg[1].len; i++) msg[1].buf[i] = 0;
                    return 0;
                } else {
			        /* I2C write + read */
                    byte[] buf = new byte[ MAX_XFER_SIZE];
                    int cmd = CMD_I2C_RD;
                    int wlen = 5 + msg[0].len;
                    if (chip_type == 0x9306) {
                        cmd = CMD_GENERIC_I2C_RD;
                        wlen = 3 + msg[0].len;
                    }
                    int mbox = ((msg[0].addr & 0x80) >> 3);
                    buf[0] = (byte) msg[1].len;
                    if (chip_type == 0x9306) {
                        buf[1] = 0x03; /* I2C bus */
                        buf[2] = (byte) (msg[0].addr << 1);
                        System.arraycopy(msg[0].buf, 0, buf, 3, msg[0].len);
                    } else {
                        buf[1] = (byte) (msg[0].addr << 1);
                        buf[3] = 0x00; /* reg addr MSB */
                        buf[4] = 0x00; /* reg addr LSB */
				        /* Keep prev behavior for write req len > 2*/
                        if (msg[0].len > 2) {
                            buf[2] = 0x00; /* reg addr len */
                            System.arraycopy(msg[0].buf, 0, buf, 5, msg[0].len);
				            /* Use reg addr fields if write req len <= 2 */
                        } else {
                            wlen = 5;
                            buf[2] = (byte) msg[0].len;
                            if (msg[0].len == 2) {
                                buf[3] = msg[0].buf[0];
                                buf[4] = msg[0].buf[1];
                            } else if (msg[0].len == 1) {
                                buf[4] = msg[0].buf[0];
                            }
                        }
                    }
                    ctrlMsg(cmd, mbox, wlen, buf, msg[1].len, msg[1].buf);
                }
            } else if (AF9035_IS_I2C_XFER_WRITE(msg)) {
                if (msg[0].len > 40) {
			        /* TODO: correct limits > 40 */
                    throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
                } else if ((msg[0].addr == af9033_i2c_addr[0]) || (msg[0].addr == af9033_i2c_addr[1])){
			        /* demod access via firmware interface */
                    int reg = ((msg[0].buf[0] & 0xFF) << 16) | ((msg[0].buf[1] & 0xFF) << 8) | (msg[0].buf[2] & 0xFF);
                    if (msg[0].addr == af9033_i2c_addr[1]) {
                        reg |= 0x100000;
                    }
                    byte[] tmp2 = new byte[msg[0].len - 3];
                    System.arraycopy(msg[0].buf, 3, tmp2, 0, tmp2.length);
                    wr_regs(reg, tmp2, tmp2.length);
                } else {
			        /* I2C write */
                    byte[] buf = new byte[ MAX_XFER_SIZE];
                    int cmd = CMD_I2C_WR;
                    int wlen = 5 + msg[0].len;
                    if (chip_type == 0x9306) {
                        cmd = CMD_GENERIC_I2C_WR;
                        wlen = 3 + msg[0].len;
                    }
                    int mbox = ((msg[0].addr & 0x80) >> 3);
                    buf[0] = (byte) msg[0].len;
                    if (chip_type == 0x9306) {
                        buf[1] = 0x03; /* I2C bus */
                        buf[2] = (byte) (msg[0].addr << 1);
                        System.arraycopy(msg[0].buf, 0, buf, 3, msg[0].len);
                    } else {
                        buf[1] = (byte) (msg[0].addr << 1);
                        buf[2] = 0x00; /* reg addr len */
                        buf[3] = 0x00; /* reg addr MSB */
                        buf[4] = 0x00; /* reg addr LSB */
                        System.arraycopy(msg[0].buf, 0, buf, 5, msg[0].len);
                    }
                    ctrlMsg(cmd, mbox, wlen, buf, 0, null);
                }
            } else if (AF9035_IS_I2C_XFER_READ(msg)) {
                if (msg[0].len > 40) {
			        /* TODO: correct limits > 40 */
                    throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
                } else if (no_read) {
                    for (int i = 0; i < msg[0].len; i++) msg[0].buf[i] = 0;
                    return 0;
                } else {
			        /* I2C read */
                    byte[] buf = new byte[5];
                    int cmd = CMD_I2C_RD;
                    int wlen = buf.length;
                    if (chip_type == 0x9306) {
                        cmd = CMD_GENERIC_I2C_RD;
                        wlen = 3;
                    }
                    int mbox = ((msg[0].addr & 0x80) >> 3);
                    buf[0] = (byte) msg[0].len;
                    if (chip_type == 0x9306) {
                        buf[1] = 0x03; /* I2C bus */
                        buf[2] = (byte) (msg[0].addr << 1);
                    } else {
                        buf[1] = (byte) (msg[0].addr << 1);
                        buf[2] = 0x00; /* reg addr len */
                        buf[3] = 0x00; /* reg addr MSB */
                        buf[4] = 0x00; /* reg addr LSB */
                    }
                    ctrlMsg(cmd, mbox, wlen, buf, msg[0].len, msg[0].buf);
                    return msg.length;
                }
            } else {
                /*
                 * We support only three kind of I2C transactions:
                 * 1) 1 x write + 1 x read (repeated start)
                 * 2) 1 x write
                 * 3) 1 x read
                 */
                throw new DvbException(BAD_API_USAGE, resources.getString(R.string.unsuported_i2c_operation));
            }
            return msg.length;
        }
    }
    @Override
    protected int getNumPacketsPerRequest() {
        return 87;
    }
    @Override
    protected int getNumRequests() {
        return 10;
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
package info.martinmarinov.drivers.usb.rtl28xx;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import java.util.Set;
import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDevice;
import info.martinmarinov.drivers.usb.DvbUsbIds;
import static info.martinmarinov.drivers.tools.SetUtils.setOf;
public class Rtl2xx2DvbDeviceCreator implements DvbUsbDevice.Creator {
    private final static Set<DeviceFilter> RTL2832_DEVICES = setOf(
            new DeviceFilter(DvbUsbIds.USB_VID_REALTEK, 0x2832, "Realtek RTL2832U reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_REALTEK, 0x2838, "Realtek RTL2832U reference design"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, DvbUsbIds.USB_PID_TERRATEC_CINERGY_T_STICK_BLACK_REV1, "TerraTec Cinergy T Stick Black"),
            new DeviceFilter(DvbUsbIds.USB_VID_GTEK, DvbUsbIds.USB_PID_DELOCK_USB2_DVBT, "G-Tek Electronics Group Lifeview LV5TDLX DVB-T"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, DvbUsbIds.USB_PID_NOXON_DAB_STICK, "TerraTec NOXON DAB Stick"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, DvbUsbIds.USB_PID_NOXON_DAB_STICK_REV2, "TerraTec NOXON DAB Stick (rev 2)"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, DvbUsbIds.USB_PID_NOXON_DAB_STICK_REV3, "TerraTec NOXON DAB Stick (rev 3)"),
            new DeviceFilter(DvbUsbIds.USB_VID_GTEK, DvbUsbIds.USB_PID_TREKSTOR_TERRES_2_0, "Trekstor DVB-T Stick Terres 2.0"),
            new DeviceFilter(DvbUsbIds.USB_VID_DEXATEK, 0x1101, "Dexatek DK DVB-T Dongle"),
            new DeviceFilter(DvbUsbIds.USB_VID_LEADTEK, 0x6680, "DigitalNow Quad DVB-T Receiver"),
            new DeviceFilter(DvbUsbIds.USB_VID_LEADTEK, DvbUsbIds.USB_PID_WINFAST_DTV_DONGLE_MINID, "Leadtek Winfast DTV Dongle Mini D"),
            new DeviceFilter(DvbUsbIds.USB_VID_LEADTEK, DvbUsbIds.USB_PID_WINFAST_DTV2000DS_PLUS, "Leadtek WinFast DTV2000DS Plus"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, 0x00d3, "TerraTec Cinergy T Stick RC (Rev. 3)"),
            new DeviceFilter(DvbUsbIds.USB_VID_DEXATEK, 0x1102, "Dexatek DK mini DVB-T Dongle"),
            new DeviceFilter(DvbUsbIds.USB_VID_TERRATEC, 0x00d7, "TerraTec Cinergy T Stick+"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, 0xd3a8, "ASUS My Cinema-U3100Mini Plus V2"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, 0xd393, "GIGABYTE U7300"),
            new DeviceFilter(DvbUsbIds.USB_VID_DEXATEK, 0x1104, "MSI DIGIVOX Micro HD"),
            new DeviceFilter(DvbUsbIds.USB_VID_COMPRO, 0x0620, "Compro VideoMate U620F"),
            new DeviceFilter(DvbUsbIds.USB_VID_COMPRO, 0x0650, "Compro VideoMate U650F"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, 0xd394, "MaxMedia HU394-T"),
            new DeviceFilter(DvbUsbIds.USB_VID_LEADTEK, 0x6a03, "Leadtek WinFast DTV Dongle mini"),
            new DeviceFilter(DvbUsbIds.USB_VID_GTEK, DvbUsbIds.USB_PID_CPYTO_REDI_PC50A, "Crypto ReDi PC 50 A"),
            new DeviceFilter(DvbUsbIds.USB_VID_KYE, 0x707f, "Genius TVGo DVB-T03"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, 0xd395, "Peak DVB-T USB"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_SVEON_STV20_RTL2832U, "Sveon STV20"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_SVEON_STV21, "Sveon STV21"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_SVEON_STV27, "Sveon STV27"),
            new DeviceFilter(DvbUsbIds.USB_VID_KWORLD_2, DvbUsbIds.USB_PID_TURBOX_DTT_2000, "TURBO-X Pure TV Tuner DTT-2000"),
            /* RTL2832P devices: */
            new DeviceFilter(DvbUsbIds.USB_VID_HANFTEK, 0x0131, "Astrometa DVB-T2"),
            new DeviceFilter(0x5654, 0xca42, "GoTView MasterHD 3")
    );
    @Override
    public Set<DeviceFilter> getSupportedDevices() {
        return RTL2832_DEVICES;
    }
    @Override
    public DvbUsbDevice create(UsbDevice usbDevice, Context context, DeviceFilter filter) throws DvbException {
        return new Rtl2832DvbDevice(usbDevice, context, filter);
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
package info.martinmarinov.drivers.tools;
import static android.app.PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.content.Context.RECEIVER_EXPORTED;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import java.util.concurrent.Future;
public class UsbPermissionObtainer {
    private static final String TAG = UsbPermissionObtainer.class.getSimpleName();
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    public static Future<UsbDeviceConnection> obtainFdFor(Context context, UsbDevice usbDevice) {
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = FLAG_MUTABLE | FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = FLAG_MUTABLE;
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!manager.hasPermission(usbDevice)) {
            AsyncFuture<UsbDeviceConnection> task = new AsyncFuture<>();
            registerNewBroadcastReceiver(context, usbDevice, task);
            manager.requestPermission(usbDevice, PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags));
            return task;
        } else {
            return new CompletedFuture<>(manager.openDevice(usbDevice));
        }
    }
    private static void registerNewBroadcastReceiver(final Context context, final UsbDevice usbDevice, final AsyncFuture<UsbDeviceConnection> task) {
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        if (task.isDone()) {
                            Log.d(TAG, "Permission already should be processed, ignoring.");
                            return;
                        }
                        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (device.equals(usbDevice)) {
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (!manager.hasPermission(device)) {
                                    Log.d(TAG, "Permissions were granted but can't access the device");
                                    task.setDone(null);
                                } else {
                                    Log.d(TAG, "Permissions granted and device is accessible");
                                    task.setDone(manager.openDevice(device));
                                }
                            } else {
                                Log.d(TAG, "Extra permission was not granted");
                                task.setDone(null);
                            }
                            context.unregisterReceiver(this);
                        } else {
                            Log.d(TAG, "Got a permission for an unexpected device");
                            task.setDone(null);
                        }
                    }
                } else {
                    Log.d(TAG, "Unexpected action");
                    task.setDone(null);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, new IntentFilter(ACTION_USB_PERMISSION), RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(broadcastReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        }
    }
    private UsbPermissionObtainer() {}
}