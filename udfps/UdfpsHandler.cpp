/*
 * Copyright (C) 2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "UdfpsHandler.xiaomi_sm8850"

#include <aidl/android/hardware/biometrics/fingerprint/BnFingerprint.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>

#include <android/looper.h>
#include <android/sensor.h>

#include <fcntl.h>
#include <poll.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <linux/uinput.h>

#include <chrono>
#include <condition_variable>
#include <cstring>
#include <fstream>
#include <mutex>
#include <thread>

#include "UdfpsHandler.h"

#define COMMAND_FOD_PRESS_STATUS 1
#define PARAM_FOD_PRESSED 1
#define PARAM_FOD_RELEASED 0

#define FOD_STATUS_PATH "/sys/class/touch/touch_dev/fod_press_status"
#define FOD_STATUS_OFF 0
#define FOD_STATUS_ON 1

#define FINGERPRINT_ACQUIRED_VENDOR 7

using ::aidl::android::hardware::biometrics::fingerprint::AcquiredInfo;

/* Exported by libsensorndkbridge but not declared in the NDK sensor header. */
extern "C" float ASensor_getMaxRange(const ASensor* sensor);

/*
 * Written by DisplayPowerController on every screen state change. Every domain
 * may read it (domain.te), so the HAL can observe screen state without talking
 * to the framework.
 */
static constexpr char SCREEN_STATE_PROPERTY[] = "debug.tracing.screen_state";

/* android.view.Display.STATE_ON; anything else counts as screen off. */
static constexpr char DISPLAY_STATE_ON[] = "2";

/*
 * The sensor HAL replays its last known state right after a subscription starts
 * and only reports the real sample around 200 ms later (observed on myron).
 * Presses inside that window are not classified, so a stale replay cannot let
 * a pocket touch through.
 */
static constexpr auto PROXIMITY_SETTLE = std::chrono::milliseconds(300);

/*
 * A finger resting on the sensor also drives the proximity reading to "near",
 * so the instantaneous state cannot distinguish a real press from pocket
 * contact. A cover only counts when it predates the touch: pocket fabric has
 * been there for seconds, while a finger and its proximity signature arrive
 * together.
 */
static constexpr auto PROXIMITY_COVER_GRACE = std::chrono::milliseconds(250);

namespace {

template <typename T>
static void set(const std::string& path, const T& value) {
    std::ofstream file(path);
    file << value;
}

}  // anonymous namespace

class XiaomiSM8850UdfpsHander : public UdfpsHandler {
  public:
    /* Where a press came from; exactly one source releases the press it started. */
    enum class PressSource { Node, Framework };

    void init(fingerprint_device_t* device) {
        mDevice = device;

        mUinputFd.reset(open("/dev/uinput", O_WRONLY | O_NONBLOCK));
        if (mUinputFd.get() < 0) {
            LOG(ERROR) << "Failed to open uinput";
        } else {
            ioctl(mUinputFd.get(), UI_SET_EVBIT, EV_KEY);
            ioctl(mUinputFd.get(), UI_SET_KEYBIT, KEY_WAKEUP);
            struct uinput_setup usetup = { .id = { .bustype = BUS_VIRTUAL }, .name = "UdfpsWakeup" };
            ioctl(mUinputFd.get(), UI_DEV_SETUP, &usetup);
            ioctl(mUinputFd.get(), UI_DEV_CREATE);
        }

        startProximityMonitor();

        std::thread([this]() {
            android::base::unique_fd fd(open(FOD_STATUS_PATH, O_RDONLY));
            
            if (fd.get() < 0) {
                LOG(ERROR) << "Failed to open FOD status path";
                return; 
            }

            struct pollfd pfd;
            pfd.fd = fd.get();
            pfd.events = POLLPRI | POLLERR;

            char buf;

            while (true) {
                int ret = poll(&pfd, 1, -1);

                if (ret < 0) {
                    LOG(ERROR) << "Error polling FOD status";
                    std::this_thread::sleep_for(std::chrono::seconds(1));
                    continue;
                }

                if (pfd.revents & (POLLPRI | POLLERR)) {
                    lseek(fd.get(), 0, SEEK_SET);

                    if (read(fd.get(), &buf, 1) > 0) {
                        bool pressed = (buf == '1');

                        /*
                         * This node reports contact with the FOD area for every
                         * touch, whatever the display is doing. While the screen
                         * is on the framework reports the same touch through
                         * onFingerDown, and whichever arrives first starts the
                         * scan. When this node wins the scan begins before the
                         * session is ready, fails, and onFingerDown is then
                         * suppressed as a duplicate press: the touch is lost and
                         * only a second press unlocks.
                         *
                         * So the node is only the press source while the screen
                         * is off, which is the case it exists for. It also owns
                         * the matching release, so a dropped press cannot send a
                         * release underneath a press the framework started.
                         *
                         * Fabric in a pocket trips the node too. Those presses
                         * are dropped when the proximity sensor reports the
                         * device as covered, otherwise every pocket touch starts
                         * a scan that fails and plays the reject haptic.
                         */
                        if (pressed) {
                            if (isScreenOff() && !isCovered()) {
                                setFingerDown(true, PressSource::Node);
                            }
                        } else if (takeNodePress()) {
                            setFingerDown(false);
                        }
                    }
                }
            }
        }).detach();
    }

