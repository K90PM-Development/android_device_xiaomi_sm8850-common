/*
 * Copyright (C) 2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "UdfpsHandler.xiaomi_sm8850"

#include <aidl/android/hardware/biometrics/fingerprint/BnFingerprint.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>

#include <fstream>
#include <thread>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <linux/uinput.h>

#include "UdfpsHandler.h"

#define COMMAND_FOD_PRESS_STATUS 1
#define PARAM_FOD_PRESSED 1
#define PARAM_FOD_RELEASED 0

#define FOD_STATUS_PATH "/sys/class/touch/touch_dev/fod_press_status"
#define FOD_STATUS_OFF 0
#define FOD_STATUS_ON 1

#define FINGERPRINT_ACQUIRED_VENDOR 7

using ::aidl::android::hardware::biometrics::fingerprint::AcquiredInfo;

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
                            setFingerDown(pressed);
                        }
                    }
                }
            }
        }).detach();
    }

    void onFingerDown(uint32_t /*x*/, uint32_t /*y*/, float /*minor*/, float /*major*/) {
        LOG(INFO) << __func__;
        setFingerDown(true);
    }

    void onFingerUp() {
        LOG(INFO) << __func__;
        setFingerDown(false);
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(INFO) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        if (result != FINGERPRINT_ACQUIRED_VENDOR) {
            setFingerDown(false);
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
        } else if (vendorCode == 44) {
            /*
             * vendorCode = 44 fingerprint scan failed
             */
            setFingerDown(false);
        }
    }

    void cancel() {
        LOG(INFO) << __func__;
        setFingerDown(false);
        setFodStatus(FOD_STATUS_OFF);
    }

  private:
    fingerprint_device_t* mDevice;
    android::base::unique_fd mUinputFd;

    void setFodStatus(int value) {
        set(FOD_STATUS_PATH, value);
    }

    void setFingerDown(bool pressed) {
        if (pressed) {
            mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_PRESSED);
        }
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
