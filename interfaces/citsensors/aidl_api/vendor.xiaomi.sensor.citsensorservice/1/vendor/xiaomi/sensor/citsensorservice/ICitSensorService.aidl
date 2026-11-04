package vendor.xiaomi.sensor.citsensorservice;

@VintfStability
interface ICitSensorService {
    int initHallSocket(in boolean enable);
    int getHallEvent();
    int calibrate(in int type, in int para);
    int channelCalibrate(in int type, in int channel, in float target);
    int getConfig(in int type, out float[] array, in int length, in int cindex);
    int setConfig(in int type, in int axis, in float value);
    int selftest(in int type, in int para);
    int collectData(in int displayId);
    int triggerCwbDump(in int displayId, in int cwbStatus, in boolean enable);
    void enableCitSensorServiceLog(in boolean enable);
    int getCalibrated();
    int setBrightness(in int brightness);
    int setArrayConfig(in int type, in float[] array, in int length);
    int getSensorSn(in int type, out char[] serialnum);
}