    /*
     * The framework path only exists while the display is interactive, which is
     * exactly when the touch node path stands aside, so a press is always
     * reported once and never needs proximity gating: the pocket can only cover
     * a device whose screen is off.
     *
     * The framework owns the press from here on, so the touch node must not end
     * it: a screen that turns on mid-touch would otherwise cancel its scan.
     */
    void onFingerDown(uint32_t /*x*/, uint32_t /*y*/, float /*minor*/, float /*major*/) {
        LOG(INFO) << __func__;
        setFingerDown(true, PressSource::Framework);
    }

    void onFingerUp() {
        LOG(INFO) << __func__;
        setFingerDown(false);
    }

    void onAuthenticationSucceeded() {
        LOG(INFO) << __func__;
        endGesture();
    }

    void onAuthenticationFailed() {
        LOG(INFO) << __func__;
        endGesture();
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(INFO) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        if (result != FINGERPRINT_ACQUIRED_VENDOR) {
            /*
             * Intermediate results must not release the press: sending a release
             * while the scan is still running makes the HAL drop it. The release
             * comes from onFingerUp or endGesture instead.
             */
            if (static_cast<AcquiredInfo>(result) == AcquiredInfo::GOOD) {
                setFodStatus(FOD_STATUS_OFF);
                wakeDevice();
                endGesture();
            }
        } else if (vendorCode == 201 || vendorCode == 202) {
            /*
             * vendorCode = 201 waiting for fingerprint authentication
             * vendorCode = 202 waiting for fingerprint enroll
             */
            setFodStatus(FOD_STATUS_ON);
        }
    }

    void cancel() {
        LOG(INFO) << __func__;
        takeNodePress();
        setFingerDown(false);
        setFodStatus(FOD_STATUS_OFF);
    }

  private:
    fingerprint_device_t* mDevice;
    android::base::unique_fd mUinputFd;
    std::mutex mFingerDownMutex;
    bool mFingerDown = false;
    /* Exactly one source releases the press it started. Both guarded by
     * mFingerDownMutex. */
    bool mFrameworkPress = false;
    bool mNodePressed = false;

    bool takeNodePress() {
        std::lock_guard<std::mutex> lock(mFingerDownMutex);
        if (!mNodePressed) {
            return false;
        }

        mNodePressed = false;
        return true;
    }

