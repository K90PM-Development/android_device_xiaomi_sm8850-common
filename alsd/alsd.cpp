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
#include <log/log.h>

#include <cerrno>
#include <cstdio>
#include <ctime>

namespace {

constexpr char kService[] =
        "vendor.xiaomi.sensor.citsensorservice.ICitSensorService/default";
// A remote binder has to be associated with a class before it will accept a
// transaction. We never receive calls, so the callbacks are stubs.
constexpr char kDescriptor[] =
        "vendor.xiaomi.sensor.citsensorservice.ICitSensorService";
constexpr char kBacklight[] = "/sys/class/backlight/panel0-backlight/brightness";

constexpr transaction_code_t kSetBrightness = 12;
constexpr transaction_code_t kTriggerCwbDump = 9;

// The panel only needs resampling about as fast as auto-brightness reacts.
constexpr long kPeriodMs = 300;
// With the screen off there is no panel light to cancel and nothing reading
// lux, so back right off instead of spinning.
constexpr long kIdlePeriodMs = 2000;

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

int readBacklight() {
    FILE* f = fopen(kBacklight, "re");
    if (f == nullptr) return -1;
    int value = -1;
    if (fscanf(f, "%d", &value) != 1) value = -1;
    fclose(f);
    return value;
}

// Returns false if the transaction could not be delivered, which is how we
// notice the service has died.
bool transact(AIBinder* binder, transaction_code_t code, int32_t a, bool hasRest,
              int32_t b, bool c) {
    AParcel* in = nullptr;
    AParcel* out = nullptr;

    if (AIBinder_prepareTransaction(binder, &in) != STATUS_OK) return false;
    if (AParcel_writeInt32(in, a) != STATUS_OK) return false;
    if (hasRest) {
        if (AParcel_writeInt32(in, b) != STATUS_OK) return false;
        if (AParcel_writeBool(in, c) != STATUS_OK) return false;
    }

    binder_status_t status = AIBinder_transact(binder, code, &in, &out, 0);
    if (out != nullptr) AParcel_delete(out);
    return status == STATUS_OK;
}

}  // namespace

int main() {
    while (true) {
        // waitForService blocks until citsensorservice is up, so this also
        // covers the service restarting under us.
        AIBinder* binder = AServiceManager_waitForService(kService);
        if (binder == nullptr) {
            ALOGE("%s is unavailable, retrying", kService);
            sleepMs(kIdlePeriodMs);
            continue;
        }

        if (!AIBinder_associateClass(binder, interfaceClass())) {
            ALOGE("failed to associate %s", kDescriptor);
            AIBinder_decStrong(binder);
            sleepMs(kIdlePeriodMs);
            continue;
        }

        ALOGI("driving the under-display ALS panel compensation");

        while (true) {
            int dbv = readBacklight();
            if (dbv <= 0) {
                // Screen off, or we cannot read the panel. Either way there is
                // nothing useful to compute.
                sleepMs(kIdlePeriodMs);
                continue;
            }

            if (!transact(binder, kSetBrightness, dbv, false, 0, false) ||
                !transact(binder, kTriggerCwbDump, 0, true, 0, true)) {
                ALOGW("citsensorservice went away, reconnecting");
                break;
            }

            sleepMs(kPeriodMs);
        }

        AIBinder_decStrong(binder);
    }
}
