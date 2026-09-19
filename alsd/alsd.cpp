/*
 * Copyright (C) 2026 Alex Zorzi <info@alexzorzi.it>
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * The ambient light sensor sits under the display, so its raw channels are
 * dominated by the panel rather than the room. citsensorservice cancels that
 * out: it samples the screen region above the sensor through concurrent
 * writeback, weights the factory panel-emission tables by what it finds, and
 * subtracts the result before converting to lux.
 *
 * None of that happens on its own. The algorithm only runs when a client calls
 * triggerCwbDump(), and it needs the live panel brightness handed to it through
 * setBrightness(). On stock that is MIUI's display stack; AOSP has no
 * equivalent, so the sensor reports a fixed constant forever. This daemon does
 * that job.
 *
 * ICitSensorService is a vendor AIDL interface we have no headers for, so the
 * two calls are made as raw binder transactions. The codes were recovered from
 * the generated proxies in vendor.xiaomi.sensor.citsensorservice-V1-ndk.so:
 *
 *     12  setBrightness(int dbv) -> int
 *      9  triggerCwbDump(int, int, bool) -> int
 *
 * triggerCwbDump's first argument must be 0; the service returns success
 * without doing anything for any other value.
 */

#define LOG_TAG "alsd"

#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <fcntl.h>
#include <log/log.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <ctime>

namespace {

constexpr char kService[] =
        "vendor.xiaomi.sensor.citsensorservice.ICitSensorService/default";
constexpr char kDescriptor[] =
        "vendor.xiaomi.sensor.citsensorservice.ICitSensorService";
constexpr char kBacklightPanel0[] =
        "/sys/class/backlight/panel0-backlight/brightness";
constexpr char kBacklightPanel1[] =
        "/sys/class/backlight/panel1-backlight/brightness";

constexpr transaction_code_t kSetBrightness = 12;
constexpr transaction_code_t kTriggerCwbDump = 9;

constexpr long kPeriodMs = 300;
constexpr long kIdlePeriodMs = 1500;

void* onCreate(void* args) {
    return args;
}

void onDestroy(void* /*userData*/) {}

binder_status_t onTransact(AIBinder* /*binder*/, transaction_code_t /*code*/,
                           const AParcel* /*in*/, AParcel* /*out*/) {
    return STATUS_UNKNOWN_TRANSACTION;
}

AIBinder_Class* interfaceClass() {
    static AIBinder_Class* clazz =
            AIBinder_Class_define(kDescriptor, onCreate, onDestroy, onTransact);
    return clazz;
}

void sleepMs(long ms) {
    timespec ts = {ms / 1000, (ms % 1000) * 1000000};
    nanosleep(&ts, nullptr);
}

int readBacklightFd(int fd) {
    if (fd < 0) return -1;

    char buf[16] = {0};
    ssize_t bytes = pread(fd, buf, sizeof(buf) - 1, 0);
    if (bytes <= 0) return -1;

    buf[bytes] = '\0';
    return atoi(buf);
}

bool transact(AIBinder* binder, transaction_code_t code, int32_t a, bool hasRest,
              int32_t b, bool c) {
    AParcel* in = nullptr;
    AParcel* out = nullptr;

    if (AIBinder_prepareTransaction(binder, &in) != STATUS_OK) return false;
    if (AParcel_writeInt32(in, a) != STATUS_OK) {
        AParcel_delete(in);
        return false;
    }
    if (hasRest) {
        if (AParcel_writeInt32(in, b) != STATUS_OK ||
            AParcel_writeBool(in, c) != STATUS_OK) {
            AParcel_delete(in);
            return false;
        }
    }

    binder_status_t status = AIBinder_transact(binder, code, &in, &out, 0);
    if (out != nullptr) AParcel_delete(out);
    return status == STATUS_OK;
}

}  // namespace

int main() {
    while (true) {
        int fd0 = open(kBacklightPanel0, O_RDONLY | O_CLOEXEC);
        int fd1 = open(kBacklightPanel1, O_RDONLY | O_CLOEXEC);

        if (fd0 < 0 && fd1 < 0) {
            ALOGE("Failed to open both %s and %s: %s, retrying...",
                  kBacklightPanel0, kBacklightPanel1, strerror(errno));
            sleepMs(kIdlePeriodMs);
            continue;
        }

        AIBinder* binder = AServiceManager_waitForService(kService);
        if (binder == nullptr) {
            ALOGE("%s is unavailable, retrying...", kService);
            if (fd0 >= 0) close(fd0);
            if (fd1 >= 0) close(fd1);
            sleepMs(kIdlePeriodMs);
            continue;
        }

        if (!AIBinder_associateClass(binder, interfaceClass())) {
            ALOGE("Failed to associate class %s", kDescriptor);
            AIBinder_decStrong(binder);
            if (fd0 >= 0) close(fd0);
            if (fd1 >= 0) close(fd1);
            sleepMs(kIdlePeriodMs);
            continue;
        }

        ALOGI("Connected to citsensorservice (fd0: %d, fd1: %d)", fd0, fd1);

        int lastDbv = -1;

        while (true) {
            int dbv0 = readBacklightFd(fd0);
            int dbv1 = readBacklightFd(fd1);
            int activeDbv = (dbv0 > 0) ? dbv0 : ((dbv1 > 0) ? dbv1 : 0);
            if (activeDbv <= 0) {
                lastDbv = activeDbv;
                sleepMs(kIdlePeriodMs);
                continue;
            }

            if (activeDbv != lastDbv) {
                if (!transact(binder, kSetBrightness, activeDbv, false, 0, false)) {
                    ALOGW("citsensorservice died during setBrightness");
                    break;
                }
                lastDbv = activeDbv;
            }

            if (!transact(binder, kTriggerCwbDump, 0, true, 0, true)) {
                ALOGW("citsensorservice died during triggerCwbDump");
                break;
            }

            sleepMs(kPeriodMs);
        }

        AIBinder_decStrong(binder);
        if (fd0 >= 0) close(fd0);
        if (fd1 >= 0) close(fd1);
    }

    return 0;
}