    /*
     * onFingerUp is not guaranteed: the framework tears the session down on
     * success and drops the late pointer up, so a latched press would eat every
     * later press. Release from the result instead.
     */
    void endGesture() {
        std::lock_guard<std::mutex> lock(mFingerDownMutex);
        if (!mFrameworkPress) {
            return;
        }

        mFrameworkPress = false;
        if (!mFingerDown) {
            return;
        }
        mFingerDown = false;

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_RELEASED);
    }

    /*
     * Proximity gate.
     *
     * fod_press_status only reports contact with the FOD area, so pocket fabric
     * triggers it. The gate drops those presses, but only while the screen is
     * off: that is the only time the device can be covered and the only time
     * fod_press_status is the source of presses. While the display is on the
     * framework reports touches through onFingerDown, and gating that would
     * just add latency to every normal unlock.
     *
     * Screen state comes from DisplayPowerController's debug.tracing.screen_state
     * property, which every domain may read, so the gate needs no polling.
     * The sensor itself is only enabled while the screen is off and is disabled
     * again as soon as it turns on, so an interactive device costs nothing.
     *
     * A press never waits: if a trusted reading is in hand it decides
     * immediately, otherwise the press is allowed through. Indecision always
     * resolves towards unlocking normally, never towards eating a tap.
     *
     * Only a cover that predates the touch counts, see PROXIMITY_COVER_GRACE.
     */
    std::mutex mProxMutex;
    std::condition_variable mProxCv;
    ALooper* mProxLooper = nullptr;
    ASensorEventQueue* mProxQueue = nullptr;
    const ASensor* mProxSensor = nullptr;
    float mProxMaxRange = 0;
    bool mProxUnavailable = false;
    bool mProxSensorEnabled = false;
    bool mProxTrusted = false;
    bool mProxHasValue = false;
    bool mProxNear = false;
    std::chrono::steady_clock::time_point mProxTrustAt;
    /* When the current cover started; only meaningful while mProxNear. */
    std::chrono::steady_clock::time_point mProxNearSince;

    void startProximityMonitor() {
        std::thread([this]() { screenStateThread(); }).detach();
        std::thread([this]() { proximityPollThread(); }).detach();
    }

    /*
     * Screen state. Read via __system_property_find so a missing property (early
     * boot, or a platform that stops publishing it) reads as "on", which leaves
     * the gate entirely out of the way.
     */
    static bool readScreenOn() {
        const prop_info* pi = __system_property_find(SCREEN_STATE_PROPERTY);
        if (pi == nullptr) {
            return true;
        }

        char value[PROP_VALUE_MAX] = {};
        __system_property_read(pi, nullptr, value);
        return strcmp(value, DISPLAY_STATE_ON) == 0;
    }

    static bool isScreenOff() {
        return !readScreenOn();
    }

    /*
     * True only when a trusted reading says the device has been covered for
     * longer than PROXIMITY_COVER_GRACE. A finger on the sensor reports "near"
     * at the same moment as the touch, so requiring the cover to predate the
     * press is what separates a pocket from a finger. Presses are decided
     * synchronously under mProxMutex, so this cannot race the sensor thread
     * updating mProxNear.
     */
    bool isCovered() {
        std::lock_guard<std::mutex> lock(mProxMutex);
        if (mProxUnavailable || !mProxTrusted || !mProxNear) {
            return false;
        }

        return std::chrono::steady_clock::now() - mProxNearSince >= PROXIMITY_COVER_GRACE;
    }

    bool proximitySetup() {
        ASensorManager* manager = ASensorManager_getInstanceForPackage(
                "android.hardware.biometrics.fingerprint-service.xiaomi");
        const ASensor* sensor = manager != nullptr
                                        ? ASensorManager_getDefaultSensor(
                                                  manager, ASENSOR_TYPE_PROXIMITY)
                                        : nullptr;
        ALooper* looper = sensor != nullptr
                                  ? ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS)
                                  : nullptr;
        ASensorEventQueue* queue =
                looper != nullptr
                        ? ASensorManager_createEventQueue(manager, looper, 0, nullptr, nullptr)
                        : nullptr;

        if (queue == nullptr) {
            return false;
        }

        mProxLooper = looper;
        mProxQueue = queue;
        mProxSensor = sensor;
        mProxMaxRange = ASensor_getMaxRange(sensor);
        LOG(INFO) << "Proximity gate ready, max range: " << mProxMaxRange;
        return true;
    }

    void enableProximityLocked() {
        ASensorEvent stale;
        while (ASensorEventQueue_getEvents(mProxQueue, &stale, 1) > 0) {
        }

        if (ASensorEventQueue_enableSensor(mProxQueue, mProxSensor) < 0) {
            LOG(ERROR) << "Failed to enable proximity sensor, FOD presses will not be gated";
            mProxUnavailable = true;
            mProxCv.notify_all();
            return;
        }

        mProxSensorEnabled = true;
        mProxTrusted = false;
        mProxHasValue = false;
        mProxNear = false;
        mProxTrustAt = std::chrono::steady_clock::now() + PROXIMITY_SETTLE;
        mProxCv.notify_all();
        LOG(INFO) << "Proximity sensor enabled (screen off)";
    }

    void disableProximityLocked() {
        if (!mProxSensorEnabled) {
            return;
        }

        ASensorEventQueue_disableSensor(mProxQueue, mProxSensor);
        mProxSensorEnabled = false;
        mProxTrusted = false;
        mProxCv.notify_all();
        LOG(INFO) << "Proximity sensor disabled (screen on)";
    }

    /*
     * Watches the screen state property and enables the sensor only while the
     * display is off. __system_property_wait blocks until the property changes,
     * so there is no polling here; the timeout only guards against the property
     * being deleted and recreated underneath us.
     */
    void screenStateThread() {
        std::unique_lock<std::mutex> lock(mProxMutex);

        const prop_info* pi = nullptr;
        while (pi == nullptr) {
            pi = __system_property_find(SCREEN_STATE_PROPERTY);
            if (pi == nullptr) {
                mProxCv.wait_for(lock, std::chrono::seconds(1));
            }
        }

        if (!proximitySetup()) {
            LOG(ERROR) << "No usable proximity sensor, FOD presses will not be gated";
            mProxUnavailable = true;
            return;
        }

        bool screenOn = readScreenOn();
        if (!screenOn) {
            enableProximityLocked();
        }

        for (;;) {
            const uint32_t serial = __system_property_serial(pi);

            lock.unlock();
            const struct timespec timeout = { 10, 0 };
            uint32_t newSerial = serial;
            __system_property_wait(pi, serial, &newSerial, &timeout);
            lock.lock();

            if (__system_property_find(SCREEN_STATE_PROPERTY) != pi) {
                pi = __system_property_find(SCREEN_STATE_PROPERTY);
                if (pi == nullptr) {
                    /* Without screen state the sensor could be left enabled for
                     * good, so drop the gate entirely and stop reading it. */
                    disableProximityLocked();
                    mProxUnavailable = true;
                    return;
                }
                screenOn = readScreenOn();
                if (!screenOn) {
                    enableProximityLocked();
                } else {
                    disableProximityLocked();
                }
                continue;
            }

            if (newSerial == serial) {
                continue;
            }

            const bool newScreenOn = readScreenOn();
            if (newScreenOn == screenOn) {
                continue;
            }
            screenOn = newScreenOn;

            if (screenOn) {
                disableProximityLocked();
            } else {
                enableProximityLocked();
            }
        }
    }

    /*
     * Drains proximity events while the sensor is enabled. The thread parks on
     * the condition variable whenever the sensor is off, so an interactive
     * device consumes no CPU here.
     */
    void proximityPollThread() {
        std::unique_lock<std::mutex> lock(mProxMutex);

        for (;;) {
            mProxCv.wait(lock, [this]() { return mProxSensorEnabled || mProxUnavailable; });
            if (mProxUnavailable) {
                return;
            }

            /* Sleep until the settle window ends, or until an event arrives. */
            auto waitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                  mProxTrustAt - std::chrono::steady_clock::now())
                                  .count();
            if (waitMs < 1) {
                waitMs = 1;
            }

            lock.unlock();
            ALooper_pollOnce(static_cast<int>(waitMs), nullptr, nullptr, nullptr);
            lock.lock();

            if (!mProxSensorEnabled) {
                /* Screen turned on while we were polling. */
                continue;
            }

            if (!mProxTrusted && std::chrono::steady_clock::now() >= mProxTrustAt) {
                mProxTrusted = true;
                LOG(INFO) << "Proximity reading trusted: " << (mProxNear ? "near" : "far");
            }

            ASensorEvent event;
            while (ASensorEventQueue_getEvents(mProxQueue, &event, 1) > 0) {
                if (event.type != ASENSOR_TYPE_PROXIMITY) {
                    continue;
                }

                const bool near =
                        mProxMaxRange > 0 ? event.distance < mProxMaxRange : event.distance == 0;
                if (mProxHasValue && near == mProxNear) {
                    continue;
                }
                LOG(INFO) << "Proximity state: " << (near ? "near" : "far")
                          << (mProxTrusted ? "" : " (settling)");
                if (near) {
                    mProxNearSince = std::chrono::steady_clock::now();
                }
                mProxHasValue = true;
                mProxNear = near;
            }
        }
    }

    void setFodStatus(int value) {
        set(FOD_STATUS_PATH, value);
    }

    /*
     * Never deduplicate a press: a latched one would swallow every later press
     * until cancel(). The vendor HAL already drops a second down while queued,
     * so forwarding costs nothing. Releases stay deduplicated.
     */
    void setFingerDown(bool pressed, PressSource source = PressSource::Node) {
        std::lock_guard<std::mutex> lock(mFingerDownMutex);
        if (pressed) {
            mFingerDown = true;
            mFrameworkPress = source == PressSource::Framework;
            mNodePressed = source == PressSource::Node;
        } else {
            if (!mFingerDown) {
                return;
            }
            mFingerDown = false;
            mFrameworkPress = false;
            mNodePressed = false;
        }

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS,
                        pressed ? PARAM_FOD_PRESSED : PARAM_FOD_RELEASED);
    }

    void wakeDevice() {
        if (mUinputFd.get() >= 0) {
            struct input_event ev[3] = {};
            ev[0].type = EV_KEY; ev[0].code = KEY_WAKEUP; ev[0].value = 1;
            ev[1].type = EV_KEY; ev[1].code = KEY_WAKEUP; ev[1].value = 0;
            ev[2].type = EV_SYN; ev[2].code = SYN_REPORT; ev[2].value = 0;
            write(mUinputFd.get(), ev, sizeof(ev));
        }
    }
};

static UdfpsHandler* create() {
    return new XiaomiSM8850UdfpsHander();
}

static void destroy(UdfpsHandler* handler) {
    delete handler;
}

extern "C" UdfpsHandlerFactory UDFPS_HANDLER_FACTORY = {
        .create = create,
        .destroy = destroy,
};
