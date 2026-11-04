///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

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
