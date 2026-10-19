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
#include <unistd.h>
#include <linux/uinput.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
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
 * The proximity sensor is only enabled while a press needs classifying, and
 * stays on for this long after the last one so a burst of touches does not pay
 * the activation cost every time.
 */
static constexpr auto PROXIMITY_LEASE = std::chrono::seconds(2);

/* Upper bound on how long a press waits for a reading before it is let through
 * unclassified, so a sensor that never reports cannot break unlocking. */
static constexpr auto PROXIMITY_READ_TIMEOUT = std::chrono::milliseconds(300);

/*
 * The sensor HAL replays its last known state right after a subscription starts
 * and only reports the real sample around 200 ms later (observed on myron).
 * That replay is what let pocket touches through, so a press is not classified
 * until this much time has passed since the sensor was enabled.
 */
static constexpr auto PROXIMITY_SETTLE = std::chrono::milliseconds(300);

namespace {

template <typename T>
static void set(const std::string& path, const T& value) {
    std::ofstream file(path);
    file << value;
}

}  // anonymous namespace

class XiaomiSM8850UdfpsHander : public UdfpsHandler {
  public:
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
            bool lastState = false;

            lseek(fd.get(), 0, SEEK_SET);
            read(fd.get(), &buf, 1);

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

                        if (pressed != lastState) {
                            lastState = pressed;

                            /*
                             * fod_press_status reports contact with the FOD area, not a
                             * confirmed finger: fabric in a pocket triggers it too. The
                             * proximity gate drops the press when the device is covered,
                             * otherwise every pocket touch starts a scan that fails and
                             * plays the reject haptic.
                             */
                            if (pressed) {
                                gatePress();
                            } else {
                                releasePress();
                            }
                        }
                    }
                }
            }
        }).detach();
    }

    void onFingerDown(uint32_t /*x*/, uint32_t /*y*/, float /*minor*/, float /*major*/) {
        LOG(INFO) << __func__;
        gatePress();
    }

    void onFingerUp() {
        LOG(INFO) << __func__;
        releasePress();
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(INFO) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        if (result != FINGERPRINT_ACQUIRED_VENDOR) {
            /*
             * Only report a release once the finger is actually lifted (touch node
             * transition or onFingerUp), not on intermediate scan results: sending
             * a release while the scan is still running makes the HAL drop it.
             */
            if (static_cast<AcquiredInfo>(result) == AcquiredInfo::GOOD) {
                setFodStatus(FOD_STATUS_OFF);
                wakeDevice();
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
        releasePress();
        setFodStatus(FOD_STATUS_OFF);
    }

  private:
    fingerprint_device_t* mDevice;
    android::base::unique_fd mUinputFd;
    std::mutex mFingerDownMutex;
    bool mFingerDown = false;

    /*
     * Proximity gate.
     *
     * The sensor is not kept running: it is enabled when a press needs to be
     * classified and disabled again once PROXIMITY_LEASE expires, so an idle
     * device burns no sensor power and the worker thread sleeps on a condition
     * variable instead of waking periodically. The lease only avoids paying the
     * enable/read cost again for a burst of touches.
     *
     * All state below is guarded by mProxMutex, which is also held while a
     * press is decided so a release cannot race past a deferred press.
     */
    std::mutex mProxMutex;
    std::condition_variable mProxCv;
    ALooper* mProxLooper = nullptr;
    ASensorEventQueue* mProxQueue = nullptr;
    const ASensor* mProxSensor = nullptr;
    float mProxMaxRange = 0;
    bool mProxSetupDone = false;
    bool mProxSetupOk = false;
    bool mProxUnavailable = false;
    bool mProxSensorEnabled = false;
    bool mProxHasValue = false;
    bool mProxNear = false;
    bool mProxPendingPress = false;
    std::chrono::steady_clock::time_point mProxLeaseEnd;
    std::chrono::steady_clock::time_point mProxTrustAt;
    std::chrono::steady_clock::time_point mProxPressDeadline;
    bool mProxSettled = false;

    void startProximityMonitor() {
        std::thread([this]() { proximityThread(); }).detach();
    }

    /* Classify a press. Never blocks: once the sensor has settled an existing
     * reading is used right away, otherwise the press is deferred and applied
     * by the proximity thread once a trusted sample arrives. */
    void gatePress() {
        std::lock_guard<std::mutex> lock(mProxMutex);

        if (mProxUnavailable || !mProxSetupDone) {
            /* Fail open: while there is no usable sensor, keep the old
             * unconditional behaviour. */
            setFingerDown(true);
            return;
        }

        if (isFingerDown()) {
            return;
        }

        const auto now = std::chrono::steady_clock::now();
        /* A press while the sensor is already running only has to wait for the
         * current lease's settle point; a cold start waits a full window. */
        if (mProxSensorEnabled) {
            if (!mProxSettled && now + PROXIMITY_SETTLE < mProxTrustAt) {
                mProxTrustAt = now + PROXIMITY_SETTLE;
            }
        } else {
            mProxTrustAt = now + PROXIMITY_SETTLE;
        }

        if (mProxSensorEnabled && now >= mProxTrustAt && mProxHasValue) {
            /* Trusted reading already in hand: decide without touching the
             * deadline machinery. */
            applyPendingPressLocked();
            return;
        }

        if (!mProxPendingPress) {
            mProxPendingPress = true;
            mProxPressDeadline = mProxTrustAt + PROXIMITY_READ_TIMEOUT;
        } else {
            const auto at = mProxTrustAt + PROXIMITY_READ_TIMEOUT;
            mProxPressDeadline = at < mProxPressDeadline ? at : mProxPressDeadline;
        }

        if (mProxSensorEnabled) {
            mProxLeaseEnd = now + PROXIMITY_LEASE;
            ALooper_wake(mProxLooper);
        } else {
            mProxCv.notify_one();
        }
    }

    void releasePress() {
        std::lock_guard<std::mutex> lock(mProxMutex);
        mProxPendingPress = false;
        setFingerDown(false);
    }

    bool isFingerDown() {
        std::lock_guard<std::mutex> lock(mFingerDownMutex);
        return mFingerDown;
    }

    void applyPendingPressLocked() {
        mProxPendingPress = false;
        if (mProxNear) {
            LOG(INFO) << "Ignoring FOD press, proximity sensor covered";
        } else {
            setFingerDown(true);
        }
    }

    void allowPendingPressLocked() {
        mProxPendingPress = false;
        setFingerDown(true);
    }

    void proximitySetup() {
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

        std::lock_guard<std::mutex> lock(mProxMutex);
        if (queue == nullptr) {
            LOG(ERROR) << "No usable proximity sensor, FOD presses will not be gated";
            mProxUnavailable = true;
        } else {
            mProxLooper = looper;
            mProxQueue = queue;
            mProxSensor = sensor;
            mProxMaxRange = ASensor_getMaxRange(sensor);
            mProxSetupOk = true;
            LOG(INFO) << "Proximity gate ready, max range: " << mProxMaxRange;
        }
        mProxSetupDone = true;
        mProxCv.notify_all();
    }

    void proximityThread() {
        /* Setup talks to sensor service and can block until it is available, so
         * it runs on its own thread: presses keep working, ungated, meanwhile. */
        std::thread([this]() { proximitySetup(); }).detach();

        std::unique_lock<std::mutex> lock(mProxMutex);
        mProxCv.wait(lock, [this]() { return mProxSetupDone; });
        if (!mProxSetupOk) {
            return;
        }

        ASensorEventQueue* queue = mProxQueue;
        const ASensor* sensor = mProxSensor;
        const float maxRange = mProxMaxRange;

        for (;;) {
            if (!mProxSensorEnabled) {
                if (!mProxPendingPress) {
                    mProxCv.wait(lock);
                    continue;
                }

                /* Discard anything left over from the previous lease so it
                 * cannot be mistaken for a fresh reading. */
                ASensorEvent stale;
                while (ASensorEventQueue_getEvents(queue, &stale, 1) > 0) {
                }

                if (ASensorEventQueue_enableSensor(queue, sensor) < 0) {
                    LOG(ERROR)
                            << "Failed to enable proximity sensor, FOD presses will not be gated";
                    mProxUnavailable = true;
                    allowPendingPressLocked();
                    return;
                }

                mProxSensorEnabled = true;
                mProxHasValue = false;
                mProxSettled = false;
                mProxTrustAt = std::chrono::steady_clock::now() + PROXIMITY_SETTLE;
                mProxLeaseEnd = mProxTrustAt + PROXIMITY_LEASE;
                LOG(INFO) << "Proximity sensor enabled";
            }

            const auto now = std::chrono::steady_clock::now();

            if (mProxSensorEnabled && now >= mProxTrustAt) {
                mProxSettled = true;
            }

            if (mProxPendingPress && mProxHasValue && mProxSettled) {
                applyPendingPressLocked();
            }

            if (now >= mProxLeaseEnd) {
                ASensorEventQueue_disableSensor(queue, sensor);
                mProxSensorEnabled = false;
                mProxHasValue = false;
                LOG(INFO) << "Proximity sensor disabled";
                continue;
            }

            if (mProxPendingPress && now >= mProxPressDeadline) {
                LOG(WARNING) << "No proximity reading in time, allowing FOD press";
                allowPendingPressLocked();
            }

            /* Sleep until the lease ends, or earlier to re-evaluate a press:
             * at the settle deadline first, then at the read timeout. Sensor
             * events wake pollOnce on their own. */
            auto wakeAt = mProxLeaseEnd;
            if (mProxPendingPress) {
                if (mProxTrustAt < wakeAt) {
                    wakeAt = mProxTrustAt;
                }
                if (mProxPressDeadline < wakeAt) {
                    wakeAt = mProxPressDeadline;
                }
            }
            auto waitMs =
                    std::chrono::duration_cast<std::chrono::milliseconds>(wakeAt - now).count();
            if (waitMs < 1) {
                waitMs = 1;
            }

            lock.unlock();
            int ident = ALooper_pollOnce(static_cast<int>(waitMs), nullptr, nullptr, nullptr);
            lock.lock();

            if (ident == ALOOPER_POLL_ERROR) {
                LOG(ERROR) << "Failed to poll proximity events, FOD presses will not be gated";
                ASensorEventQueue_disableSensor(queue, sensor);
                mProxSensorEnabled = false;
                mProxUnavailable = true;
                allowPendingPressLocked();
                return;
            }

            ASensorEvent event;
            while (ASensorEventQueue_getEvents(queue, &event, 1) > 0) {
                if (event.type != ASENSOR_TYPE_PROXIMITY) {
                    continue;
                }

                bool near = maxRange > 0 ? event.distance < maxRange : event.distance == 0;
                const bool trusted = std::chrono::steady_clock::now() >= mProxTrustAt;
                if (!mProxHasValue || near != mProxNear || !trusted) {
                    LOG(INFO) << "Proximity state: " << (near ? "near" : "far")
                              << (trusted ? "" : " (settling, not trusted yet)");
                }
                mProxNear = near;
                mProxHasValue = true;
            }
        }
    }

    void setFodStatus(int value) {
        set(FOD_STATUS_PATH, value);
    }

    void setFingerDown(bool pressed) {
        std::lock_guard<std::mutex> lock(mFingerDownMutex);
        if (mFingerDown == pressed) {
            return;
        }
        mFingerDown = pressed;

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
