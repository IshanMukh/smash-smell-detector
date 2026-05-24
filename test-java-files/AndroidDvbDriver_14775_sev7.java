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
import static java.util.Collections.emptySet;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.tools.SetUtils.setOf;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName.DVBT_SOFT_RST;
import android.content.res.Resources;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.Check;
import info.martinmarinov.drivers.tools.DvbMath;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.RegValue;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxDvbDevice.Rtl28xxI2cAdapter;
class Rtl2832Frontend implements DvbFrontend {
    private final static String TAG = Rtl2832Frontend.class.getSimpleName();
    private final static int I2C_ADDRESS = 0x10;
    private final static long XTAL = 28_800_000L;
    private final Rtl28xxTunerType tunerType;
    private final Rtl28xxI2cAdapter i2cAdapter;
    private final Resources resources;
    private DvbTuner tuner;
    Rtl2832Frontend(Rtl28xxTunerType tunerType, Rtl28xxI2cAdapter i2cAdapter, Resources resources) {
        this.tunerType = tunerType;
        this.i2cAdapter = i2cAdapter;
        this.resources = resources;
    }
    @Override
    public DvbCapabilities getCapabilities() {
        return Rtl2832FrontendData.CAPABILITIES;
    }
    private void wr(int reg, byte[] val) throws DvbException {
        wr(reg, val, val.length);
    }
    private synchronized void wr(int reg, byte[] val, int length) throws DvbException {
        byte[] buf = new byte[length + 1];
        System.arraycopy(val, 0, buf, 1, length);
        buf[0] = (byte) reg;
        i2cAdapter.transfer(I2C_ADDRESS, 0, buf);
    }
    synchronized void wr(int reg, int page, byte[] val) throws DvbException {
        wr(reg, page, val, val.length);
    }
    private synchronized void wr(int reg, int page, int val) throws DvbException {
        wr(reg, page, new byte[] {(byte) val});
    }
    private synchronized void wr(int reg, int page, byte[] val, int length) throws DvbException {
        if (page != i2cAdapter.page) {
            wr(0x00, new byte[] {(byte) page});
            i2cAdapter.page = page;
        }
        wr(reg, val, length);
    }
    private synchronized void wrMask(int reg, int page, int mask, int val) throws DvbException {
        int orig = rd(reg, page);
        int tmp = (orig & ~mask) | (val & mask);
        wr(reg, page, new byte[] {(byte) tmp});
    }
    private static int calcBit(int val) {
        return 1 << val;
    }
    private static int calcRegMask(int val) {
        return calcBit(val + 1) - 1;
    }
    synchronized void wrDemodReg(DvbtRegBitName reg, long val) throws DvbException {
        int len = (reg.msb >> 3) + 1;
        byte[] reading = new byte[len];
        byte[] writing = new byte[len];
        int mask = calcRegMask(reg.msb - reg.lsb);
        rd(reg.startAddress, reg.page, reading);
        int readingTmp = 0;
        for (int i = 0; i < len; i++) {
            readingTmp |= (reading[i] & 0xFF) << ((len - 1 - i) * 8);
        }
        int writingTmp = readingTmp & ~(mask << reg.lsb);
        writingTmp |= ((val & mask) << reg.lsb);
        for (int i = 0; i < len; i++) {
            writing[i] = (byte) (writingTmp >> ((len - 1 - i) * 8));
        }
        wr(reg.startAddress, reg.page, writing);
    }
    private synchronized void wrDemodRegs(RegValue[] values) throws DvbException {
        for (RegValue regValue : values) wrDemodReg(regValue.reg, regValue.val);
    }
    private synchronized void rd(int reg, byte[] val) throws DvbException {
        i2cAdapter.transfer(
                I2C_ADDRESS, 0, new byte[] {(byte) reg},
                I2C_ADDRESS, I2C_M_RD, val
        );
    }
    private synchronized void rd(int reg, int page, byte[] val) throws DvbException {
        if (page != i2cAdapter.page) {
            wr(0x00, new byte[] {(byte) page});
            i2cAdapter.page = page;
        }
        rd(reg, val);
    }
    private synchronized int rd(int reg, int page) throws DvbException {
        byte[] result = new byte[1];
        rd(reg, page, result);
        return result[0] & 0xFF;
    }
    private synchronized long rdDemodReg(DvbtRegBitName reg) throws DvbException {
        int len = (reg.msb >> 3) + 1;
        byte[] reading = new byte[len];
        int mask = calcRegMask(reg.msb - reg.lsb);
        rd(reg.startAddress, reg.page, reading);
        long readingTmp = 0;
        for (int i = 0; i < len; i++) {
            readingTmp |= ((long) (reading[i] & 0xFF)) << ((len - 1 - i) * 8);
        }
        return (readingTmp >> reg.lsb) & mask;
    }
    private void setIf(long if_freq) throws DvbException {
        int en_bbin = (if_freq == 0 ? 0x1 : 0x0);
	    /*
	     * PSET_IFFREQ = - floor((IfFreqHz % CrystalFreqHz) * pow(2, 22)
	     *		/ CrystalFreqHz)
	    */
        long pset_iffreq = if_freq % XTAL;
        pset_iffreq *= 0x400000;
        pset_iffreq = DvbMath.divU64(pset_iffreq, XTAL);
        pset_iffreq = -pset_iffreq;
        pset_iffreq = pset_iffreq & 0x3fffff;
        wrDemodReg(DvbtRegBitName.DVBT_EN_BBIN, en_bbin);
        wrDemodReg(DvbtRegBitName.DVBT_PSET_IFFREQ, pset_iffreq);
    }
    @Override
    public synchronized void attach() throws DvbException {
        /* check if the demod is there */
        rd(0, 0);
        wrDemodReg(DVBT_SOFT_RST, 0x1);
    }
    @Override
    public synchronized void release() {
        try {
            wrDemodReg(DVBT_SOFT_RST, 0x1);
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        unsetSdrMode();
        wrDemodReg(DVBT_SOFT_RST, 0x0);
        wrDemodRegs(Rtl2832FrontendData.INITIAL_REGS);
        switch (tunerType) {
            case RTL2832_E4000:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_E4000);
                break;
            case RTL2832_R820T:
            case RTL2832_R828D:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_R820T);
                break;
            case RTL2832_FC0012:
            case RTL2832_FC0013:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_FC0012);
                break;
            default:
                throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
        }
        // Skipping IF since no tuners support IF from what I can see
        /*
	    * r820t NIM code does a software reset here at the demod -
	    * may not be needed, as there's already a software reset at set_params()
	    */
        wrDemodReg(DVBT_SOFT_RST, 0x1);
        wrDemodReg(DVBT_SOFT_RST, 0x0);
        tuner.init();
    }
    private void unsetSdrMode() throws DvbException {
        /* PID filter */
        wr(0x61, 0, 0xe0);
	    /* mode */
        wr(0x19, 0, 0x20);
        wr(0x17, 0, new byte[] {(byte) 0x11, (byte) 0x10});
	    /* FSM */
        wr(0x92, 1, new byte[] {(byte) 0x00, (byte) 0x0f, (byte) 0xff});
        wr(0x3e, 1, new byte[] {(byte) 0x40, (byte) 0x00});
        wr(0x15, 1, new byte[] {(byte) 0x06, (byte) 0x3f, (byte) 0xce, (byte) 0xcc});
    }
    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        Check.notNull(tuner);
        if (deliverySystem != DeliverySystem.DVBT) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        tuner.setParams(frequency, bandwidthHz, deliverySystem);
        setIf(tuner.getIfFrequency());
        int i;
        long bwMode;
        switch ((int) bandwidthHz) {
            case 6000000:
                i = 0;
                bwMode = 48000000;
                break;
            case 7000000:
                i = 1;
                bwMode = 56000000;
                break;
            case 8000000:
                i = 2;
                bwMode = 64000000;
                break;
            default:
                throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        }
        byte[] byteToSend = new byte[1];
        for (int j = 0; j < Rtl2832FrontendData.BW_PARAMS[0].length; j++) {
            byteToSend[0] = Rtl2832FrontendData.BW_PARAMS[i][j];
            wr(0x1c+j, 1, byteToSend);
        }
        /* calculate and set resample ratio
	    * RSAMP_RATIO = floor(CrystalFreqHz * 7 * pow(2, 22)
	    *	/ ConstWithBandwidthMode)
	    */
        long num = XTAL * 7;
        num *= 0x400000L;
        num = DvbMath.divU64(num, bwMode);
        long resampRatio =  num & 0x3ffffff;
        wrDemodReg(DvbtRegBitName.DVBT_RSAMP_RATIO, resampRatio);
	    /* calculate and set cfreq off ratio
	     * CFREQ_OFF_RATIO = - floor(ConstWithBandwidthMode * pow(2, 20)
	     *	/ (CrystalFreqHz * 7))
	    */
        num = bwMode << 20;
        long num2 = XTAL * 7;
        num = DvbMath.divU64(num, num2);
        num = -num;
        long cfreqOffRatio = num & 0xfffff;
        wrDemodReg(DvbtRegBitName.DVBT_CFREQ_OFF_RATIO, cfreqOffRatio);
	    /* soft reset */
        wrDemodReg(DVBT_SOFT_RST, 0x1);
        wrDemodReg(DVBT_SOFT_RST, 0x0);
    }
    @Override
    public synchronized int readSnr() throws DvbException {
	    /* reports SNR in resolution of 0.1 dB */
        int tmp = rd(0x3c, 3);
        int constellation = (tmp >> 2) & 0x03; /* [3:2] */
        if (constellation >= Rtl2832FrontendData.CONSTELLATION_NUM) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_read_snr));
        int hierarchy = (tmp >> 4) & 0x07; /* [6:4] */
        if (hierarchy >= Rtl2832FrontendData.HIERARCHY_NUM) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_read_snr));
        byte[] buf = new byte[2];
        rd(0x0c, 4, buf);
        int tmp16 = (buf[0] & 0xFF) << 8 | (buf[1] & 0xFF);
        if (tmp16 == 0) return 0;
        return (Rtl2832FrontendData.SNR_CONSTANTS[constellation][hierarchy] - DvbMath.intlog10(tmp16)) / ((1 << 24) / 100);
    }
    @Override
    public synchronized int readRfStrengthPercentage() throws DvbException {
        long tmp = rdDemodReg(DvbtRegBitName.DVBT_FSM_STAGE);
        if (tmp == 10 || tmp == 11) {
            // If it has signal
            int u8tmp = rd(0x05, 3);
            u8tmp = (~u8tmp) & 0xFF;
            int strength = u8tmp << 8 | u8tmp;
            return (100 * strength) / 0xffff;
        } else {
            return 0;
        }
    }
    @Override
    public synchronized int readBer() throws DvbException {
        byte[] buf = new byte[2];
        rd(0x4e, 3, buf);
        // Default unit is bit error per 1MB
        return (buf[0] & 0xFF) << 8 | (buf[1] & 0xFF);
    }
    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        long tmp = rdDemodReg(DvbtRegBitName.DVBT_FSM_STAGE);
        if (tmp == 11) {
            return setOf(DvbStatus.FE_HAS_SIGNAL, DvbStatus.FE_HAS_CARRIER,
                    DvbStatus.FE_HAS_VITERBI, DvbStatus.FE_HAS_SYNC, DvbStatus.FE_HAS_LOCK);
        } else if (tmp == 10) {
            return setOf(DvbStatus.FE_HAS_SIGNAL, DvbStatus.FE_HAS_CARRIER,
                    DvbStatus.FE_HAS_VITERBI);
        }
        return emptySet();
    }
    @Override
    public synchronized void setPids(int... pids) throws DvbException {
        setPids(false, pids);
    }
    @Override
    public synchronized void disablePidFilter() throws DvbException {
        disablePidFilter(false);
    }
    void setPids(boolean slaveTs, int ... pids) throws DvbException {
        if (!hardwareSupportsPidFilterOf(pids)) {
            // if can't do hardware filtering, fallback to software
            Log.d(TAG, "Falling back to software PID filtering");
            disablePidFilter(slaveTs);
            return;
        }
        enablePidFilter(slaveTs);
        long pidFilter = 0;
        for (int index = 0; index < pids.length; index++) {
            pidFilter |= 1 << index;
        }
        // write mask
        byte[] buf = new byte[] {
                (byte) (pidFilter & 0xFF),
                (byte) ((pidFilter >> 8) & 0xFF),
                (byte) ((pidFilter >> 16) & 0xFF),
                (byte) ((pidFilter >> 24) & 0xFF)
        };
        if (slaveTs) {
            wr(0x22, 0, buf);
        } else {
            wr(0x62, 0, buf);
        }
        for (int index = 0; index < pids.length; index++) {
            int pid = pids[index];
            buf[0] = (byte) ((pid >> 8) & 0xFF);
            buf[1] = (byte) (pid & 0xFF);
            if (slaveTs) {
                wr(0x26 + 2 * index, 0, buf, 2);
            } else {
                wr(0x66 + 2 * index, 0, buf, 2);
            }
        }
    }
    void disablePidFilter(boolean slaveTs) throws DvbException {
        if (slaveTs) {
            wrMask(0x21, 0, 0xc0, 0xc0);
        } else {
            wrMask(0x61, 0, 0xc0, 0xc0);
        }
    }
    private void enablePidFilter(boolean slaveTs) throws DvbException {
        if (slaveTs) {
            wrMask(0x21, 0, 0xc0, 0x80);
        } else {
            wrMask(0x61, 0, 0xc0, 0x80);
        }
    }
    private static boolean hardwareSupportsPidFilterOf(int ... pids) {
        if (pids.length > 32) {
            return false;
        }
        // Avoid unnecessary unpacking, ignore Android Studio warning
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < pids.length; i++) {
            if (pids[i] < 0 || pids[i] > 0x1FFF) return false;
        }
        return true;
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
import info.martinmarinov.drivers.DvbException;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_TEN;
public abstract class I2cAdapter {
    private final Object lock = new Object();
    private final static int RETRIES = 10;
    public void transfer(int addr, int flags, byte[] buf) throws DvbException {
       transfer(addr, flags, buf, buf.length);
    }
    public void transfer(int addr, int flags, byte[] buf, int len) throws DvbException {
        transfer(new I2cMessage(addr, flags, buf, len));
    }
    public void transfer(int addr1, int flags1, byte[] buf1,
                         int addr2, int flags2, byte[] buf2) throws DvbException {
        transfer(addr1, flags1, buf1, buf1.length,
                addr2, flags2, buf2, buf2.length);
    }
    public void transfer(int addr1, int flags1, byte[] buf1, int len1,
                         int addr2, int flags2, byte[] buf2, int len2) throws DvbException {
        transfer(new I2cMessage(addr1, flags1, buf1, len1),
                new I2cMessage(addr2, flags2, buf2, len2));
    }
    public void send(int addr, byte[] buf, int count) throws DvbException {
        transfer(addr, I2C_M_TEN, buf, count);
    }
    public void recv(int addr, byte[] buf, int count) throws DvbException {
        transfer(addr, I2C_M_TEN | I2C_M_RD, buf, count);
    }
    private void transfer(I2cMessage ... messages) throws DvbException {
        synchronized (lock) {
            for (int i = 0; i < RETRIES; i++) {
                try {
                    if (masterXfer(messages) == messages.length) {
                        return;
                    }
                } catch (DvbException e) {
                    if (i == RETRIES - 1) throw e;
                }
            }
        }
    }
    protected abstract int masterXfer(I2cMessage[] messages) throws DvbException;
    public class I2cMessage {
        public static final int I2C_M_TEN		= 0x0010	/* this is a ten bit chip address */;
        public static final int I2C_M_RD		= 0x0001	/* read data, from slave to master */;
        public static final int I2C_M_STOP		= 0x8000	/* if I2C_FUNC_PROTOCOL_MANGLING */;
        public static final int I2C_M_NOSTART		= 0x4000	/* if I2C_FUNC_NOSTART */;
        public static final int I2C_M_REV_DIR_ADDR	= 0x2000	/* if I2C_FUNC_PROTOCOL_MANGLING */;
        public static final int I2C_M_IGNORE_NAK	= 0x1000	/* if I2C_FUNC_PROTOCOL_MANGLING */;
        public static final int I2C_M_NO_RD_ACK		= 0x0800	/* if I2C_FUNC_PROTOCOL_MANGLING */;
        public static final int I2C_M_RECV_LEN		= 0x0400	/* length will be first received byte */;
        // These are 16 bit integers, however using int in Java
        // since Java doesn't have 16 bit unsigned type
        public final int addr;
        public final int flags;
        public final byte[] buf;
        public final int len;
        I2cMessage(int addr, int flags, byte[] buf, int len) {
            this.addr = addr;
            this.flags = flags;
            this.buf = buf;
            this.len = len;
        }
    }
    public static abstract class I2GateControl {
        protected abstract void i2cGateCtrl(boolean enable) throws DvbException;
        public synchronized void runInOpenGate(ThrowingRunnable<DvbException> r) throws DvbException {
            try {
                i2cGateCtrl(true);
                r.run();
            } finally {
                i2cGateCtrl(false);
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
package info.martinmarinov.drivers.usb.rtl28xx;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.usb.DvbTuner;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
class FC0013Tuner implements DvbTuner {
    private final static boolean DUAL_MASTER = false;
    private final int i2cAddr;
    private final Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter;
    private final long xtal;
    private final I2cAdapter.I2GateControl i2GateControl;
    FC0013Tuner(int i2cAddr, Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, long xtal, I2cAdapter.I2GateControl i2GateControl) {
        this.i2cAddr = i2cAddr;
        this.i2cAdapter = i2cAdapter;
        this.xtal = xtal;
        this.i2GateControl = i2GateControl;
    }
    private void wr(int reg, int val) throws DvbException {
        i2cAdapter.transfer(i2cAddr, 0, new byte[] { (byte) reg, (byte) val });
    }
    private int rd(int reg) throws DvbException {
        byte[] response = new byte[1];
        i2cAdapter.transfer(
                i2cAddr, 0, new byte[] { (byte) reg },
                i2cAddr, I2C_M_RD, response
        );
        return response[0] & 0xFF;
    }
    @Override
    public void attatch() throws DvbException {
        // no-op
    }
    @Override
    public void release() {
        // no-op
    }
    @Override
    public void init() throws DvbException {
        final int[] reg = new int[] {
                0x00,	/* reg. 0x00: dummy */
                0x09,	/* reg. 0x01 */
                0x16,	/* reg. 0x02 */
                0x00,	/* reg. 0x03 */
                0x00,	/* reg. 0x04 */
                0x17,	/* reg. 0x05 */
                0x02,	/* reg. 0x06 */
                0x0a,	/* reg. 0x07: CHECK */
                0xff,	/* reg. 0x08: AGC Clock divide by 256, AGC gain 1/256,
			                    Loop Bw 1/8 */
                0x6f,	/* reg. 0x09: enable LoopThrough */
                0xb8,	/* reg. 0x0a: Disable LO Test Buffer */
                0x82,	/* reg. 0x0b: CHECK */
                0xfc,	/* reg. 0x0c: depending on AGC Up-Down mode, may need 0xf8 */
                0x01,	/* reg. 0x0d: AGC Not Forcing & LNA Forcing, may need 0x02 */
                0x00,	/* reg. 0x0e */
                0x00,	/* reg. 0x0f */
                0x00,	/* reg. 0x10 */
                0x00,	/* reg. 0x11 */
                0x00,	/* reg. 0x12 */
                0x00,	/* reg. 0x13 */
                0x50,	/* reg. 0x14: DVB-t High Gain, UHF.
			                    Middle Gain: 0x48, Low Gain: 0x40 */
                0x01,	/* reg. 0x15 */
        };
        if (xtal == 27_000_000L || xtal == 28_800_000L) {
            reg[0x07] |= 0x20;
        }
        if (DUAL_MASTER) {
            reg[0x0c] |= 0x02;
        }
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                for (int i = 1; i < reg.length; i++) {
                    wr(i, reg[i]);
                }
            }
        });
    }
    @Override
    public void setParams(long frequency, final long bandwidthHz, DeliverySystem ignored) throws DvbException {
        final long freq = frequency / 1_000L;
        final int xtalFreqKhz2;
        switch ((int) xtal) {
            case 27_000_000:
                xtalFreqKhz2 = 27000 / 2;
                break;
            case 36_000_000:
                xtalFreqKhz2 = 36000 / 2;
                break;
            case 28_800_000:
            default:
                xtalFreqKhz2 = 28800 / 2;
                break;
        }
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                setVhfTrack(freq);
                if (freq < 300000) {
		            /* enable VHF filter */
                    int tmp = rd(0x07);
                    wr(0x07, tmp | 0x10);
		            /* disable UHF & disable GPS */
                    tmp = rd(0x14);
                    wr(0x14, tmp & 0x1f);
                } else if (freq <= 862000) {
		            /* disable VHF filter */
                    int tmp = rd(0x07);
                    wr(0x07, tmp & 0xef);
		            /* enable UHF & disable GPS */
                    tmp = rd(0x14);
                    wr(0x14, (tmp & 0x1f) | 0x40);
                } else {
		            /* disable VHF filter */
                    int tmp = rd(0x07);
                    wr(0x07, tmp & 0xef);
		            /* disable UHF & enable GPS */
                    tmp = rd(0x14);
                    wr(0x14, (tmp & 0x1f) | 0x20);
                }
                int[] reg = new int[7];
                int multi;
	        /* select frequency divider and the frequency of VCO */
                if (freq < 37084) {		/* freq * 96 < 3560000 */
                    multi = 96;
                    reg[5] = 0x82;
                    reg[6] = 0x00;
                } else if (freq < 55625) {	/* freq * 64 < 3560000 */
                    multi = 64;
                    reg[5] = 0x02;
                    reg[6] = 0x02;
                } else if (freq < 74167) {	/* freq * 48 < 3560000 */
                    multi = 48;
                    reg[5] = 0x42;
                    reg[6] = 0x00;
                } else if (freq < 111250) {	/* freq * 32 < 3560000 */
                    multi = 32;
                    reg[5] = 0x82;
                    reg[6] = 0x02;
                } else if (freq < 148334) {	/* freq * 24 < 3560000 */
                    multi = 24;
                    reg[5] = 0x22;
                    reg[6] = 0x00;
                } else if (freq < 222500) {	/* freq * 16 < 3560000 */
                    multi = 16;
                    reg[5] = 0x42;
                    reg[6] = 0x02;
                } else if (freq < 296667) {	/* freq * 12 < 3560000 */
                    multi = 12;
                    reg[5] = 0x12;
                    reg[6] = 0x00;
                } else if (freq < 445000) {	/* freq * 8 < 3560000 */
                    multi = 8;
                    reg[5] = 0x22;
                    reg[6] = 0x02;
                } else if (freq < 593334) {	/* freq * 6 < 3560000 */
                    multi = 6;
                    reg[5] = 0x0a;
                    reg[6] = 0x00;
                } else if (freq < 950000) {	/* freq * 4 < 3800000 */
                    multi = 4;
                    reg[5] = 0x12;
                    reg[6] = 0x02;
                } else {
                    multi = 2;
                    reg[5] = 0x0a;
                    reg[6] = 0x02;
                }
                long f_vco = freq * multi;
                boolean vco_select = false;
                if (f_vco >= 3_060_000L) {
                    reg[6] |= 0x08;
                    vco_select = true;
                }
                if (freq >= 45_000L) {
		        /* From divided value (XDIV) determined the FA and FP value */
                    long xdiv = (f_vco / xtalFreqKhz2);
                    if ((f_vco - xdiv * xtalFreqKhz2) >= (xtalFreqKhz2 / 2)) {
                        xdiv++;
                    }
                    int pm = (int) (xdiv / 8);
                    int am = (int) (xdiv - (8 * pm));
                    if (am < 2) {
                        reg[1] = am + 8;
                        reg[2] = pm - 1;
                    } else {
                        reg[1] = am;
                        reg[2] = pm;
                    }
                } else {
		        /* fix for frequency less than 45 MHz */
                    reg[1] = 0x06;
                    reg[2] = 0x11;
                }
	            /* fix clock out */
                reg[6] |= 0x20;
	            /* From VCO frequency determines the XIN ( fractional part of Delta
	            Sigma PLL) and divided value (XDIV) */
                int xin = (int) (f_vco - (f_vco / xtalFreqKhz2) * xtalFreqKhz2);
                xin = (xin << 15) / xtalFreqKhz2;
                if (xin >= 16384) {
                    xin += 32768;
                }
                reg[3] = xin >> 8;
                reg[4] = xin & 0xff;
                reg[6] &= 0x3f; /* bits 6 and 7 describe the bandwidth */
                switch ((int) bandwidthHz) {
                    case 6000000:
                        reg[6] |= 0x80;
                        break;
                    case 7000000:
                        reg[6] |= 0x40;
                        break;
                    case 8000000:
                    default:
                        break;
                }
	            /* modified for Realtek demod */
                reg[5] |= 0x07;
                for (int i = 1; i <= 6; i++) {
                    wr(i, reg[i]);
                }
                int tmp = rd(0x11);
                if (multi == 64) {
                    wr(0x11, tmp | 0x04);
                } else {
                    wr(0x11, tmp & 0xfb);
                }
	            /* VCO Calibration */
                wr(0x0e, 0x80);
                wr(0x0e, 0x00);
	            /* VCO Re-Calibration if needed */
                wr(0x0e, 0x00);
                SleepUtils.mdelay(10);
                tmp = rd(0x0e);
	            /* vco selection */
                tmp &= 0x3f;
                if (vco_select) {
                    if (tmp > 0x3c) {
                        reg[6] &= (~0x08) & 0xFF;
                        wr(0x06, reg[6]);
                        wr(0x0e, 0x80);
                        wr(0x0e, 0x00);
                    }
                } else {
                    if (tmp < 0x02) {
                        reg[6] |= 0x08;
                        wr(0x06, reg[6]);
                        wr(0x0e, 0x80);
                        wr(0x0e, 0x00);
                    }
                }
            }
        });
    }
    private void setVhfTrack(long freq) throws DvbException {
        int tmp = rd(0x1d);
        tmp &= 0xe3;
        if (freq <= 177500) {		/* VHF Track: 7 */
            wr(0x1d, tmp | 0x1c);
        } else if (freq <= 184500) {	/* VHF Track: 6 */
            wr(0x1d, tmp | 0x18);
        } else if (freq <= 191500) {	/* VHF Track: 5 */
            wr(0x1d, tmp | 0x14);
        } else if (freq <= 198500) {	/* VHF Track: 4 */
            wr(0x1d, tmp | 0x10);
        } else if (freq <= 205500) {	/* VHF Track: 3 */
            wr(0x1d, tmp | 0x0c);
        } else if (freq <= 219500) {	/* VHF Track: 2 */
            wr(0x1d, tmp | 0x08);
        } else if (freq < 300000) {	/* VHF Track: 1 */
            wr(0x1d, tmp | 0x04);
        } else {			/* UHF and GPS */
            wr(0x1d, tmp | 0x1c);
        }
    }
    @Override
    public long getIfFrequency() throws DvbException {
        /* always ? */
        return 0;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        throw new UnsupportedOperationException();
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
import android.content.res.Resources;
import androidx.annotation.NonNull;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.DvbMath;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.usb.DvbTuner;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_CARRIER;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_LOCK;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SIGNAL;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SYNC;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_VITERBI;
class Mn88473 extends Mn8847X {
    private final static int DVBT2_STREAM_ID = 0;
    private final static long XTAL = 25_000_000L;
    private DeliverySystem currentDeliverySystem = null;
    private DvbTuner tuner;
    Mn88473(Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources) {
        super(i2cAdapter, resources, 0x03);
    }
    @Override
    public synchronized void release() {
        try {
            // sleep
            writeReg(2, 0x05, 0x3e);
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        loadFirmware(R.raw.mn8847301fw);
        /* TS config */
        writeReg(2, 0x09, 0x08);
        writeReg(2, 0x08, 0x1d);
        tuner.init();
    }
    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        int deliverySystemVal, regBank22dval, regBank0d2val;
        switch (deliverySystem) {
            case DVBT:
                deliverySystemVal = 0x02;
                regBank22dval = 0x23;
                regBank0d2val = 0x2a;
                break;
            case DVBT2:
                deliverySystemVal = 0x03;
                regBank22dval = 0x3b;
                regBank0d2val = 0x29;
                break;
            case DVBC:
                deliverySystemVal = 0x04;
                regBank22dval = 0x3b;
                regBank0d2val = 0x29;
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        byte[] confValPtr;
        switch (deliverySystem) {
            case DVBT:
            case DVBT2:
                switch ((int) bandwidthHz) {
                    case 6_000_000:
                        confValPtr = new byte[] {(byte) 0xe9, (byte) 0x55, (byte) 0x55, (byte) 0x1c, (byte) 0x29, (byte) 0x1c, (byte) 0x29};
                        break;
                    case 7_000_000:
                        confValPtr = new byte[] {(byte) 0xc8, (byte) 0x00, (byte) 0x00, (byte) 0x17, (byte) 0x0a, (byte) 0x17, (byte) 0x0a};
                        break;
                    case 8_000_000:
                        confValPtr = new byte[] {(byte) 0xaf, (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0xec, (byte) 0x11, (byte) 0xec};
                        break;
                    default:
                        throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
                }
                break;
            case DVBC:
                confValPtr = new byte[] {(byte) 0x10, (byte) 0xab, (byte) 0x0d, (byte) 0xae, (byte) 0x1d, (byte) 0x9d};
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        tuner.setParams(frequency, bandwidthHz, deliverySystem);
        long ifFrequency = tuner.getIfFrequency();
        long itmp = DvbMath.divRoundClosest(ifFrequency * 0x1000000L, XTAL);
        writeReg(2, 0x05, 0x00);
        writeReg(2, 0xfb, 0x13);
        writeReg(2, 0xef, 0x13);
        writeReg(2, 0xf9, 0x13);
        writeReg(2, 0x00, 0x18);
        writeReg(2, 0x01, 0x01);
        writeReg(2, 0x02, 0x21);
        writeReg(2, 0x03, deliverySystemVal);
        writeReg(2, 0x0b, 0x00);
        writeReg(2, 0x10, (int) ((itmp >> 16) & 0xff));
        writeReg(2, 0x11, (int) ((itmp >> 8) & 0xff));
        writeReg(2, 0x12, (int) (itmp & 0xff));
        switch (deliverySystem) {
            case DVBT:
            case DVBT2:
                for (int i = 0; i < 7; i++) {
                    writeReg(2, 0x13 + i, confValPtr[i] & 0xFF);
                }
                break;
            case DVBC:
                write(1, 0x10, confValPtr, 6);
                break;
        }
        writeReg(2, 0x2d, regBank22dval);
        writeReg(2, 0x2e, 0x00);
        writeReg(2, 0x56, 0x0d);
        write(0, 0x01, new byte[] { (byte) 0xba, (byte) 0x13, (byte) 0x80, (byte) 0xba, (byte) 0x91, (byte) 0xdd, (byte) 0xe7, (byte) 0x28 });
        writeReg(0, 0x0a, 0x1a);
        writeReg(0, 0x13, 0x1f);
        writeReg(0, 0x19, 0x03);
        writeReg(0, 0x1d, 0xb0);
        writeReg(0, 0x2a, 0x72);
        writeReg(0, 0x2d, 0x00);
        writeReg(0, 0x3c, 0x00);
        writeReg(0, 0x3f, 0xf8);
        write(0, 0x40, new byte[] { (byte) 0xf4, (byte) 0x08 });
        writeReg(0, 0xd2, regBank0d2val);
        writeReg(0, 0xd4, 0x55);
        writeReg(1, 0xbe, 0x08);
        writeReg(0, 0xb2, 0x37);
        writeReg(0, 0xd7, 0x04);
        /* PLP */
        if (deliverySystem == DeliverySystem.DVBT2) {
            writeReg(2, 0x36, DVBT2_STREAM_ID);
        }
        /* Reset FSM */
        writeReg(2, 0xf8, 0x9f);
        this.currentDeliverySystem = deliverySystem;
    }
    @Override
    public synchronized int readSnr() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_VITERBI)) return 0;
        byte[] buf = new byte[4];
        int[] ibuf = new int[3];
        int tmp, tmp1, tmp2;
        switch (currentDeliverySystem) {
            case DVBT:
                read(0, 0x8f, buf, 2);
                tmp = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                if (tmp != 0) {
                    /* CNR[dB]: 10 * (log10(65536 / value) + 0.2) */
			        /* log10(65536) = 80807124, 0.2 = 3355443 */
                    return (int) DvbMath.divU64((80807124L - DvbMath.intlog10(tmp) + 3355443L) * 10000L, 1 << 24);
                } else {
                    return 0;
                }
            case DVBT2:
                ibuf[0] = readReg(2, 0xb7);
                ibuf[1] = readReg(2, 0xb8);
                ibuf[2] = readReg(2, 0xb9);
                tmp = (ibuf[1] << 8) | ibuf[2];
                tmp1 = (ibuf[0] >> 2) & 0x01; /* 0=SISO, 1=MISO */
                if (tmp != 0) {
                    if (tmp1 != 0) {
                        /* CNR[dB]: 10 * (log10(16384 / value) - 0.6) */
				        /* log10(16384) = 70706234, 0.6 = 10066330 */
                        return (int) DvbMath.divU64((70706234L - DvbMath.intlog10(tmp) - 10066330L) * 10000L, 1 << 24);
                    } else {
                        /* CNR[dB]: 10 * (log10(65536 / value) + 0.2) */
				        /* log10(65536) = 80807124, 0.2 = 3355443 */
                        return (int) DvbMath.divU64((80807124L - DvbMath.intlog10(tmp) + 3355443L) * 10000L, 1 << 24);
                    }
                } else {
                    return 0;
                }
            case DVBC:
                read(1, 0xa1, buf, 4);
                tmp1 = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF); /* signal */
                tmp2 = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF); /* noise */
                if (tmp1 != 0 && tmp2 != 0) {
                    /* CNR[dB]: 10 * log10(8 * (signal / noise)) */
			        /* log10(8) = 15151336 */
                    return (int) DvbMath.divU64((15151336L + DvbMath.intlog10(tmp1) - DvbMath.intlog10(tmp2)) * 10000L, 1 << 24);
                } else {
                    return  0;
                }
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
    }
    @Override
    public synchronized int readRfStrengthPercentage() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_SIGNAL)) return 0;
        // There's signal, read it
        int[] buf = new int[2];
        buf[0] = readReg(2, 0x86);
        buf[1] = readReg(2, 0x87);
        /* AGCRD[15:6] gives us a 10bit value ([5:0] are always 0) */
        int strength = (buf[0] << 8) | buf[1] | (buf[0] >> 2);
        return (100 * strength) / 0xffff;
    }
    @Override
    public synchronized int readBer() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_LOCK)) return 0xFFFF;
        byte[] buf = new byte[5];
        read(0, 0x92, buf, 5);
        int bitErrors = ((buf[0] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[2] & 0xFF);
        int tmp2 = ((buf[3] & 0xFF) << 8) | (buf[4] & 0xFF);
        int bitCount = tmp2 * 8 * 204;
        if (bitCount == 0) return 100;
        // Default unit is bit error per 1MB
        return (int) ((bitErrors * 0xFFFFL) / bitCount);
    }
    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        if (currentDeliverySystem == null) return SetUtils.setOf();
        int tmp;
        switch (currentDeliverySystem) {
            case DVBT:
                tmp = readReg(0, 0x62);
                if ((tmp & 0xa0) == 0) {
                    if ((tmp & 0x0f) >= 0x09) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                                FE_HAS_VITERBI, FE_HAS_SYNC,
                                FE_HAS_LOCK);
                    } else if ((tmp & 0x0f) >= 0x03) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER);
                    } else {
                        return SetUtils.setOf();
                    }
                } else {
                    return SetUtils.setOf();
                }
            case DVBT2:
                tmp = readReg(2, 0x8b);
                if ((tmp & 0x40) == 0) {
                    if ((tmp & 0x0f) >= 0x0d) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                                FE_HAS_VITERBI, FE_HAS_SYNC,
                                FE_HAS_LOCK);
                    } else if ((tmp & 0x0f) >= 0x0a) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                                FE_HAS_VITERBI);
                    } else if ((tmp & 0x0f) >= 0x07) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER);
                    } else {
                        return SetUtils.setOf();
                    }
                } else {
                    return SetUtils.setOf();
                }
            case DVBC:
                tmp = readReg(1, 0x85);
                if ((tmp & 0x40) == 0) {
                    tmp = readReg(1, 0x89);
                    if ((tmp & 0x01) != 0) {
                        return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                                FE_HAS_VITERBI, FE_HAS_SYNC,
                                FE_HAS_LOCK);
                    } else {
                        return SetUtils.setOf();
                    }
                } else {
                    return SetUtils.setOf();
                }
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
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
package info.martinmarinov.drivers.usb.rtl28xx;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.IO_EXCEPTION;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import android.content.res.Resources;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
abstract class Mn8847X implements DvbFrontend {
    private final static String TAG = Mn8847X.class.getSimpleName();
    private final static int I2C_WR_MAX = 22;
    private final static int[] I2C_ADDRESS = new int[] { 0x18, 0x1a, 0x1c };
    private final static DvbCapabilities CAPABILITIES = new DvbCapabilities(
            174000000L,
            862000000L,
            166667L,
            SetUtils.setOf(DeliverySystem.DVBT, DeliverySystem.DVBT2, DeliverySystem.DVBC));
    private final Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter;
    protected final Resources resources;
    private final int expectedChipId;
    Mn8847X(Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources, int expectedChipId) {
        this.i2cAdapter = i2cAdapter;
        this.resources = resources;
        this.expectedChipId = expectedChipId;
    }
    synchronized void write(int addressId, int reg, byte[] bytes) throws DvbException {
        write(addressId, reg, bytes, bytes.length);
    }
    synchronized void write(int addressId, int reg, byte[] value, int len) throws DvbException {
        if (len + 1 > I2C_WR_MAX) throw new DvbException(BAD_API_USAGE, resources.getString(R.string.i2c_communication_failure));
        byte[] buf = new byte[len+1];
        buf[0] = (byte) reg;
        System.arraycopy(value, 0, buf, 1, len);
        i2cAdapter.transfer(I2C_ADDRESS[addressId], 0, buf, len + 1);
    }
    synchronized void writeReg(int addressId, int reg, int val) throws DvbException {
        write(addressId, reg, new byte[] { (byte) val });
    }
    synchronized void read(int addressId, int reg, byte[] val, int len) throws DvbException {
        i2cAdapter.transfer(
                I2C_ADDRESS[addressId], 0, new byte[] {(byte) reg}, 1,
                I2C_ADDRESS[addressId], I2C_M_RD, val, len
        );
    }
    synchronized int readReg(int addressId, int reg) throws DvbException {
        byte[] ans = new byte[1];
        read(addressId, reg, ans, ans.length);
        return ans[0] & 0xFF;
    }
    @Override
    public synchronized DvbCapabilities getCapabilities() {
        return CAPABILITIES;
    }
    @Override
    public synchronized void attach() throws DvbException {
        if (readReg(2, 0xFF) != expectedChipId) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.unsupported_tuner_on_device));
        /* Sleep because chip is active by default */
        writeReg(2, 0x05, 0x3e);
    }
    void loadFirmware(int firmwareResource) throws DvbException {
        boolean isWarm = (readReg(0, 0xf5) & 0x01) == 0;
        if (!isWarm) {
            Log.d(TAG, "Loading firmware");
            writeReg(0, 0xf5, 0x03);
            InputStream inputStream = resources.openRawResource(firmwareResource);
            try {
                byte[] buff = new byte[I2C_WR_MAX - 1];
                int remain = inputStream.available();
                while (remain > 0) {
                    int toRead = remain > buff.length ? buff.length : remain;
                    int read = inputStream.read(buff, 0, toRead);
                    write(0, 0xf6, buff, read);
                    remain -= read;
                }
            } catch (IOException e) {
                throw new DvbException(IO_EXCEPTION, e);
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            /* Parity check of firmware */
            if ((readReg(0, 0xf8) & 0x10) != 0) {
                throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_load_firmware));
            }
            writeReg(0, 0xf5, 0x00);
        }
        Log.d(TAG, "Device is warm");
    }
    @Override
    public void setPids(int... pids) throws DvbException {
        // Not supported
    }
    @Override
    public void disablePidFilter() throws DvbException {
        // Not supported
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
package info.martinmarinov.drivers.usb.rtl28xx;
import android.content.res.Resources;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.usb.DvbFrontend;
enum Rtl28xxSlaveType {
    SLAVE_DEMOD_NONE((rtl28xxDvbDevice, tuner, i2CAdapter, resources) -> new Rtl2832Frontend(tuner, i2CAdapter, resources)),
    SLAVE_DEMOD_MN88472((rtl28xxDvbDevice, tuner, i2CAdapter, resources) -> {
        if (tuner != Rtl28xxTunerType.RTL2832_R828D)
            throw new DvbException(DvbException.ErrorCode.BAD_API_USAGE, resources.getString(R.string.unsupported_slave_on_tuner));
        Rtl2832Frontend master = new Rtl2832Frontend(tuner, i2CAdapter, resources);
        Mn88472 slave = new Mn88472(i2CAdapter, resources);
        return new Rtl2832pFrontend(master, rtl28xxDvbDevice, slave, false);
    }),
    SLAVE_DEMOD_MN88473((rtl28xxDvbDevice, tuner, i2CAdapter, resources) -> {
        if (tuner != Rtl28xxTunerType.RTL2832_R828D)
            throw new DvbException(DvbException.ErrorCode.BAD_API_USAGE, resources.getString(R.string.unsupported_slave_on_tuner));
        Rtl2832Frontend master = new Rtl2832Frontend(tuner, i2CAdapter, resources);
        Mn88473 slave = new Mn88473(i2CAdapter, resources);
        return new Rtl2832pFrontend(master, rtl28xxDvbDevice, slave, false);
    }),
    SLAVE_DEMOD_CXD2837ER((rtl28xxDvbDevice, tuner, i2CAdapter, resources) -> {
        if (tuner != Rtl28xxTunerType.RTL2832_R828D)
            throw new DvbException(DvbException.ErrorCode.BAD_API_USAGE, resources.getString(R.string.unsupported_slave_on_tuner));
        Rtl2832Frontend master = new Rtl2832Frontend(tuner, i2CAdapter, resources);
        Cxd2841er slave = new Cxd2841er(
                i2CAdapter,
                resources,
                Cxd2841er.Xtal.SONY_XTAL_20500,
                0xd8,
                true,
                true,
                true,
                true);
        return new Rtl2832pFrontend(master, rtl28xxDvbDevice, slave, true);
    });
    private final FrontendCreator frontendCreator;
    Rtl28xxSlaveType(FrontendCreator frontendCreator) {
        this.frontendCreator = frontendCreator;
    }
    DvbFrontend createFrontend(Rtl28xxDvbDevice rtl28xxDvbDevice, Rtl28xxTunerType tunerType, Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources) throws DvbException {
        return frontendCreator.createFrontend(rtl28xxDvbDevice, tunerType, i2cAdapter, resources);
    }
    private interface FrontendCreator {
        DvbFrontend createFrontend(Rtl28xxDvbDevice rtl28xxDvbDevice, Rtl28xxTunerType tunerType, Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources) throws DvbException;
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
import android.content.res.Resources;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.I2cAdapter.I2GateControl;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.E4000TunerData.E4000Pll;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.E4000TunerData.E4000_FREQ_BANDS;
import static info.martinmarinov.drivers.usb.rtl28xx.E4000TunerData.E4000_IF_LUT;
import static info.martinmarinov.drivers.usb.rtl28xx.E4000TunerData.E4000_LNA_LUT;
import static info.martinmarinov.drivers.usb.rtl28xx.E4000TunerData.E4000_PLL_LUT;
class E4000Tuner implements DvbTuner {
    private final static int MAX_XFER_SIZE = 64;
    private final int i2cAddress;
    private final Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter;
    private final long xtal;
    private final I2GateControl i2GateControl;
    private final Resources resources;
    E4000Tuner(int i2cAddress, Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, long xtal, I2GateControl i2GateControl, Resources resources) {
        this.i2cAddress = i2cAddress;
        this.i2cAdapter = i2cAdapter;
        this.xtal = xtal;
        this.i2GateControl = i2GateControl;
        this.resources = resources;
    }
    private void wrRegs(int reg, byte[] val) throws DvbException {
        wrRegs(reg, val, val.length);
    }
    private void wrRegs(int reg, byte[] val, int length) throws DvbException {
        if (length + 1 > MAX_XFER_SIZE) throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        byte[] buf = new byte[length + 1];
        buf[0] = (byte) reg;
        System.arraycopy(val, 0, buf, 1, length);
        i2cAdapter.transfer(i2cAddress, 0, buf);
    }
    private void readRegs(int reg, byte[] out) throws DvbException {
        readRegs(reg, out, out.length);
    }
    private void readRegs(int reg, byte[] buf, int length) throws DvbException {
        if (length > MAX_XFER_SIZE) throw new DvbException(BAD_API_USAGE, resources.getString(R.string.bad_api_usage));
        i2cAdapter.transfer(
                i2cAddress, 0, new byte[] { (byte) reg }, 1,
                i2cAddress, I2C_M_RD, buf, length
        );
    }
    private void wrReg(int reg, int val) throws DvbException {
        i2cAdapter.transfer(i2cAddress, 0, new byte[] { (byte) reg, (byte) val });
    }
    private int rdReg(int reg) throws DvbException {
        byte[] val = new byte[1];
        readRegs(reg, val);
        return val[0] & 0xFF;
    }
    @Override
    public void init() throws DvbException {
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                /* dummy I2C to ensure I2C wakes up */
                wrReg(0x02, 0x40);
	            /* reset */
                wrReg(0x00, 0x01);
	            /* disable output clock */
                wrReg(0x06, 0x00);
                wrReg(0x7a, 0x96);
	            /* configure gains */
                wrRegs(0x7e, new byte[]{(byte) 0x01, (byte) 0xFE});
                wrReg(0x82, 0x00);
                wrReg(0x24, 0x05);
                wrRegs(0x87, new byte[]{(byte) 0x20, (byte) 0x01});
                wrRegs(0x9f, new byte[]{(byte) 0x7F, (byte) 0x07});
	            /* DC offset control */
                wrReg(0x2d, 0x1f);
                wrRegs(0x70, new byte[]{(byte) 0x01, (byte) 0x01});
	            /* gain control */
                wrReg(0x1a, 0x17);
                wrReg(0x1f, 0x1a);
            }
        });
    }
    private void sleep() throws DvbException {
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                wrReg(0x00, 0x00);
            }
        });
    }
    @Override
    public void attatch() throws DvbException {
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                int chipId = rdReg(0x02);
                if (chipId != 0x40) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.unexpected_chip_id));
                // put sleep as chip seems to be in normal mode by default
                wrReg(0x00, 0x00);
            }
        });
    }
    @Override
    public void release() {
        try {
            sleep();
        } catch (DvbException ignored) {}
    }
    @Override
    public void setParams(final long frequency, final long bandwidthHz, DeliverySystem ignored) throws DvbException {
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                /* gain control manual */
                wrReg(0x1a, 0x00);
                int i;
                /* PLL */
                for (i = 0; i < E4000_PLL_LUT.length; i++) {
                    if (frequency <= E4000_PLL_LUT[i].freq) {
                        break;
                    }
                }
                if (i == E4000_PLL_LUT.length) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, frequency / 1_000_000L));
                /*
	             * Note: Currently f_VCO overflows when c->frequency is 1 073 741 824 Hz
	             * or more.
	             */
                byte[] buf = new byte[5];
                E4000Pll pllLut = E4000_PLL_LUT[i];
                long f_VCO = frequency * pllLut.mul;
                long sigma_delta = 0x10000L * (f_VCO % xtal) / xtal;
                buf[0] = (byte) (f_VCO / xtal);
                buf[1] = (byte) sigma_delta;
                buf[2] = (byte) (sigma_delta >> 8);
                buf[3] = (byte) 0x00;
                buf[4] = (byte) pllLut.div;
                wrRegs(0x09, buf);
                /* LNA filter (RF filter) */
                for (i = 0; i < E4000_LNA_LUT.length; i++) {
                    if (frequency <= E4000_LNA_LUT[i].freq) {
                        break;
                    }
                }
                if (i == E4000_LNA_LUT.length) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, frequency / 1_000_000L));
                wrReg(0x10, E4000_LNA_LUT[i].val);
                /* IF filters */
                for (i = 0; i < E4000_IF_LUT.length; i++) {
                    if (bandwidthHz <= E4000_IF_LUT[i].freq) {
                        break;
                    }
                }
                if (i == E4000_IF_LUT.length) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, frequency / 1_000_000L));
                buf[0] = (byte) E4000_IF_LUT[i].reg11val;
                buf[1] = (byte) E4000_IF_LUT[i].reg12val;
                wrRegs(0x11, buf, 2);
                /* frequency band */
                for (i = 0; i < E4000_FREQ_BANDS.length; i++) {
                    if (frequency <= E4000_FREQ_BANDS[i].freq) {
                        break;
                    }
                }
                if (i == E4000_FREQ_BANDS.length) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, frequency / 1_000_000L));
                wrReg(0x07, E4000_FREQ_BANDS[i].reg07val);
                wrReg(0x78, E4000_FREQ_BANDS[i].reg78val);
                /* DC offset */
                byte[] i_data = new byte[4];
                byte[] q_data = new byte[4];
                for (i = 0; i < 4; i++) {
                    if (i == 0) {
                        wrRegs(0x15, new byte[] {(byte) 0x00, (byte) 0x7e, (byte) 0x24});
                    } else if (i == 1) {
                        wrRegs(0x15, new byte[] {(byte) 0x00, (byte) 0x7f});
                    } else if (i == 2) {
                        wrRegs(0x15, new byte[] {(byte) 0x01});
                    } else {
                        wrRegs(0x16, new byte[] {(byte) 0x7e});
                    }
                    wrReg(0x29, 0x01);
                    readRegs(0x2a, buf, 3);
                    i_data[i] = (byte) (((buf[2] & 0x3) << 6) | (buf[0] & 0x3f));
                    q_data[i] = (byte) (((((buf[2] & 0xFF) >> 4) & 0x3) << 6) | (buf[1] & 0x3f));
                }
                swap(q_data, 2, 3);
                swap(i_data, 2, 3);
                wrRegs(0x50, q_data, 4);
                wrRegs(0x60, i_data, 4);
                /* gain control auto */
                wrReg(0x1a, 0x17);
            }
        });
    }
    private static void swap(byte[] arr, int id1, int id2) {
        byte tmp = arr[id1];
        arr[id1] = arr[id2];
        arr[id2] = tmp;
    }
    @Override
    public long getIfFrequency() throws DvbException {
        return 0; // Zero-IF
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        throw new UnsupportedOperationException();
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
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import android.content.res.Resources;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.NonNull;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.BitReverse;
import info.martinmarinov.drivers.tools.I2cAdapter.I2GateControl;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxDvbDevice.Rtl28xxI2cAdapter;
class R820tTuner implements DvbTuner {
    private final static int MAX_I2C_MSG_LEN = 2;
    private final static String TAG = R820tTuner.class.getSimpleName();
    @SuppressWarnings("unused")
    enum RafaelChip {
        CHIP_R820T, CHIP_R620D, CHIP_R828D, CHIP_R828, CHIP_R828S, CHIP_R820C
    }
    @SuppressWarnings("unused")
    enum XtalCapValue {
        XTAL_LOW_CAP_30P,
        XTAL_LOW_CAP_20P,
        XTAL_LOW_CAP_10P,
        XTAL_LOW_CAP_0P,
        XTAL_HIGH_CAP_0P
    }
    private final int i2cAddress;
    private final Rtl28xxI2cAdapter i2cAdapter;
    private final RafaelChip rafaelChip;
    private final long xtal;
    private final I2GateControl i2GateControl;
    private final Resources resources;
    private final static int VCO_POWER_REF = 0x02;
    private final static int VER_NUM = 49;
    private final static int NUM_REGS = 27;
    private final static int REG_SHADOW_START = 5;
    private final byte[] regs = new byte[NUM_REGS];
    private final static int NUM_IMR = 5;
    private final static int IMR_TRIAL = 9;
    private final SectType[] imrData = SectType.newArray(NUM_IMR);
    private XtalCapValue xtalCapValue;
    private boolean hasLock = false;
    private boolean imrDone = false;
    private boolean initDone = false;
    private long intFreq = 0;
    private long mBw;
    private int filCalCode;
    R820tTuner(int i2cAddress, Rtl28xxI2cAdapter i2cAdapter, RafaelChip rafaelChip, long xtal, I2GateControl i2GateControl, Resources resources) {
        this.i2cAddress = i2cAddress;
        this.i2cAdapter = i2cAdapter;
        this.rafaelChip = rafaelChip;
        this.xtal = xtal;
        this.i2GateControl = i2GateControl;
        this.resources = resources;
    }
    // IO
    private void shadowStore(int reg, byte[] val) {
        int len = val.length;
        int r = reg - REG_SHADOW_START;
        if (r < 0) {
            len += r;
            r = 0;
        }
        if (len <= 0) {
            return;
        }
        if (len > NUM_REGS) {
            len = NUM_REGS;
        }
        System.arraycopy(val, 0, regs, r, len);
    }
    private void write(int reg, byte[] val) throws DvbException {
        shadowStore(reg, val);
        int len = val.length;
        int pos = 0;
        byte[] buf = new byte[len+1];
        do {
            int size = len > MAX_I2C_MSG_LEN - 1 ? MAX_I2C_MSG_LEN - 1 : len;
            buf[0] = (byte) reg;
            System.arraycopy(val, pos, buf, 1, size);
            i2cAdapter.transfer(i2cAddress, 0, buf, size + 1);
            reg += size;
            len -= size;
            pos += size;
        } while (len > 0);
    }
    private void writeReg(int reg, int val) throws DvbException {
        write(reg, new byte[] {(byte) val});
    }
    private void writeRegMask(int reg, int val, int bitMask) throws DvbException {
        int rc = readCacheReg(reg);
        val = (rc & ~bitMask) | (val & bitMask);
        write(reg, new byte[] {(byte) val});
    }
    private void read(int reg, byte[] val, int len) throws DvbException {
        i2cAdapter.transfer(
                i2cAddress, 0, new byte[] {(byte) reg}, 1,
                i2cAddress, I2C_M_RD, val, len
        );
        for (int i = 0; i < len; i++) val[i] = BitReverse.bitRev8(val[i]);
    }
    private void read(int reg, byte[] val) throws DvbException {
        read(reg, val, val.length);
    }
    private int readCacheReg(int reg) {
        reg -= REG_SHADOW_START;
        if (reg < 0 || reg >= NUM_REGS) throw new IllegalArgumentException();
        return regs[reg] & 0xFF;
    }
    private int multiRead() throws DvbException {
        int sum = 0;
        int min = 255;
        int max = 0;
        byte[] data = new byte[2];
        SleepUtils.usleep(5_000);
        for (int i = 0; i < 6; i++) {
            read(0, data);
            int dataVal = data[1] & 0xFF;
            sum += dataVal;
            if (dataVal < min) min = dataVal;
            if (dataVal > max) max = dataVal;
        }
        int rc = sum - max - min;
        if (rc < 0) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.failed_callibration_step));
        return rc;
    }
    // Logic
    private void initRegs() throws DvbException {
        write(REG_SHADOW_START, R820tTunerData.INIT_REGS);
    }
    private void imrPrepare() throws DvbException {
        /* Initialize the shadow registers */
        System.arraycopy(R820tTunerData.INIT_REGS, 0, regs, 0, R820tTunerData.INIT_REGS.length);
        /* lna off (air-in off) */
        writeRegMask(0x05, 0x20, 0x20);
        /* mixer gain mode = manual */
        writeRegMask(0x07, 0, 0x10);
        /* filter corner = lowest */
        writeRegMask(0x0a, 0x0f, 0x0f);
        /* filter bw=+2cap, hp=5M */
        writeRegMask(0x0b, 0x60, 0x6f);
        /* adc=on, vga code mode, gain = 26.5dB   */
        writeRegMask(0x0c, 0x0b, 0x9f);
        /* ring clk = on */
        writeRegMask(0x0f, 0, 0x08);
        /* ring power = on */
        writeRegMask(0x18, 0x10, 0x10);
        /* from ring = ring pll in */
        writeRegMask(0x1c, 0x02, 0x02);
        /* sw_pdect = det3 */
        writeRegMask(0x1e, 0x80, 0x80);
        /* Set filt_3dB */
        writeRegMask(0x06, 0x20, 0x20);
    }
    private void vgaAdjust() throws DvbException {
        /* increase vga power to let image significant */
        for (int vgaCount = 12; vgaCount < 16; vgaCount++) {
            writeRegMask(0x0c, vgaCount, 0x0f);
            SleepUtils.usleep(10_000L);
            int rc = multiRead();
            if (rc > 40 * 4) break;
        }
    }
    private boolean imrCross(SectType[] iqPoint) throws DvbException {
        SectType[] cross = SectType.newArray(5);
        int reg08 = readCacheReg(0x08) & 0xc0;
        int reg09 = readCacheReg(0x09) & 0xc0;
        SectType tmp = new SectType();
        tmp.value = 255;
        for (int i = 0; i < 5; i++) {
            switch (i) {
                case 0:
                    cross[i].gainX  = reg08;
                    cross[i].phaseY = reg09;
                    break;
                case 1:
                    cross[i].gainX  = reg08;		/* 0 */
                    cross[i].phaseY = reg09 + 1;		/* Q-1 */
                    break;
                case 2:
                    cross[i].gainX  = reg08;		/* 0 */
                    cross[i].phaseY = (reg09 | 0x20) + 1;	/* I-1 */
                    break;
                case 3:
                    cross[i].gainX  = reg08 + 1;		/* Q-1 */
                    cross[i].phaseY = reg09;
                    break;
                default:
                    cross[i].gainX  = (reg08 | 0x20) + 1;	/* I-1 */
                    cross[i].phaseY = reg09;
            }
            writeReg(0x08, cross[i].gainX);
            writeReg(0x09, cross[i].phaseY);
            cross[i].value = multiRead();
            if (cross[i].value < tmp.value) tmp.copyFrom(cross[i]);
        }
        if ((tmp.phaseY & 0x1f) == 1) {	/* y-direction */
            iqPoint[0].copyFrom(cross[0]);
            iqPoint[1].copyFrom(cross[1]);
            iqPoint[2].copyFrom(cross[2]);
            return false;
        } else {				/* (0,0) or x-direction */
            iqPoint[0].copyFrom(cross[0]);
            iqPoint[1].copyFrom(cross[3]);
            iqPoint[2].copyFrom(cross[4]);
            return true;
        }
    }
    private static void compreCor(SectType[] iq) {
        for (int i = 3; i > 0; i--) {
            int otherId = i - 1;
            if (iq[0].value > iq[otherId].value) {
                SectType temp = new SectType();
                temp.copyFrom(iq[0]);
                iq[0].copyFrom(iq[otherId]);
                iq[otherId].copyFrom(temp);
            }
        }
    }
    private void compreSep(SectType[] iq, int reg) throws DvbException {
        /*
	     * Purpose: if (Gain<9 or Phase<9), Gain+1 or Phase+1 and compare
	     * with min value:
	     *  new < min => update to min and continue
	     *  new > min => Exit
	     */
        SectType tmp = new SectType();
        /* min value already saved in iq[0] */
        tmp.phaseY = iq[0].phaseY;
        tmp.gainX = iq[0].gainX;
        while (((tmp.gainX & 0x1f) < IMR_TRIAL) &&
                ((tmp.phaseY & 0x1f) < IMR_TRIAL)) {
            if (reg == 0x08)
                tmp.gainX++;
            else
                tmp.phaseY++;
            writeReg(0x08, tmp.gainX);
            writeReg(0x09, tmp.phaseY);
            tmp.value = multiRead();
            if (tmp.value <= iq[0].value) {
                iq[0].gainX  = tmp.gainX;
                iq[0].phaseY = tmp.phaseY;
                iq[0].value   = tmp.value;
            } else {
                return;
            }
        }
    }
    private void iqTree(SectType[] iq, int fixVal, int varVal, int fixReg) throws DvbException {
        /*
	     * record IMC results by input gain/phase location then adjust
	     * gain or phase positive 1 step and negtive 1 step,
	     * both record results
	     */
        int varReg = fixReg == 0x08 ? 0x09 : 0x08;
        for (int i = 0; i < 3; i++) {
            writeReg(fixReg, fixVal);
            writeReg(varReg, varVal);
            iq[i].value = multiRead();
            if (fixReg == 0x08) {
                iq[i].gainX  = fixVal;
                iq[i].phaseY = varVal;
            } else {
                iq[i].phaseY = fixVal;
                iq[i].gainX  = varVal;
            }
            if (i == 0) {  /* try right-side point */
                varVal++;
            } else if (i == 1) { /* try left-side point */
			    /* if absolute location is 1, change I/Q direction */
                if ((varVal & 0x1f) < 0x02) {
                    int tmp = 2 - (varVal & 0x1f);
				    /* b[5]:I/Q selection. 0:Q-path, 1:I-path */
                    if ((varVal & 0x20) != 0) {
                        varVal &= 0xc0;
                        varVal |= tmp;
                    } else {
                        varVal |= 0x20 | tmp;
                    }
                } else {
                    varVal -= 2;
                }
            }
        }
    }
    private void section(SectType iqPoint) throws DvbException {
        SectType[] compareIq = SectType.newArray(3);
        SectType[] compareBet = SectType.newArray(3);
        /* Try X-1 column and save min result to compare_bet[0] */
        if ((iqPoint.gainX & 0x1f) == 0) {
            compareIq[0].gainX = ((iqPoint.gainX) & 0xdf) + 1;  /* Q-path, Gain=1 */
        } else {
            compareIq[0].gainX = iqPoint.gainX - 1;  /* left point */
        }
        compareIq[0].phaseY = iqPoint.phaseY;
        /* y-direction */
        iqTree(compareIq, compareIq[0].gainX, compareIq[0].phaseY, 0x08);
        compreCor(compareIq);
        compareBet[0].copyFrom(compareIq[0]);
        /* Try X column and save min result to compare_bet[1] */
        compareIq[0].gainX  = iqPoint.gainX;
        compareIq[0].phaseY = iqPoint.phaseY;
        iqTree(compareIq, compareIq[0].gainX, compareIq[0].phaseY, 0x08);
        compreCor(compareIq);
        compareBet[1].copyFrom(compareIq[0]);
	    /* Try X+1 column and save min result to compare_bet[2] */
        if ((iqPoint.gainX & 0x1f) == 0x00) {
            compareIq[0].gainX = ((iqPoint.gainX) | 0x20) + 1;  /* I-path, Gain=1 */
        } else {
            compareIq[0].gainX = iqPoint.gainX + 1;
        }
        compareIq[0].phaseY = iqPoint.phaseY;
        iqTree(compareIq, compareIq[0].gainX, compareIq[0].phaseY, 0x08);
        compreCor(compareIq);
        compareBet[2].copyFrom(compareIq[0]);
        compreCor(compareBet);
        iqPoint.copyFrom(compareBet[0]);
    }
    private SectType iq() throws DvbException {
        vgaAdjust();
        SectType[] compareIq = SectType.newArray(3);
        boolean xDirection = imrCross(compareIq);
        int dirReg, otherReg;
        if (xDirection) {
            dirReg = 0x08;
            otherReg = 0x09;
        } else {
            dirReg = 0x09;
            otherReg = 0x08;
        }
        /* compare and find min of 3 points. determine i/q direction */
        compreCor(compareIq);
        /* increase step to find min value of this direction */
        compreSep(compareIq, dirReg);
        /* the other direction */
        iqTree(compareIq, compareIq[0].gainX, compareIq[0].phaseY, dirReg);
        /* compare and find min of 3 points. determine i/q direction */
        compreCor(compareIq);
        /* increase step to find min value on this direction */
        compreSep(compareIq, otherReg);
        /* check 3 points again */
        iqTree(compareIq, compareIq[0].gainX, compareIq[0].phaseY, otherReg);
        compreCor(compareIq);
        section(compareIq[0]);
        /* reset gain/phase control setting */
        writeRegMask(0x08, 0, 0x3f);
        writeRegMask(0x09, 0, 0x3f);
        return compareIq[0];
    }
    private void fImr(SectType iqPoint) throws DvbException {
        vgaAdjust();
        /*
	    * search surrounding points from previous point
	    * try (x-1), (x), (x+1) columns, and find min IMR result point
	    */
        section(iqPoint);
    }
    private void setMux(long freq) throws DvbException {
        /* Get the proper frequency range */
        freq = freq / 1_000_000L;
        R820tTunerData.FreqRange range = R820tTunerData.FREQ_RANGES[R820tTunerData.FREQ_RANGES.length - 1];
        for (int i = 0; i < R820tTunerData.FREQ_RANGES.length - 1; i++) {
            if (freq < R820tTunerData.FREQ_RANGES[i + 1].freq) {
                range = R820tTunerData.FREQ_RANGES[i];
                break;
            }
        }
        /* Open Drain */
        writeRegMask(0x17, range.openD, 0x08);
        /* RF_MUX,Polymux */
        writeRegMask(0x1a, range.rfMuxPloy, 0xc3);
        /* TF BAND */
        writeReg(0x1b, range.tfC);
        /* XTAL CAP & Drive */
        int val;
        switch (xtalCapValue) {
            case XTAL_LOW_CAP_30P:
            case XTAL_LOW_CAP_20P:
                val = range.xtalCap20p | 0x08;
                break;
            case XTAL_LOW_CAP_10P:
                val = range.xtalCap10p | 0x08;
                break;
            case XTAL_HIGH_CAP_0P:
                val = range.xtalCap0p;
                break;
            default:
            case XTAL_LOW_CAP_0P:
                val = range.xtalCap0p | 0x08;
                break;
        }
        writeRegMask(0x10, val, 0x0b);
        int reg08, reg09;
        if (imrDone) {
            reg08 = imrData[range.imrMem].gainX;
            reg09 = imrData[range.imrMem].phaseY;
        } else {
            reg08 = 0;
            reg09 = 0;
        }
        writeRegMask(0x08, reg08, 0x3f);
        writeRegMask(0x09, reg09, 0x3f);
    }
    private void setPll(long freq) throws DvbException {
        /* Frequency in kHz */
        freq = freq / 1_000L;
        long pllRef = xtal / 1_000L;
        // refdiv2 which is disabled in original driver
        writeRegMask(0x10, 0x00, 0x10);
        /* set pll autotune = 128kHz */
        writeRegMask(0x1a, 0x00, 0x0c);
        /* set VCO current = 100 */
        writeRegMask(0x12, 0x80, 0xe0);
        /* Calculate divider */
        int mixDiv = 2;
        int divNum = 0;
        long vcoMin  = 1_770_000L;
        long vcoMax  = vcoMin * 2;
        while (mixDiv <= 64) {
            if (((freq * mixDiv) >= vcoMin) &&
                    ((freq * mixDiv) < vcoMax)) {
                int divBuf = mixDiv;
                while (divBuf > 2) {
                    divBuf = divBuf >> 1;
                    divNum++;
                }
                break;
            }
            mixDiv = mixDiv << 1;
        }
        byte[] data = new byte[5];
        read(0x00, data);
        int vcoFineTune = (data[4] & 0x30) >> 4;
        if (rafaelChip != RafaelChip.CHIP_R828D) {
            if (vcoFineTune > VCO_POWER_REF) {
                divNum--;
            } else if (vcoFineTune < VCO_POWER_REF) {
                divNum++;
            }
        }
        writeRegMask(0x10, divNum << 5, 0xe0);
        long vcoFreq = freq * mixDiv;
        long nint = vcoFreq / (2 * pllRef);
        long vcoFra = vcoFreq - 2 * pllRef * nint;
	    /* boundary spur prevention */
        if (vcoFra < pllRef / 64) {
            vcoFra = 0;
        } else if (vcoFra > pllRef * 127 / 64) {
            vcoFra = 0;
            nint++;
        } else if ((vcoFra > pllRef * 127 / 128) && (vcoFra < pllRef)) {
            vcoFra = pllRef * 127 / 128;
        } else if ((vcoFra > pllRef) && (vcoFra < pllRef * 129 / 128)) {
            vcoFra = pllRef * 129 / 128;
        }
        long ni = (nint - 13) / 4;
        long si = nint - 4 * ni - 13;
        writeReg(0x14, (int) (ni + (si << 6)));
        /* pw_sdm */
        int val = vcoFra == 0 ? 0x08 : 0x00;
        writeRegMask(0x12, val, 0x08);
        /* sdm calculator */
        int nSdm = 2;
        int sdm = 0;
        while (vcoFra > 1) {
            if (vcoFra > (2 * pllRef / nSdm)) {
                sdm = sdm + 32768 / (nSdm / 2);
                vcoFra = vcoFra - 2 * pllRef / nSdm;
                if (nSdm >= 0x8000)
                    break;
            }
            nSdm = nSdm << 1;
        }
        writeReg(0x16, sdm >> 8);
        writeReg(0x15, sdm & 0xFF);
        for (int i = 0; i < 2; i++) {
            SleepUtils.usleep(1_0000L);
            /* Check if PLL has locked */
            read(0x00, data, 3);
            if ((data[2] & 0x40) != 0)
                break;
            if (i == 0) {
			    /* Didn't lock. Increase VCO current */
                writeRegMask(0x12, 0x60, 0xe0);
            }
        }
        if ((data[2] & 0x40) == 0) {
            hasLock = false;
        } else {
            hasLock = true;
            Log.d(TAG, String.format("tuner has lock at frequency %d kHz\n", freq));
            writeRegMask(0x1a, 0x08, 0x08);
        }
    }
    private void imr(int imrMem, boolean imFlag) throws DvbException {
        long ringRef = xtal > 24_000_000L ? xtal / 2_000L : xtal / 1_000L;
        int nRing = 15;
        for (int n = 0; n < 16; n++) {
            if ((16L + n) * 8L * ringRef >= 3_100_000L) {
                nRing = n;
                break;
            }
        }
        int reg18 = readCacheReg(0x18);
        int reg19 = readCacheReg(0x19);
        int reg1f = readCacheReg(0x1f);
        reg18 &= 0xf0;      /* set ring[3:0] */
        reg18 |= nRing;
        long ringVco = (16L + nRing) * 8L * ringRef;
        reg18 &= 0xdf;   /* clear ring_se23 */
        reg19 &= 0xfc;   /* clear ring_seldiv */
        reg1f &= 0xfc;   /* clear ring_att */
        long ringFreq;
        switch (imrMem) {
            case 0:
                ringFreq = ringVco / 48;
                reg18 |= 0x20;  /* ring_se23 = 1 */
                reg19 |= 0x03;  /* ring_seldiv = 3 */
                reg1f |= 0x02;  /* ring_att 10 */
                break;
            case 1:
                ringFreq = ringVco / 16;
                reg18 |= 0x00;  /* ring_se23 = 0 */
                reg19 |= 0x02;  /* ring_seldiv = 2 */
                reg1f |= 0x00;  /* pw_ring 00 */
                break;
            case 2:
                ringFreq = ringVco / 8;
                reg18 |= 0x00;  /* ring_se23 = 0 */
                reg19 |= 0x01;  /* ring_seldiv = 1 */
                reg1f |= 0x03;  /* pw_ring 11 */
                break;
            case 3:
                ringFreq = ringVco / 6;
                reg18 |= 0x20;  /* ring_se23 = 1 */
                reg19 |= 0x00;  /* ring_seldiv = 0 */
                reg1f |= 0x03;  /* pw_ring 11 */
                break;
            default:
                ringFreq = ringVco / 4;
                reg18 |= 0x00;  /* ring_se23 = 0 */
                reg19 |= 0x00;  /* ring_seldiv = 0 */
                reg1f |= 0x01;  /* pw_ring 01 */
                break;
        }
        /* write pw_ring, n_ring, ringdiv2 registers */
	    /* n_ring, ring_se23 */
        writeReg(0x18, reg18);
	    /* ring_sediv */
        writeReg(0x19, reg19);
	    /* pw_ring */
        writeReg(0x1f, reg1f);
	    /* mux input freq ~ rf_in freq */
        setMux((ringFreq - 5_300L) * 1_000L);
        setPll((ringFreq - 5_300L) * 1_000L);
        if (!hasLock) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_calibrate_tuner));
        SectType imrPoint;
        if (imFlag) {
            imrPoint = iq();
        } else {
            imrPoint = new SectType();
            imrPoint.copyFrom(imrData[3]);
            fImr(imrPoint);
        }
        imrData[imrMem >= NUM_IMR ? NUM_IMR - 1 : imrMem].copyFrom(imrPoint);
    }
    private @NonNull XtalCapValue xtalCheck() throws DvbException {
        initRegs();
        /* cap 30pF & Drive Low */
        writeRegMask(0x10, 0x0b, 0x0b);
        /* set pll autotune = 128kHz */
        writeRegMask(0x1a, 0x00, 0x0c);
         /* set manual initial reg = 111111;  */
        writeRegMask(0x13, 0x7f, 0x7f);
         /* set auto */
        writeRegMask(0x13, 0x00, 0x40);
        /* Try several xtal capacitor alternatives */
        byte[] data = new byte[3];
        for (Pair<Integer, XtalCapValue> xtalCap : R820tTunerData.XTAL_CAPS) {
            writeRegMask(0x10, xtalCap.first, 0x1b);
            SleepUtils.usleep(6_000L);
            read(0x00, data);
            if ((data[2] & 0x40) == 0) {
                continue;
            }
            int val = data[2] & 0x3f;
            if (xtal == 16_000_000L && (val > 29 || val < 23)) {
                return xtalCap.second;
            }
            if (val != 0x3f) {
                return xtalCap.second;
            }
        }
        throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_calibrate_tuner));
    }
    private void imrCalibrate() throws DvbException {
        if (initDone) return;
        if (rafaelChip == RafaelChip.CHIP_R820T ||
                rafaelChip == RafaelChip.CHIP_R828S ||
                rafaelChip == RafaelChip.CHIP_R820C) {
            xtalCapValue = XtalCapValue.XTAL_HIGH_CAP_0P;
        } else {
            for (int i = 0; i < 3; i++) {
                XtalCapValue detectedCap = xtalCheck();
                if (i == 0 || detectedCap.ordinal() > xtalCapValue.ordinal()) {
                    xtalCapValue = detectedCap;
                }
            }
        }
        initRegs();
        imrPrepare();
        imr(3, true);
        imr(1, false);
        imr(0, false);
        imr(2, false);
        imr(4, false);
        imrDone = true;
        initDone = true;
    }
    private void standby() throws DvbException {
        /* If device was not initialized yet, don't need to standby */
        if (!initDone) return;
        writeReg(0x06, 0xb1);
        writeReg(0x05, 0x03);
        writeReg(0x07, 0x3a);
        writeReg(0x08, 0x40);
        writeReg(0x09, 0xc0);
        writeReg(0x0a, 0x36);
        writeReg(0x0c, 0x35);
        writeReg(0x0f, 0x68);
        writeReg(0x11, 0x03);
        writeReg(0x17, 0xf4);
        writeReg(0x19, 0x0c);
    }
    private void setTvStandard(long bw) throws DvbException {
        long ifKhz, filtCalLo;
        int filtGain, imgR, filtQ, hpCor, extEnable, loopThrough;
        int ltAtt, fltExtWidest, polyfilCur;
        if (bw <= 6) {
            ifKhz = 3570;
            filtCalLo = 56000;	/* 52000->56000 */
            filtGain = 0x10;	/* +3db, 6mhz on */
            imgR = 0x00;		/* image negative */
            filtQ = 0x10;		/* r10[4]:low q(1'b1) */
            hpCor = 0x6b;		/* 1.7m disable, +2cap, 1.0mhz */
            extEnable = 0x60;	/* r30[6]=1 ext enable; r30[5]:1 ext at lna max-1 */
            loopThrough = 0x00;	/* r5[7], lt on */
            ltAtt = 0x00;		/* r31[7], lt att enable */
            fltExtWidest = 0x00;	/* r15[7]: flt_ext_wide off */
            polyfilCur = 0x60;	/* r25[6:5]:min */
        } else if (bw == 7) {
			    /* 7 MHz, second table */
            ifKhz = 4570;
            filtCalLo = 63000;
            filtGain = 0x10;	/* +3db, 6mhz on */
            imgR = 0x00;		/* image negative */
            filtQ = 0x10;		/* r10[4]:low q(1'b1) */
            hpCor = 0x2a;		/* 1.7m disable, +1cap, 1.25mhz */
            extEnable = 0x60;	/* r30[6]=1 ext enable; r30[5]:1 ext at lna max-1 */
            loopThrough = 0x00;	/* r5[7], lt on */
            ltAtt = 0x00;		/* r31[7], lt att enable */
            fltExtWidest = 0x00;	/* r15[7]: flt_ext_wide off */
            polyfilCur = 0x60;	/* r25[6:5]:min */
        } else {
            ifKhz = 4570;
            filtCalLo = 68500;
            filtGain = 0x10;	/* +3db, 6mhz on */
            imgR = 0x00;		/* image negative */
            filtQ = 0x10;		/* r10[4]:low q(1'b1) */
            hpCor = 0x0b;		/* 1.7m disable, +0cap, 1.0mhz */
            extEnable = 0x60;	/* r30[6]=1 ext enable; r30[5]:1 ext at lna max-1 */
            loopThrough = 0x00;	/* r5[7], lt on */
            ltAtt = 0x00;		/* r31[7], lt att enable */
            fltExtWidest = 0x00;	/* r15[7]: flt_ext_wide off */
            polyfilCur = 0x60;	/* r25[6:5]:min */
        }
        /* Initialize the shadow registers */
        System.arraycopy(R820tTunerData.INIT_REGS, 0, regs, 0, R820tTunerData.INIT_REGS.length);
        /* Init Flag & Xtal_check Result */
        writeRegMask(0x0c, imrDone ? 1 | (xtalCapValue.ordinal() << 1) : 0, 0x0f);
        /* version */
        writeRegMask(0x13, VER_NUM, 0x3f);
        /* for LT Gain test */
        writeRegMask(0x1d, 0x00, 0x38);
        SleepUtils.usleep(1_000);
        intFreq = ifKhz * 1_000;
        /* Check if standard changed. If so, filter calibration is needed */
        boolean needCalibration = bw != mBw;
        if (needCalibration) {
            byte[] data = new byte[5];
            for (int i = 0; i < 2; i++) {
			    /* Set filt_cap */
                writeRegMask(0x0b, hpCor, 0x60);
			    /* set cali clk =on */
                writeRegMask(0x0f, 0x04, 0x04);
			    /* X'tal cap 0pF for PLL */
                writeRegMask(0x10, 0x00, 0x03);
                setPll(filtCalLo * 1_000L);
                if (!hasLock) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, filtCalLo / 1_000L));
			    /* Start Trigger */
                writeRegMask(0x0b, 0x10, 0x10);
                SleepUtils.usleep(1_000L);
			    /* Stop Trigger */
                writeRegMask(0x0b, 0x00, 0x10);
			    /* set cali clk =off */
                writeRegMask(0x0f, 0x00, 0x04);
			    /* Check if calibration worked */
                read(0x00, data);
                filCalCode = data[4] & 0x0f;
                if (filCalCode != 0 && filCalCode != 0x0f) {
                    break;
                }
            }
		    /* narrowest */
            if (filCalCode == 0x0f) filCalCode = 0;
        }
        writeRegMask(0x0a, filtQ | filCalCode, 0x1f);
	    /* Set BW, Filter_gain, & HP corner */
        writeRegMask(0x0b, hpCor, 0xef);
	    /* Set Img_R */
        writeRegMask(0x07, imgR, 0x80);
	    /* Set filt_3dB, V6MHz */
        writeRegMask(0x06, filtGain, 0x30);
	    /* channel filter extension */
        writeRegMask(0x1e, extEnable, 0x60);
	    /* Loop through */
        writeRegMask(0x05, loopThrough, 0x80);
	    /* Loop through attenuation */
        writeRegMask(0x1f, ltAtt, 0x80);
	    /* filter extension widest */
        writeRegMask(0x0f, fltExtWidest, 0x80);
	    /* RF poly filter current */
        writeRegMask(0x19, polyfilCur, 0x60);
	    /* Store current standard. If it changes, re-calibrate the tuner */
        mBw = bw;
    }
    private void sysFreqSel(long freq, DeliverySystem deliverySystem) throws DvbException {
        int mixerTop, lnaTop, cpCur, divBufCur;
        int lnaVthL, mixerVthL, airCable1In, cable2In, lnaDischarge, filterCur;
        switch (deliverySystem) {
            case DVBT:
                if ((freq == 506000000L) || (freq == 666000000L) ||
                        (freq == 818000000L)) {
                    mixerTop = 0x14;	/* mixer top:14 , top-1, low-discharge */
                    lnaTop = 0xe5;		/* detect bw 3, lna top:4, predet top:2 */
                    cpCur = 0x28;		/* 101, 0.2 */
                    divBufCur = 0x20;	/* 10, 200u */
                } else {
                    mixerTop = 0x24;	/* mixer top:13 , top-1, low-discharge */
                    lnaTop = 0xe5;		/* detect bw 3, lna top:4, predet top:2 */
                    cpCur = 0x38;		/* 111, auto */
                    divBufCur = 0x30;	/* 11, 150u */
                }
                lnaVthL = 0x53;		/* lna vth 0.84	,  vtl 0.64 */
                mixerVthL = 0x75;		/* mixer vth 1.04, vtl 0.84 */
                airCable1In = 0x00;
                cable2In = 0x00;
                lnaDischarge = 14;
                filterCur = 0x40;		/* 10, low */
                break;
            case DVBT2:
                mixerTop = 0x24;	/* mixer top:13 , top-1, low-discharge */
                lnaTop = 0xe5;		/* detect bw 3, lna top:4, predet top:2 */
                lnaVthL = 0x53;	/* lna vth 0.84	,  vtl 0.64 */
                mixerVthL = 0x75;	/* mixer vth 1.04, vtl 0.84 */
                airCable1In = 0x00;
                cable2In = 0x00;
                lnaDischarge = 14;
                cpCur = 0x38;		/* 111, auto */
                divBufCur = 0x30;	/* 11, 150u */
                filterCur = 0x40;	/* 10, low */
                break;
            case DVBC:
                mixerTop = 0x24;       /* mixer top:13 , top-1, low-discharge */
                lnaTop = 0xe5;
                lnaVthL = 0x62;
                mixerVthL = 0x75;
                airCable1In = 0x60;
                cable2In = 0x00;
                lnaDischarge = 14;
                cpCur = 0x38;          /* 111, auto */
                divBufCur = 0x30;     /* 11, 150u */
                filterCur = 0x40;      /* 10, low */
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        // Skipping diplexer since it doesn't seem to be happening for R820t
        // Skipping predetect since it doesn't seem to be happening for R820t
        writeRegMask(0x1d, lnaTop, 0xc7);
        writeRegMask(0x1c, mixerTop, 0xf8);
        writeReg(0x0d, lnaVthL);
        writeReg(0x0e, mixerVthL);
	    /* Air-IN only for Astrometa */
        writeRegMask(0x05, airCable1In, 0x60);
        writeRegMask(0x06, cable2In, 0x08);
        writeRegMask(0x11, cpCur, 0x38);
        writeRegMask(0x17, divBufCur, 0x30);
        writeRegMask(0x0a, filterCur, 0x60);
	    /*
	     * Original driver initializes regs 0x05 and 0x06 with the
	     * same value again on this point. Probably, it is just an
	     * error there
	     */
	    /*
	     * Set LNA
	     */
        /* LNA TOP: lowest */
        writeRegMask(0x1d, 0, 0x38);
        /* 0: normal mode */
        writeRegMask(0x1c, 0, 0x04);
        /* 0: PRE_DECT off */
        writeRegMask(0x06, 0, 0x40);
        /* agc clk 250hz */
        writeRegMask(0x1a, 0x30, 0x30);
        SleepUtils.mdelay(250);
        /* write LNA TOP = 3 */
        writeRegMask(0x1d, 0x18, 0x38);
        /*
		 * write discharge mode
		 * what's there at the original driver
		 */
        writeRegMask(0x1c, mixerTop, 0x04);
        /* LNA discharge current */
        writeRegMask(0x1e, lnaDischarge, 0x1f);
        /* agc clk 60hz */
        writeRegMask(0x1a, 0x20, 0x30);
    }
    private void genericSetFreq(long freq /* in Hz */, long bw, DeliverySystem deliverySystem) throws DvbException {
        setTvStandard(bw);
        long loFreq = freq + intFreq;
        setMux(loFreq);
        setPll(loFreq);
        if (!hasLock) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.cannot_tune, freq / 1_000_000));
        sysFreqSel(freq, deliverySystem);
    }
    // API
    @Override
    public void init() throws DvbException {
        if (initDone) {
            Log.d(TAG, "Already initialized, no need to re-initialize");
            return;
        }
        i2GateControl.runInOpenGate(() -> {
            imrCalibrate();
            initRegs();
        });
    }
    @Override
    public void setParams(final long frequency, final long bandwidthHz, final DeliverySystem deliverySystem) throws DvbException {
        i2GateControl.runInOpenGate(() -> {
            long bw = (bandwidthHz + 500_000L) / 1_000_000L;
            if (bw == 0) bw = 8;
            genericSetFreq(frequency, bw, deliverySystem);
        });
    }
    @Override
    public void attatch() throws DvbException {
        i2GateControl.runInOpenGate(() -> {
            byte[] data = new byte[5];
            read(0x00, data);
            standby();
        });
        Log.d(TAG, "Rafael Micro r820t successfully identified");
    }
    @Override
    public void release() {
        try {
            i2GateControl.runInOpenGate(() -> standby());
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public long getIfFrequency() throws DvbException {
        return intFreq;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        throw new UnsupportedOperationException();
    }
    // Helper classes
    private static class SectType {
        private int phaseY;
        private int gainX;
        private int value;
        private void copyFrom(SectType src) {
            phaseY = src.phaseY;
            gainX = src.gainX;
            value = src.value;
        }
        private static SectType[] newArray(int elements) {
            SectType[] array = new SectType[elements];
            for (int i = 0; i < array.length; i++) array[i] = new SectType();
            return array;
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
package info.martinmarinov.drivers.usb.rtl28xx;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.tools.I2cAdapter;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.usb.DvbTuner;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
class FC0012Tuner implements DvbTuner {
    private final static boolean DUAL_MASTER = false;
    private final static boolean LOOP_TRHOUGH = false;
    private final int i2cAddr;
    private final Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter;
    private final long xtal;
    private final I2cAdapter.I2GateControl i2GateControl;
    private final Rtl28xxDvbDevice.TunerCallback tunerCallback;
    FC0012Tuner(int i2cAddr, Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, long xtal, I2cAdapter.I2GateControl i2GateControl, Rtl28xxDvbDevice.TunerCallback tunerCallback) {
        this.i2cAddr = i2cAddr;
        this.i2cAdapter = i2cAdapter;
        this.xtal = xtal;
        this.i2GateControl = i2GateControl;
        this.tunerCallback = tunerCallback;
    }
    private void wr(int reg, int val) throws DvbException {
        i2cAdapter.transfer(i2cAddr, 0, new byte[] { (byte) reg, (byte) val });
    }
    private int rd(int reg) throws DvbException {
        byte[] response = new byte[1];
        i2cAdapter.transfer(
                i2cAddr, 0, new byte[] { (byte) reg },
                i2cAddr, I2C_M_RD, response
        );
        return response[0] & 0xFF;
    }
    @Override
    public void attatch() throws DvbException {
        // no-op
    }
    @Override
    public void release() {
        // no-op
    }
    @Override
    public void init() throws DvbException {
        final int[] reg = new int[] {
                0x00,	/* dummy reg. 0 */
                0x05,	/* reg. 0x01 */
                0x10,	/* reg. 0x02 */
                0x00,	/* reg. 0x03 */
                0x00,	/* reg. 0x04 */
                0x0f,	/* reg. 0x05: may also be 0x0a */
                0x00,	/* reg. 0x06: divider 2, VCO slow */
                0x00,	/* reg. 0x07: may also be 0x0f */
                0xff,	/* reg. 0x08: AGC Clock divide by 256, AGC gain 1/256,
			   Loop Bw 1/8 */
                0x6e,	/* reg. 0x09: Disable LoopThrough, Enable LoopThrough: 0x6f */
                0xb8,	/* reg. 0x0a: Disable LO Test Buffer */
                0x82,	/* reg. 0x0b: Output Clock is same as clock frequency,
			   may also be 0x83 */
                0xfc,	/* reg. 0x0c: depending on AGC Up-Down mode, may need 0xf8 */
                0x02,	/* reg. 0x0d: AGC Not Forcing & LNA Forcing, 0x02 for DVB-T */
                0x00,	/* reg. 0x0e */
                0x00,	/* reg. 0x0f */
                0x00,	/* reg. 0x10: may also be 0x0d */
                0x00,	/* reg. 0x11 */
                0x1f,	/* reg. 0x12: Set to maximum gain */
                0x08,	/* reg. 0x13: Set to Middle Gain: 0x08,
			   Low Gain: 0x00, High Gain: 0x10, enable IX2: 0x80 */
                0x00,	/* reg. 0x14 */
                0x04,	/* reg. 0x15: Enable LNA COMPS */
        };
        if (xtal == 27_000_000L || xtal == 28_800_000L) {
            reg[0x07] |= 0x20;
        }
        if (DUAL_MASTER) {
            reg[0x0c] |= 0x02;
        }
        if (LOOP_TRHOUGH) {
            reg[0x09] |= 0x01;
        }
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                for (int i = 1; i < reg.length; i++) {
                    wr(i, reg[i]);
                }
            }
        });
    }
    @Override
    public void setParams(long frequency, long bandwidthHz, DeliverySystem ignored) throws DvbException {
        long freq = frequency / 1_000L;
        tunerCallback.onFeVhfEnable(freq <= 300000);
        int xtalFreqKhz2;
        switch ((int) xtal) {
            case 27_000_000:
                xtalFreqKhz2 = 27000 / 2;
                break;
            case 36_000_000:
                xtalFreqKhz2 = 36000 / 2;
                break;
            case 28_800_000:
            default:
                xtalFreqKhz2 = 28800 / 2;
                break;
        }
        int multi;
        final int[] reg = new int[7];
        /* select frequency divider and the frequency of VCO */
        if (freq < 37084) {		/* freq * 96 < 3560000 */
            multi = 96;
            reg[5] = 0x82;
            reg[6] = 0x00;
        } else if (freq < 55625) {	/* freq * 64 < 3560000 */
            multi = 64;
            reg[5] = 0x82;
            reg[6] = 0x02;
        } else if (freq < 74167) {	/* freq * 48 < 3560000 */
            multi = 48;
            reg[5] = 0x42;
            reg[6] = 0x00;
        } else if (freq < 111250) {	/* freq * 32 < 3560000 */
            multi = 32;
            reg[5] = 0x42;
            reg[6] = 0x02;
        } else if (freq < 148334) {	/* freq * 24 < 3560000 */
            multi = 24;
            reg[5] = 0x22;
            reg[6] = 0x00;
        } else if (freq < 222500) {	/* freq * 16 < 3560000 */
            multi = 16;
            reg[5] = 0x22;
            reg[6] = 0x02;
        } else if (freq < 296667) {	/* freq * 12 < 3560000 */
            multi = 12;
            reg[5] = 0x12;
            reg[6] = 0x00;
        } else if (freq < 445000) {	/* freq * 8 < 3560000 */
            multi = 8;
            reg[5] = 0x12;
            reg[6] = 0x02;
        } else if (freq < 593334) {	/* freq * 6 < 3560000 */
            multi = 6;
            reg[5] = 0x0a;
            reg[6] = 0x00;
        } else {
            multi = 4;
            reg[5] = 0x0a;
            reg[6] = 0x02;
        }
        long f_vco = freq * multi;
        final boolean vco_select = f_vco >= 3060000;
        if (vco_select) {
            reg[6] |= 0x08;
        }
        if (freq >= 45000) {
		    /* From divided value (XDIV) determined the FA and FP value */
            int xdiv = (int) (f_vco / xtalFreqKhz2);
            if ((f_vco - xdiv * xtalFreqKhz2) >= (xtalFreqKhz2 / 2)) {
                xdiv++;
            }
            int pm = xdiv / 8;
            int am = xdiv - (8 * pm);
            if (am < 2) {
                reg[1] = am + 8;
                reg[2] = pm - 1;
            } else {
                reg[1] = am;
                reg[2] = pm;
            }
        } else {
		/* fix for frequency less than 45 MHz */
            reg[1] = 0x06;
            reg[2] = 0x11;
        }
	    /* fix clock out */
        reg[6] |= 0x20;
	    /* From VCO frequency determines the XIN ( fractional part of Delta
	       Sigma PLL) and divided value (XDIV) */
        int xin = (int)(f_vco - (f_vco / xtalFreqKhz2) * xtalFreqKhz2);
        xin = (xin << 15) / xtalFreqKhz2;
        if (xin >= 16384) {
            xin += 32768;
        }
        reg[3] = xin >> 8;	/* xin with 9 bit resolution */
        reg[4] = xin & 0xff;
        reg[6] &= 0x3f;	/* bits 6 and 7 describe the bandwidth */
        switch ((int) bandwidthHz) {
            case 6000000:
                reg[6] |= 0x80;
                break;
            case 7000000:
                reg[6] |= 0x40;
                break;
            case 8000000:
            default:
                break;
        }
	    /* modified for Realtek demod */
        reg[5] |= 0x07;
        i2GateControl.runInOpenGate(new ThrowingRunnable<DvbException>() {
            @Override
            public void run() throws DvbException {
                for (int i = 1; i <= 6; i++) {
                    wr(i, reg[i]);
                }
	            /* VCO Calibration */
                wr(0x0e, 0x80);
                wr(0x0e, 0x00);
                /* VCO Re-Calibration if needed */
                wr(0x0e, 0x00);
                SleepUtils.mdelay(10);
                int tmp = rd(0x0e);
	            /* vco selection */
                tmp &= 0x3f;
                if (vco_select) {
                    if (tmp > 0x3c) {
                        reg[6] &= (~0x08) & 0xFF;
                        wr(0x06, reg[6]);
                        wr(0x0e, 0x80);
                        wr(0x0e, 0x00);
                    }
                } else {
                    if (tmp < 0x02) {
                        reg[6] |= 0x08;
                        wr(0x06, reg[6]);
                        wr(0x0e, 0x80);
                        wr(0x0e, 0x00);
                    }
                }
            }
        });
    }
    @Override
    public long getIfFrequency() throws DvbException {
        /* always ? */
        return 0;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        throw new UnsupportedOperationException();
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
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName.DVBT_PIP_ON;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName.DVBT_SOFT_RST;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxConst.SYS_DEMOD_CTL;
import androidx.annotation.NonNull;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
class Rtl2832pFrontend implements DvbFrontend {
    private final Rtl2832Frontend rtl2832Frontend;
    private final Rtl28xxDvbDevice rtl28xxDvbDevice;
    private final DvbFrontend slave;
    private final boolean forceSlave;
    private DvbCapabilities rtl2832Capabilities;
    private boolean slaveEnabled;
    Rtl2832pFrontend(Rtl2832Frontend rtl2832Frontend, Rtl28xxDvbDevice rtl28xxDvbDevice, DvbFrontend slave, boolean forceSlave) throws DvbException {
        this.rtl2832Frontend = rtl2832Frontend;
        this.rtl28xxDvbDevice = rtl28xxDvbDevice;
        this.slave = slave;
        this.forceSlave = forceSlave;
    }
    @Override
    public DvbCapabilities getCapabilities() {
        return slave.getCapabilities();
    }
    @Override
    public synchronized void attach() throws DvbException {
        rtl2832Frontend.attach();
        rtl2832Capabilities = rtl2832Frontend.getCapabilities();
        slave.attach();
    }
    @Override
    public synchronized void release() {
        slave.release();
        rtl2832Frontend.release();
    }
    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        enableSlave(false);
        rtl2832Frontend.init(tuner);
        enableSlave(true);
        slave.init(tuner);
    }
    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        enableSlave(false);
        if (forceSlave || !rtl2832Capabilities.getSupportedDeliverySystems().contains(deliverySystem)) {
            enableSlave(true);
        }
        activeFrontend().setParams(frequency, bandwidthHz, deliverySystem);
    }
    @Override
    public synchronized int readSnr() throws DvbException {
        return activeFrontend().readSnr();
    }
    @Override
    public synchronized int readRfStrengthPercentage() throws DvbException {
        return activeFrontend().readRfStrengthPercentage();
    }
    @Override
    public synchronized int readBer() throws DvbException {
        return activeFrontend().readBer();
    }
    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        return activeFrontend().getStatus();
    }
    private DvbFrontend activeFrontend() {
        return slaveEnabled ? slave : rtl2832Frontend;
    }
    private void enableSlave(boolean enable) throws DvbException {
        if (enable) {
            rtl2832Frontend.wrDemodReg(DVBT_SOFT_RST, 0x0);
            rtl2832Frontend.wr(0x0c, 1, new byte[]{(byte) 0x5f, (byte) 0xff});
            rtl2832Frontend.wrDemodReg(DVBT_PIP_ON, 0x1);
            rtl2832Frontend.wr(0xbc, 0, new byte[]{(byte) 0x18});
            rtl2832Frontend.wr(0x92, 1, new byte[]{(byte) 0x7f, (byte) 0xf7, (byte) 0xff});
            rtl28xxDvbDevice.wrReg(SYS_DEMOD_CTL, 0x00, 0x48); // disable ADC
        } else {
            rtl2832Frontend.wr(0x92, 1, new byte[]{(byte) 0x00, (byte) 0x0f, (byte) 0xff});
            rtl2832Frontend.wr(0xbc, 0, new byte[]{(byte) 0x08});
            rtl2832Frontend.wrDemodReg(DVBT_PIP_ON, 0x0);
            rtl2832Frontend.wr(0x0c, 1, new byte[]{(byte) 0x00, (byte) 0x00});
            rtl2832Frontend.wrDemodReg(DVBT_SOFT_RST, 0x1);
            rtl28xxDvbDevice.wrReg(SYS_DEMOD_CTL, 0x48, 0x48); // enable ADC
        }
        this.slaveEnabled = enable;
    }
    @Override
    public synchronized void disablePidFilter() throws DvbException {
        rtl2832Frontend.disablePidFilter(slaveEnabled);
    }
    @Override
    public synchronized void setPids(int... pids) throws DvbException {
        rtl2832Frontend.setPids(slaveEnabled, pids);
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
import static java.util.Collections.addAll;
import static java.util.Collections.emptySet;
import static info.martinmarinov.drivers.DeliverySystem.DVBC;
import static info.martinmarinov.drivers.DeliverySystem.DVBT2;
import static info.martinmarinov.drivers.DvbException.ErrorCode.BAD_API_USAGE;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SIGNAL;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_VITERBI;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.tools.SetUtils.setOf;
import static info.martinmarinov.drivers.tools.SleepUtils.usleep;
import android.content.res.Resources;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.LinkedHashSet;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.Check;
import info.martinmarinov.drivers.tools.ThrowingRunnable;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxDvbDevice.Rtl28xxI2cAdapter;
public class Cxd2841er implements DvbFrontend {
    private final static String TAG = Cxd2841er.class.getSimpleName();
    private ChipId chipId;
    private DvbTuner tuner;
    enum Xtal {
        SONY_XTAL_20500(
                new byte[]{0x11, (byte) 0xF0, 0x00, 0x00, 0x00},
                new byte[]{0x14, (byte) 0x80, 0x00, 0x00, 0x00},
                new byte[]{0x17, (byte) 0xEA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA},
                new byte[]{0x1C, (byte) 0xB3, 0x33, 0x33, 0x33},
                new byte[]{0x58, (byte) 0xE2, (byte) 0xAF, (byte) 0xE0, (byte) 0xBC}),
        SONY_XTAL_24000(
                new byte[]{0x15, 0x00, 0x00, 0x00, 0x00},
                new byte[]{0x18, 0x00, 0x00, 0x00, 0x00},
                new byte[]{0x1C, 0x00, 0x00, 0x00, 0x00},
                new byte[]{0x21, (byte) 0x99, (byte) 0x99, (byte) 0x99, (byte) 0x99},
                new byte[]{0x68, 0x0F, (byte) 0xA2, 0x32, (byte) 0xD0}),
        SONY_XTAL_41000(
                new byte[]{0x11, (byte) 0xF0, 0x00, 0x00, 0x00},
                new byte[]{0x14, (byte) 0x80, 0x00, 0x00, 0x00},
                new byte[]{0x17, (byte) 0xEA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA},
                new byte[]{0x1C, (byte) 0xB3, 0x33, 0x33, 0x33},
                new byte[]{0x58, (byte) 0xE2, (byte) 0xAF, (byte) 0xE0, (byte) 0xBC});
        private final byte[] nominalRate8bw, nominalRate7bw, nominalRate6bw, nominalRate5bw, nominalRate17bw;
        Xtal(byte[] nominalRate8bw, byte[] nominalRate7bw, byte[] nominalRate6bw, byte[] nominalRate5bw, byte[] nominalRate17bw) {
            this.nominalRate8bw = nominalRate8bw;
            this.nominalRate7bw = nominalRate7bw;
            this.nominalRate6bw = nominalRate6bw;
            this.nominalRate5bw = nominalRate5bw;
            this.nominalRate17bw = nominalRate17bw;
        }
    }
    private enum State {
        SHUTDOWN,
        SLEEP_TC,
        ACTIVE_TC
    }
    private enum ChipId {
        CXD2837ER(0xb1, new DvbCapabilities(
                42_000_000L,
                1002_000_000L,
                166667L,
                setOf(DeliverySystem.DVBT, DVBT2, DVBC)
        )),
        CXD2841ER(0xa7, new DvbCapabilities(
                42_000_000L,
                1002_000_000L,
                166667L,
                setOf(DeliverySystem.DVBT, DVBT2, DVBC)
        )),
        CXD2843ER(0xa4, new DvbCapabilities(
                42_000_000L,
                1002_000_000L,
                166667L,
                setOf(DeliverySystem.DVBT, DVBT2, DVBC)
        )),
        CXD2854ER(0xc1, new DvbCapabilities(
                42_000_000L,
                1002_000_000L,
                166667L,
                setOf(DeliverySystem.DVBT, DVBT2) // also ISDBT
        ));
        private final int chip_id;
        final @NonNull
        DvbCapabilities dvbCapabilities;
        ChipId(int chip_id, @NonNull DvbCapabilities dvbCapabilities) {
            this.chip_id = chip_id;
            this.dvbCapabilities = dvbCapabilities;
        }
    }
    private enum I2C {
        SLVX,
        SLVT
    }
    private final static int MAX_WRITE_REGSIZE = 16;
    private final Rtl28xxI2cAdapter i2cAdapter;
    private final Resources resources;
    private final Xtal xtal;
    private final int i2c_addr_slvx, i2c_addr_slvt;
    private final boolean tsbits, no_agcneg, ts_serial, early_tune;
    private State state = State.SHUTDOWN;
    private DeliverySystem currentDeliverySystem = null;
    public Cxd2841er(
            Rtl28xxI2cAdapter i2cAdapter,
            Resources resources,
            Xtal xtal,
            int ic2_addr,
            boolean tsbits,
            boolean no_agcneg,
            boolean ts_serial,
            boolean early_tune) {
        this.i2cAdapter = i2cAdapter;
        this.resources = resources;
        this.xtal = xtal;
        this.i2c_addr_slvx = (ic2_addr + 4) >> 1;
        this.i2c_addr_slvt = ic2_addr >> 1;
        this.tsbits = tsbits;
        this.no_agcneg = no_agcneg;
        this.ts_serial = ts_serial;
        this.early_tune = early_tune;
    }
    @Override
    public DvbCapabilities getCapabilities() {
        Check.notNull(chipId, "Capabilities check before tuner is attached");
        return chipId.dvbCapabilities;
    }
    @Override
    public void attach() throws DvbException {
        this.chipId = cxd2841erChipId();
        Log.d(TAG, "Chip found: " + chipId);
    }
    @Override
    public void release() {
        chipId = null;
        currentDeliverySystem = null;
        try {
            sleepTcToShutdown();
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        shutdownToSleepTc();
        /* SONY_DEMOD_CONFIG_IFAGCNEG = 1 (0 for NO_AGCNEG */
        writeReg(I2C.SLVT, 0x00, 0x10);
        setRegBits(I2C.SLVT, 0xcb, (no_agcneg ? 0x00 : 0x40), 0x40);
        /* SONY_DEMOD_CONFIG_IFAGC_ADC_FS = 0 */
        writeReg(I2C.SLVT, 0xcd, 0x50);
        /* SONY_DEMOD_CONFIG_PARALLEL_SEL = 1 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        setRegBits(I2C.SLVT, 0xc4, (ts_serial ? 0x80 : 0x00), 0x80);
        /* clear TSCFG bits 3+4 */
        if (tsbits) {
            setRegBits(I2C.SLVT, 0xc4, 0x00, 0x18);
        }
        tuner.init();
    }
    @Override
    public void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        if (early_tune) {
            tuner.setParams(frequency, bandwidthHz, deliverySystem);
        }
        /* deconfigure/put demod to sleep on delsys switch if active */
        if (state == State.ACTIVE_TC && deliverySystem != currentDeliverySystem) {
            sleepTc();
        }
        switch (deliverySystem) {
            case DVBT:
                currentDeliverySystem = DeliverySystem.DVBT;
                switch (state) {
                    case SLEEP_TC:
                        sleepTcToActiveT(bandwidthHz);
                        break;
                    case ACTIVE_TC:
                        retuneActive(bandwidthHz, deliverySystem);
                        break;
                    default:
                        throw new IllegalStateException("Device in bad state "+state);
                }
                break;
            case DVBT2:
                currentDeliverySystem = DVBT2;
                switch (state) {
                    case SLEEP_TC:
                        sleepTcToActiveT2(bandwidthHz);
                        break;
                    case ACTIVE_TC:
                        retuneActive(bandwidthHz, deliverySystem);
                        break;
                    default:
                        throw new IllegalStateException("Device in bad state "+state);
                }
                break;
            case DVBC:
                currentDeliverySystem = DVBC;
                if (bandwidthHz != 6000000L && bandwidthHz != 7000000L && bandwidthHz != 8000000L) {
                    bandwidthHz = 8000000L;
                    Log.d(TAG, "Forcing bandwidth to " + bandwidthHz);
                }
                switch (state) {
                    case SLEEP_TC:
                        sleepTcToActiveC(bandwidthHz);
                        break;
                    case ACTIVE_TC:
                        retuneActive(bandwidthHz, deliverySystem);
                        break;
                    default:
                        throw new IllegalStateException("Device in bad state "+state);
                }
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        if (!early_tune) {
            tuner.setParams(frequency, bandwidthHz, deliverySystem);
        }
        tuneDone();
    }
    private void tuneDone() throws DvbException {
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0, 0);
        /* SW Reset */
        writeReg(I2C.SLVT, 0xfe, 0x01);
        /* Enable TS output */
        writeReg(I2C.SLVT, 0xc3, 0x00);
    }
    private void retuneActive(long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        if (state != State.ACTIVE_TC) {
            throw new IllegalStateException("Device in bad state "+state);
        }
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* disable TS output */
        writeReg(I2C.SLVT, 0xc3, 0x01);
        switch (deliverySystem) {
            case DVBT:
                sleepTcToActiveTBand(bandwidthHz);
                break;
            case DVBT2:
                sleepTcToActiveT2Band(bandwidthHz);
                break;
            case DVBC:
                sleepTcToActiveCBand(bandwidthHz);
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
    }
    private void sleepTcToActiveT(long bandwidthHz) throws DvbException {
        setTsClockMode(DeliverySystem.DVBT);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Set demod mode */
        writeReg(I2C.SLVX, 0x17, 0x01);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Enable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x01);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Enable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Enable ADC 1 */
        writeReg(I2C.SLVT, 0x41, 0x1a);
        /* Enable ADC 2 & 3 */
        if (xtal == Xtal.SONY_XTAL_41000) {
            write(I2C.SLVT, 0x43, new byte[]{0x0A, (byte) 0xD4});
        } else {
            write(I2C.SLVT, 0x43, new byte[]{0x09, 0x54});
        }
        /* Enable ADC 4 */
        writeReg(I2C.SLVX, 0x18, 0x00);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* IFAGC gain settings */
        setRegBits(I2C.SLVT, 0xd2, 0x0c, 0x1f);
        /* Set SLV-T Bank : 0x11 */
        writeReg(I2C.SLVT, 0x00, 0x11);
        /* BBAGC TARGET level setting */
        writeReg(I2C.SLVT, 0x6a, 0x50);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* ASCOT setting */
        setRegBits(I2C.SLVT, 0xa5, 0x00, 0x01);
        /* Set SLV-T Bank : 0x18 */
        writeReg(I2C.SLVT, 0x00, 0x18);
        /* Pre-RS BER monitor setting */
        setRegBits(I2C.SLVT, 0x36, 0x40, 0x07);
        /* FEC Auto Recovery setting */
        setRegBits(I2C.SLVT, 0x30, 0x01, 0x01);
        setRegBits(I2C.SLVT, 0x31, 0x01, 0x01);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* TSIF setting */
        setRegBits(I2C.SLVT, 0xce, 0x01, 0x01);
        setRegBits(I2C.SLVT, 0xcf, 0x01, 0x01);
        if (xtal == Xtal.SONY_XTAL_24000) {
            /* Set SLV-T Bank : 0x10 */
            writeReg(I2C.SLVT, 0x00, 0x10);
            writeReg(I2C.SLVT, 0xBF, 0x60);
            writeReg(I2C.SLVT, 0x00, 0x18);
            write(I2C.SLVT, 0x24, new byte[]{(byte) 0xDC, 0x6C, 0x00});
        }
        sleepTcToActiveTBand(bandwidthHz);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable HiZ Setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x28);
        /* Disable HiZ Setting 2 */
        writeReg(I2C.SLVT, 0x81, 0x00);
        state = State.ACTIVE_TC;
    }
    private void sleepTcToActiveT2(long bandwidthHz) throws DvbException {
        setTsClockMode(DVBT2);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Set demod mode */
        writeReg(I2C.SLVX, 0x17, 0x02);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Enable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x01);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x59, 0x00);
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Enable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Enable ADC 1 */
        writeReg(I2C.SLVT, 0x41, 0x1a);
        if (xtal == Xtal.SONY_XTAL_41000) {
            write(I2C.SLVT, 0x43, new byte[]{0x0A, (byte) 0xD4});
        } else {
            write(I2C.SLVT, 0x43, new byte[]{0x09, 0x54});
        }
        /* Enable ADC 4 */
        writeReg(I2C.SLVX, 0x18, 0x00);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* IFAGC gain settings */
        setRegBits(I2C.SLVT, 0xd2, 0x0c, 0x1f);
        /* Set SLV-T Bank : 0x11 */
        writeReg(I2C.SLVT, 0x00, 0x11);
        /* BBAGC TARGET level setting */
        writeReg(I2C.SLVT, 0x6a, 0x50);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* ASCOT setting */
        setRegBits(I2C.SLVT, 0xa5, 0x00, 0x01);
        /* Set SLV-T Bank : 0x20 */
        writeReg(I2C.SLVT, 0x00, 0x20);
        /* Acquisition optimization setting */
        writeReg(I2C.SLVT, 0x8b, 0x3c);
        /* Set SLV-T Bank : 0x2b */
        writeReg(I2C.SLVT, 0x00, 0x2b);
        setRegBits(I2C.SLVT, 0x76, 0x20, 0x70);
        /* Set SLV-T Bank : 0x23 */
        writeReg(I2C.SLVT, 0x00, 0x23);
        /* L1 Control setting */
        setRegBits(I2C.SLVT, 0xE6, 0x00, 0x03);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* TSIF setting */
        setRegBits(I2C.SLVT, 0xce, 0x01, 0x01);
        setRegBits(I2C.SLVT, 0xcf, 0x01, 0x01);
        /* DVB-T2 initial setting */
        writeReg(I2C.SLVT, 0x00, 0x13);
        writeReg(I2C.SLVT, 0x83, 0x10);
        writeReg(I2C.SLVT, 0x86, 0x34);
        setRegBits(I2C.SLVT, 0x9e, 0x09, 0x0f);
        writeReg(I2C.SLVT, 0x9f, 0xd8);
        /* Set SLV-T Bank : 0x2a */
        writeReg(I2C.SLVT, 0x00, 0x2a);
        setRegBits(I2C.SLVT, 0x38, 0x04, 0x0f);
        /* Set SLV-T Bank : 0x2b */
        writeReg(I2C.SLVT, 0x00, 0x2b);
        setRegBits(I2C.SLVT, 0x11, 0x20, 0x3f);
        /* 24MHz Xtal setting */
        if (xtal == Xtal.SONY_XTAL_24000) {
            /* Set SLV-T Bank : 0x11 */
            writeReg(I2C.SLVT, 0x00, 0x11);
            write(I2C.SLVT, 0x33, new byte[]{(byte) 0xEB, 0x03, 0x3B});
            /* Set SLV-T Bank : 0x20 */
            writeReg(I2C.SLVT, 0x00, 0x20);
            write(I2C.SLVT, 0x95, new byte[]{0x5E, 0x5E, 0x47});
            writeReg(I2C.SLVT, 0x99, 0x18);
            write(I2C.SLVT, 0xD9, new byte[]{0x3F, (byte) 0xFF});
            /* Set SLV-T Bank : 0x24 */
            writeReg(I2C.SLVT, 0x00, 0x24);
            write(I2C.SLVT, 0x34, new byte[]{0x0B, 0x72});
            write(I2C.SLVT, 0xD2, new byte[]{(byte) 0x93, (byte) 0xF3, 0x00});
            write(I2C.SLVT, 0xDD, new byte[]{0x05, (byte) 0xB8, (byte) 0xD8});
            writeReg(I2C.SLVT, 0xE0, 0x00);
            /* Set SLV-T Bank : 0x25 */
            writeReg(I2C.SLVT, 0x00, 0x25);
            writeReg(I2C.SLVT, 0xED, 0x60);
            /* Set SLV-T Bank : 0x27 */
            writeReg(I2C.SLVT, 0x00, 0x27);
            writeReg(I2C.SLVT, 0xFA, 0x34);
            /* Set SLV-T Bank : 0x2B */
            writeReg(I2C.SLVT, 0x00, 0x2B);
            writeReg(I2C.SLVT, 0x4B, 0x2F);
            writeReg(I2C.SLVT, 0x9E, 0x0E);
            /* Set SLV-T Bank : 0x2D */
            writeReg(I2C.SLVT, 0x00, 0x2D);
            write(I2C.SLVT, 0x24, new byte[]{(byte) 0x89, (byte) 0x89});
            /* Set SLV-T Bank : 0x5E */
            writeReg(I2C.SLVT, 0x00, 0x5E);
            write(I2C.SLVT, 0x8C, new byte[]{0x24, (byte) 0x95});
        }
        sleepTcToActiveT2Band(bandwidthHz);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable HiZ Setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x28);
        /* Disable HiZ Setting 2 */
        writeReg(I2C.SLVT, 0x81, 0x00);
        state = State.ACTIVE_TC;
    }
    private void sleepTcToActiveC(long bandwidthHz) throws DvbException {
        setTsClockMode(DVBC);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Set demod mode */
        writeReg(I2C.SLVX, 0x17, 0x04);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Enable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x01);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x59, 0x00);
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Enable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Enable ADC 1 */
        writeReg(I2C.SLVT, 0x41, 0x1a);
        /* xtal freq 20.5MHz */
        write(I2C.SLVT, 0x43, new byte[]{0x09, 0x54});
        /* Enable ADC 4 */
        writeReg(I2C.SLVX, 0x18, 0x00);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* IFAGC gain settings */
        setRegBits(I2C.SLVT, 0xd2, 0x09, 0x1f);
        /* Set SLV-T Bank : 0x11 */
        writeReg(I2C.SLVT, 0x00, 0x11);
        /* BBAGC TARGET level setting */
        writeReg(I2C.SLVT, 0x6a, 0x48);
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        /* ASCOT setting */
        setRegBits(I2C.SLVT, 0xa5, 0x00, 0x01);
        /* Set SLV-T Bank : 0x40 */
        writeReg(I2C.SLVT, 0x00, 0x40);
        /* Demod setting */
        setRegBits(I2C.SLVT, 0xc3, 0x00, 0x04);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* TSIF setting */
        setRegBits(I2C.SLVT, 0xce, 0x01, 0x01);
        setRegBits(I2C.SLVT, 0xcf, 0x01, 0x01);
        sleepTcToActiveCBand(bandwidthHz);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable HiZ Setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x28);
        /* Disable HiZ Setting 2 */
        writeReg(I2C.SLVT, 0x81, 0x00);
        state = State.ACTIVE_TC;
    }
    private void sleepTcToActiveTBand(long bandwidthHz) throws DvbException {
        /* Set SLV-T Bank : 0x13 */
        writeReg(I2C.SLVT, 0x00, 0x13);
        /* Echo performance optimization setting */
        write(I2C.SLVT, 0x9C, new byte[]{0x01, 0x14});
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        long iffreq = calcIffreqXtal();
        switch ((int) bandwidthHz) {
            case 8000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate8bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x00, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x15, 0x28});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x01, (byte) 0xE0});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x01, 0x02});
                break;
            case 7000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate7bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x02, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x1F, (byte) 0xF8});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x12, (byte) 0xF8});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            case 6000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate6bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x04, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x25, 0x4C});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x1F, (byte) 0xDC});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            case 5000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate5bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x06, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x2C, (byte) 0xC2});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x26, 0x3C});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            default:
                throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        }
    }
    private void sleepTcToActiveT2Band(long bandwidthHz) throws DvbException {
        /* Set SLV-T Bank : 0x20 */
        writeReg(I2C.SLVT, 0x00, 0x20);
        long iffreq = calcIffreqXtal();
        switch ((int) bandwidthHz) {
            case 8000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate8bw);
                /* Set SLV-T Bank : 0x27 */
                writeReg(I2C.SLVT, 0x00, 0x27);
                setRegBits(I2C.SLVT, 0x7a, 0x00, 0x0f);
                /* Set SLV-T Bank : 0x10 */
                writeReg(I2C.SLVT, 0x00, 0x10);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x00, 0x07);
                break;
            case 7000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate7bw);
                /* Set SLV-T Bank : 0x27 */
                writeReg(I2C.SLVT, 0x00, 0x27);
                setRegBits(I2C.SLVT, 0x7a, 0x00, 0x0f);
                /* Set SLV-T Bank : 0x10 */
                writeReg(I2C.SLVT, 0x00, 0x10);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x02, 0x07);
                break;
            case 6000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate6bw);
                /* Set SLV-T Bank : 0x27 */
                writeReg(I2C.SLVT, 0x00, 0x27);
                setRegBits(I2C.SLVT, 0x7a, 0x00, 0x0f);
                /* Set SLV-T Bank : 0x10 */
                writeReg(I2C.SLVT, 0x00, 0x10);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x04, 0x07);
                break;
            case 5000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate5bw);
                /* Set SLV-T Bank : 0x27 */
                writeReg(I2C.SLVT, 0x00, 0x27);
                setRegBits(I2C.SLVT, 0x7a, 0x00, 0x0f);
                /* Set SLV-T Bank : 0x10 */
                writeReg(I2C.SLVT, 0x00, 0x10);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x06, 0x07);
                break;
            case 1712000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate17bw);
                /* Set SLV-T Bank : 0x27 */
                writeReg(I2C.SLVT, 0x00, 0x27);
                setRegBits(I2C.SLVT, 0x7a, 0x03, 0x0f);
                /* Set SLV-T Bank : 0x10 */
                writeReg(I2C.SLVT, 0x00, 0x10);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x03, 0x07);
                break;
            default:
                throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        }
    }
    private void sleepTcToActiveCBand(long bandwidthHz) throws DvbException {
        /* Set SLV-T Bank : 0x13 */
        writeReg(I2C.SLVT, 0x00, 0x13);
        /* Echo performance optimization setting */
        write(I2C.SLVT, 0x9C, new byte[]{0x01, 0x14});
        /* Set SLV-T Bank : 0x10 */
        writeReg(I2C.SLVT, 0x00, 0x10);
        long iffreq = calcIffreqXtal();
        switch ((int) bandwidthHz) {
            case 8000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate8bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x00, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x15, 0x28});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x01, (byte) 0xE0});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x01, 0x02});
                break;
            case 7000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate7bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x02, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x1F, (byte) 0xF8});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x12, (byte) 0xF8});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            case 6000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate6bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x04, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x25, 0x4C});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x1F, (byte) 0xDC});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            case 5000000:
                /* <Timing Recovery setting> */
                write(I2C.SLVT, 0x9F, xtal.nominalRate5bw);
                /* <IF freq setting> */
                write(I2C.SLVT, 0xB6, new byte[]{(byte) ((iffreq >> 16) & 0xff), (byte) ((iffreq >> 8) & 0xff), (byte) (iffreq & 0xff)});
                /* System bandwidth setting */
                setRegBits(I2C.SLVT, 0xD7, 0x06, 0x07);
                /* Demod core latency setting */
                if (xtal == Xtal.SONY_XTAL_24000) {
                    write(I2C.SLVT, 0xD9, new byte[]{0x2C, (byte) 0xC2});
                } else {
                    write(I2C.SLVT, 0xD9, new byte[]{0x26, 0x3C});
                }
                /* Notch filter setting */
                writeReg(I2C.SLVT, 0x00, 0x17);
                write(I2C.SLVT, 0x38, new byte[]{0x00, 0x03});
                break;
            default:
                throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        }
    }
    private long calcIffreqXtal() throws DvbException {
        long ifhz = tuner.getIfFrequency() * 16777216L;
        return ifhz / (xtal == Xtal.SONY_XTAL_24000 ? 48000000L : 41000000L);
    }
    private void setTsClockMode(DeliverySystem system) throws DvbException {
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        setRegBits(I2C.SLVT, 0xc4, ts_serial ? 0x01 : 0x00, 0x03);
        setRegBits(I2C.SLVT, 0xd1, ts_serial ? 0x01 : 0x00, 0x03);
        writeReg(I2C.SLVT, 0xd9, 0x08);
        setRegBits(I2C.SLVT, 0x32, 0x00, 0x01);
        setRegBits(I2C.SLVT, 0x33, ts_serial ? 0x01 : 0x00, 0x03);
        setRegBits(I2C.SLVT, 0x32, 0x01, 0x01);
        if (system == DeliverySystem.DVBT) {
            /* Enable parity period for DVB-T */
            writeReg(I2C.SLVT, 0x00, 0x10);
            setRegBits(I2C.SLVT, 0x66, 0x01, 0x01);
        } else if (system == DVBC) {
            /* Enable parity period for DVB-C */
            writeReg(I2C.SLVT, 0x00, 0x40);
            setRegBits(I2C.SLVT, 0x66, 0x01, 0x01);
        }
    }
    @Override
    public int readSnr() throws DvbException {
        if (currentDeliverySystem == null) {
            return -1;
        }
        byte[] data = new byte[2];
        withFrozenRegisters(() -> {
            writeReg(I2C.SLVT, 0x00, 0x10);
            read(I2C.SLVT, 0x28, data, data.length);
        });
        int reg = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (reg == 0) {
            return -1;
        }
        switch (currentDeliverySystem) {
            case DVBT:
                if (reg > 4996) {
                    reg = 4996;
                }
                return 100 * (intlog10x100(reg) - intlog10x100(5350 - reg) + 285);
            case DVBT2:
                if (reg > 10876) {
                    reg = 10876;
                }
                return 100 * (intlog10x100(reg) - intlog10x100(12600 - reg) + 320);
        }
        // Unsupported for DVBC
        return -1;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        if (state != State.ACTIVE_TC) {
            return 0;
        }
        // Fallback to dumb heuristics for DVB-C
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_SIGNAL)) return 0;
        return 100;
    }
    @Override
    public int readBer() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_VITERBI)) return 0xFFFF;
        return 0;
    }
    @Override
    public Set<DvbStatus> getStatus() throws DvbException {
        if (state != State.ACTIVE_TC) {
            return emptySet();
        }
        Set<DvbStatus> statuses = new LinkedHashSet<>();
        switch (currentDeliverySystem) {
            case DVBT:
                writeReg(I2C.SLVT, 0x00, 0x10);
                break;
            case DVBT2:
                writeReg(I2C.SLVT, 0x00, 0x20);
                break;
            case DVBC:
                writeReg(I2C.SLVT, 0x00, 0x40);
                break;
            default:
                throw new IllegalStateException("Unexpected delivery system");
        }
        int data;
        switch (currentDeliverySystem) {
            case DVBT:
            case DVBT2:
                data = readReg(I2C.SLVT, 0x10);
                if ((data & 0x07) != 0x07) {
                    if ((data & 0x10) != 0) {
                        // unlock
                        return emptySet();
                    }
                    if ((data & 0x07) == 0x6) {
                        // sync
                        addAll(
                                statuses,
                                DvbStatus.FE_HAS_SIGNAL,
                                DvbStatus.FE_HAS_CARRIER,
                                DvbStatus.FE_HAS_VITERBI,
                                DvbStatus.FE_HAS_SYNC
                        );
                    }
                    if ((data & 0x20) != 0) {
                        statuses.add(DvbStatus.FE_HAS_LOCK);
                    }
                }
                break;
            case DVBC:
                data = readReg(I2C.SLVT, 0x88);
                if ((data & 0x01) != 0) {
                    data = readReg(I2C.SLVT, 0x10);
                    if ((data & 0x20) != 0) {
                        addAll(
                                statuses,
                                DvbStatus.FE_HAS_LOCK,
                                DvbStatus.FE_HAS_SIGNAL,
                                DvbStatus.FE_HAS_CARRIER,
                                DvbStatus.FE_HAS_VITERBI,
                                DvbStatus.FE_HAS_SYNC
                        );
                    }
                }
                break;
        }
        return statuses;
    }
    @Override
    public void setPids(int... pids) throws DvbException {
        // Not supported
    }
    @Override
    public void disablePidFilter() throws DvbException {
        // Not supported
    }
    private ChipId cxd2841erChipId() throws DvbException {
        int chip_id;
        try {
            writeReg(I2C.SLVT, 0, 0);
            chip_id = readReg(I2C.SLVT, 0xfd);
        } catch (Throwable t) {
            writeReg(I2C.SLVX, 0, 0);
            chip_id = readReg(I2C.SLVX, 0xfd);
        }
        for (ChipId c : ChipId.values()) {
            if (c.chip_id == chip_id) {
                return c;
            }
        }
        throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unexpected_chip_id));
    }
    private void shutdownToSleepTc() throws DvbException {
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Clear all demodulator registers */
        writeReg(I2C.SLVX, 0x02, 0x00);
        usleep(5000);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Set demod SW reset */
        writeReg(I2C.SLVX, 0x10, 0x01);
        /* Select ADC clock mode */
        writeReg(I2C.SLVX, 0x13, 0x00);
        int data;
        switch (xtal) {
            case SONY_XTAL_20500:
                data = 0x0;
                break;
            case SONY_XTAL_24000:
                writeReg(I2C.SLVX, 0x12, 0x00);
                data = 0x3;
                break;
            case SONY_XTAL_41000:
                writeReg(I2C.SLVX, 0x12, 0x00);
                data = 0x1;
                break;
            default:
                throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.usupported_clock_speed));
        }
        writeReg(I2C.SLVX, 0x14, data);
        /* Clear demod SW reset */
        writeReg(I2C.SLVX, 0x10, 0x00);
        usleep(5000);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* TADC Bias On */
        writeReg(I2C.SLVT, 0x43, 0x0a);
        writeReg(I2C.SLVT, 0x41, 0x0a);
        /* SADC Bias On */
        writeReg(I2C.SLVT, 0x63, 0x16);
        writeReg(I2C.SLVT, 0x65, 0x27);
        writeReg(I2C.SLVT, 0x69, 0x06);
        state = State.SLEEP_TC;
    }
    private void sleepTcToShutdown() throws DvbException {
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* Disable oscillator */
        writeReg(I2C.SLVX, 0x15, 0x01);
        /* Set demod mode */
        writeReg(I2C.SLVX, 0x17, 0x01);
        state = State.SHUTDOWN;
    }
    private void sleepTc() throws DvbException {
        if (state == State.ACTIVE_TC) {
            switch (currentDeliverySystem) {
                case DVBT:
                    activeTToSleepTc();
                    break;
                case DVBT2:
                    activeT2ToSleepTc();
                    break;
                case DVBC:
                    activeCToSleepTc();
                    break;
            }
        }
        if (state != State.SLEEP_TC) {
            throw new IllegalStateException("Device in bad state "+state);
        }
    }
    private void activeTToSleepTc() throws DvbException {
        if (state != State.ACTIVE_TC) {
            throw new IllegalStateException("Device in bad state "+state);
        }
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* disable TS output */
        writeReg(I2C.SLVT, 0xc3, 0x01);
        /* enable Hi-Z setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x3f);
        /* enable Hi-Z setting 2 */
        writeReg(I2C.SLVT, 0x81, 0xff);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* disable ADC 1 */
        writeReg(I2C.SLVX, 0x18, 0x01);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable ADC 2 */
        writeReg(I2C.SLVT, 0x43, 0x0a);
        /* Disable ADC 3 */
        writeReg(I2C.SLVT, 0x41, 0x0a);
        /* Disable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Disable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x00);
        state = State.SLEEP_TC;
    }
    private void activeT2ToSleepTc() throws DvbException {
        if (state != State.ACTIVE_TC) {
            throw new IllegalStateException("Device in bad state "+state);
        }
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* disable TS output */
        writeReg(I2C.SLVT, 0xc3, 0x01);
        /* enable Hi-Z setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x3f);
        /* enable Hi-Z setting 2 */
        writeReg(I2C.SLVT, 0x81, 0xff);
        /* Cancel DVB-T2 setting */
        writeReg(I2C.SLVT, 0x00, 0x13);
        writeReg(I2C.SLVT, 0x83, 0x40);
        writeReg(I2C.SLVT, 0x86, 0x21);
        setRegBits(I2C.SLVT, 0x9e, 0x09, 0x0f);
        writeReg(I2C.SLVT, 0x9f, 0xfb);
        writeReg(I2C.SLVT, 0x00, 0x2a);
        setRegBits(I2C.SLVT, 0x38, 0x00, 0x0f);
        writeReg(I2C.SLVT, 0x00, 0x2b);
        setRegBits(I2C.SLVT, 0x11, 0x00, 0x3f);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* disable ADC 1 */
        writeReg(I2C.SLVX, 0x18, 0x01);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable ADC 2 */
        writeReg(I2C.SLVT, 0x43, 0x0a);
        /* Disable ADC 3 */
        writeReg(I2C.SLVT, 0x41, 0x0a);
        /* Disable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Disable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x00);
        state = State.SLEEP_TC;
    }
    private void activeCToSleepTc() throws DvbException {
        if (state != State.ACTIVE_TC) {
            throw new IllegalStateException("Device in bad state "+state);
        }
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* disable TS output */
        writeReg(I2C.SLVT, 0xc3, 0x01);
        /* enable Hi-Z setting 1 */
        writeReg(I2C.SLVT, 0x80, 0x3f);
        /* enable Hi-Z setting 2 */
        writeReg(I2C.SLVT, 0x81, 0xff);
        /* Cancel DVB-C setting */
        writeReg(I2C.SLVT, 0x00, 0x11);
        setRegBits(I2C.SLVT, 0xa3, 0x00, 0x1f);
        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C.SLVX, 0x00, 0x00);
        /* disable ADC 1 */
        writeReg(I2C.SLVX, 0x18, 0x01);
        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C.SLVT, 0x00, 0x00);
        /* Disable ADC 2 */
        writeReg(I2C.SLVT, 0x43, 0x0a);
        /* Disable ADC 3 */
        writeReg(I2C.SLVT, 0x41, 0x0a);
        /* Disable ADC clock */
        writeReg(I2C.SLVT, 0x30, 0x00);
        /* Disable RF level monitor */
        writeReg(I2C.SLVT, 0x2f, 0x00);
        /* Disable demod clock */
        writeReg(I2C.SLVT, 0x2c, 0x00);
        state = State.SLEEP_TC;
    }
    private void withFrozenRegisters(ThrowingRunnable<DvbException> r) throws DvbException {
        try {
            /*
             * Freeze registers: ensure multiple separate register reads
             * are from the same snapshot
             */
            writeReg(I2C.SLVT, 0x01, 0x01);
            r.run();
        } finally {
            writeReg(I2C.SLVT, 0x01, 0x00);
        }
    }
    synchronized void write(I2C i2C_addr, int reg, byte[] bytes) throws DvbException {
        write(i2C_addr, reg, bytes, bytes.length);
    }
    synchronized void write(I2C i2C_addr, int reg, byte[] value, int len) throws DvbException {
        if (len + 1 > MAX_WRITE_REGSIZE)
            throw new DvbException(BAD_API_USAGE, resources.getString(R.string.i2c_communication_failure));
        int i2c_addr = (i2C_addr == I2C.SLVX ? i2c_addr_slvx : i2c_addr_slvt);
        byte[] buf = new byte[len + 1];
        buf[0] = (byte) reg;
        System.arraycopy(value, 0, buf, 1, len);
        i2cAdapter.transfer(i2c_addr, 0, buf, len + 1);
    }
    synchronized void writeReg(I2C i2C_addr, int reg, int val) throws DvbException {
        write(i2C_addr, reg, new byte[]{(byte) val});
    }
    synchronized void setRegBits(I2C i2C_addr, int reg, int data, int mask) throws DvbException {
        if (mask != 0xFF) {
            int rdata = readReg(i2C_addr, reg);
            data = (data & mask) | (rdata & (mask ^ 0xFF));
        }
        writeReg(i2C_addr, reg, data);
    }
    synchronized void read(I2C i2C_addr, int reg, byte[] val, int len) throws DvbException {
        int i2c_addr = (i2C_addr == I2C.SLVX ? i2c_addr_slvx : i2c_addr_slvt);
        i2cAdapter.transfer(
                i2c_addr, 0, new byte[]{(byte) reg}, 1,
                i2c_addr, I2C_M_RD, val, len
        );
    }
    synchronized int readReg(I2C i2C_addr, int reg) throws DvbException {
        byte[] ans = new byte[1];
        read(i2C_addr, reg, ans, ans.length);
        return ans[0] & 0xFF;
    }
    private static int intlog10x100(double reg) {
        return (int) Math.round(Math.log10(reg) * 100.0);
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
import android.content.res.Resources;
import androidx.annotation.NonNull;
import java.util.Set;
import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.DvbMath;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.usb.DvbTuner;
import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_CARRIER;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_LOCK;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SIGNAL;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_SYNC;
import static info.martinmarinov.drivers.DvbStatus.FE_HAS_VITERBI;
class Mn88472 extends Mn8847X {
    private final static int DVBT2_STREAM_ID = 0;
    private final static long XTAL = 20_500_000L;
    private DvbTuner tuner;
    private DeliverySystem currentDeliverySystem = null;
    Mn88472(Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources) {
        super(i2cAdapter, resources, 0x02);
    }
    @Override
    public synchronized void release() {
        /* Power down */
        try {
            writeReg(2, 0x0c, 0x30);
            writeReg(2, 0x0b, 0x30);
            writeReg(2, 0x05, 0x3e);
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }
    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        /* Power up */
        writeReg(2, 0x05, 0x00);
        writeReg(2, 0x0b, 0x00);
        writeReg(2, 0x0c, 0x00);
        loadFirmware(R.raw.mn8847202fw);
        /* TS config */
        writeReg(2, 0x08, 0x1d);
        writeReg(0, 0xd9, 0xe3);
    }
    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        int delivery_system_val, reg_bank0_b4_val,
                reg_bank0_cd_val, reg_bank0_d4_val, reg_bank0_d6_val;
        switch (deliverySystem) {
            case DVBT:
                delivery_system_val = 0x02;
                reg_bank0_b4_val = 0x00;
                reg_bank0_cd_val = 0x1f;
                reg_bank0_d4_val = 0x0a;
                reg_bank0_d6_val = 0x48;
                break;
            case DVBT2:
                delivery_system_val = 0x03;
                reg_bank0_b4_val = 0xf6;
                reg_bank0_cd_val = 0x01;
                reg_bank0_d4_val = 0x09;
                reg_bank0_d6_val = 0x46;
                break;
            case DVBC:
                delivery_system_val = 0x04;
                reg_bank0_b4_val = 0x00;
                reg_bank0_cd_val = 0x17;
                reg_bank0_d4_val = 0x09;
                reg_bank0_d6_val = 0x48;
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        byte[] bandwidth_vals_ptr;
        int bandwidth_val;
        switch (deliverySystem) {
            case DVBT:
            case DVBT2:
                switch ((int) bandwidthHz) {
                    case 5000000:
                        bandwidth_vals_ptr = new byte[] {(byte) 0xe5, (byte) 0x99, (byte) 0x9a, (byte) 0x1b, (byte) 0xa9, (byte) 0x1b, (byte) 0xa9};
                        bandwidth_val = 0x03;
                        break;
                    case 6000000:
                        bandwidth_vals_ptr = new byte[] {(byte) 0xbf, (byte) 0x55, (byte) 0x55, (byte) 0x15, (byte) 0x6b, (byte) 0x15, (byte) 0x6b};
                        bandwidth_val = 0x02;
                        break;
                    case 7000000:
                        bandwidth_vals_ptr = new byte[] {(byte) 0xa4, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0x2c, (byte) 0x0f, (byte) 0x2c};
                        bandwidth_val = 0x01;
                        break;
                    case 8000000:
                        bandwidth_vals_ptr = new byte[] {(byte) 0x8f, (byte) 0x80, (byte) 0x00, (byte) 0x08, (byte) 0xee, (byte) 0x08, (byte) 0xee};
                        bandwidth_val = 0x00;
                        break;
                    default:
                        throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
                }
                break;
            case DVBC:
                bandwidth_vals_ptr = null;
                bandwidth_val = 0x00;
                break;
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
        }
        /* Program tuner */
        tuner.setParams(frequency, bandwidthHz, deliverySystem);
        long ifFrequency = tuner.getIfFrequency();
        writeReg(2, 0x00, 0x66);
        writeReg(2, 0x01, 0x00);
        writeReg(2, 0x02, 0x01);
        writeReg(2, 0x03, delivery_system_val);
        writeReg(2, 0x04, bandwidth_val);
	    /* IF */
        long itmp = DvbMath.divRoundClosest(ifFrequency * 0x1000000L, XTAL);
        writeReg(2, 0x10, (int) ((itmp >> 16) & 0xff));
        writeReg(2, 0x11, (int) ((itmp >>  8) & 0xff));
        writeReg(2, 0x12, (int) (itmp & 0xff));
	    /* Bandwidth */
        if (bandwidth_vals_ptr != null) {
            for (int i = 0; i < 7; i++) {
                writeReg(2, 0x13 + i, bandwidth_vals_ptr[i] & 0xFF);
            }
        }
        writeReg(0, 0xb4, reg_bank0_b4_val);
        writeReg(0, 0xcd, reg_bank0_cd_val);
        writeReg(0, 0xd4, reg_bank0_d4_val);
        writeReg(0, 0xd6, reg_bank0_d6_val);
        switch (deliverySystem) {
            case DVBT:
                writeReg(0, 0x07, 0x26);
                writeReg(0, 0x00, 0xba);
                writeReg(0, 0x01, 0x13);
                break;
            case DVBT2:
                writeReg(2, 0x2b, 0x13);
                writeReg(2, 0x4f, 0x05);
                writeReg(1, 0xf6, 0x05);
                writeReg(2, 0x32, DVBT2_STREAM_ID);
                break;
            case DVBC:
                break;
            default:
                break;
        }
        /* Reset FSM */
        writeReg(2, 0xf8, 0x9f);
        this.currentDeliverySystem = deliverySystem;
    }
    @Override
    public int readSnr() throws DvbException {
        return -1;
    }
    @Override
    public int readRfStrengthPercentage() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_SIGNAL)) return 0;
        return 100;
    }
    @Override
    public int readBer() throws DvbException {
        Set<DvbStatus> cachedStatus = getStatus();
        if (!cachedStatus.contains(FE_HAS_VITERBI)) return 0xFFFF;
        return 0;
    }
    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        if (currentDeliverySystem == null) return SetUtils.setOf();
        int tmp;
        switch (currentDeliverySystem) {
            case DVBT:
                tmp = readReg(0, 0x7f);
                if ((tmp & 0x0f) >= 0x09) {
                    return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                            FE_HAS_VITERBI, FE_HAS_SYNC,
                            FE_HAS_LOCK);
                } else {
                    return SetUtils.setOf();
                }
            case DVBT2:
                tmp = readReg(2, 0x92);
                if ((tmp & 0x0f) >= 0x0d) {
                    return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                            FE_HAS_VITERBI, FE_HAS_SYNC,
                            FE_HAS_LOCK);
                } else if ((tmp & 0x0f) >= 0x0a) {
                    return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                            FE_HAS_VITERBI);
                } else if ((tmp & 0x0f) >= 0x07) {
                    return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER);
                } else {
                    return SetUtils.setOf();
                }
            case DVBC:
                tmp = readReg(1, 0x84);
                if ((tmp & 0x0f) >= 0x08) {
                    return SetUtils.setOf(FE_HAS_SIGNAL, FE_HAS_CARRIER,
                            FE_HAS_VITERBI, FE_HAS_SYNC,
                            FE_HAS_LOCK);
                } else {
                    return SetUtils.setOf();
                }
            default:
                throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));
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