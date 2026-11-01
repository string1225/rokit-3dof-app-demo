#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include "FusionAhrs.h"
#include "FusionBias.h"

typedef struct {
    FusionAhrs ahrs;
    FusionBias bias;
} Tracker;

JNIEXPORT jlong JNICALL Java_com_rokid_stabledemo_FusionBridge_create(
        JNIEnv *env, jclass type, jfloat sample_rate, jfloat range_rad) {
    (void) env; (void) type;
    Tracker *tracker = calloc(1, sizeof(Tracker));
    if (!tracker) return 0;
    FusionAhrsInitialise(&tracker->ahrs);
    FusionAhrsSettings settings = fusionAhrsDefaultSettings;
    settings.sampleRate = sample_rate;
    settings.convention = FusionConventionEnu;
    settings.gain = 0.5f;
    settings.gyroscopeRange = FusionRadiansToDegrees(range_rad);
    settings.accelerationRejection = 10.0f;
    settings.rejectionTimeout = 5.0f;
    FusionAhrsSetSettings(&tracker->ahrs, &settings);
    FusionBiasInitialise(&tracker->bias);
    FusionBiasSettings bias_settings = fusionBiasDefaultSettings;
    bias_settings.sampleRate = sample_rate;
    FusionBiasSetSettings(&tracker->bias, &bias_settings);
    return (jlong) (intptr_t) tracker;
}

JNIEXPORT void JNICALL Java_com_rokid_stabledemo_FusionBridge_destroy(
        JNIEnv *env, jclass type, jlong handle) {
    (void) env; (void) type;
    free((void *) (intptr_t) handle);
}

JNIEXPORT void JNICALL Java_com_rokid_stabledemo_FusionBridge_update(
        JNIEnv *env, jclass type, jlong handle, jfloat gx, jfloat gy, jfloat gz,
        jfloat ax, jfloat ay, jfloat az, jfloat dt, jfloatArray result) {
    (void) type;
    Tracker *tracker = (Tracker *) (intptr_t) handle;
    if (!tracker || !isfinite(dt) || dt <= 0.0f || dt > 0.1f ||
        !isfinite(gx) || !isfinite(gy) || !isfinite(gz) ||
        !isfinite(ax) || !isfinite(ay) || !isfinite(az) ||
        (*env)->GetArrayLength(env, result) < 5) return;
    FusionVector gyro = {.array = {FusionRadiansToDegrees(gx), FusionRadiansToDegrees(gy), FusionRadiansToDegrees(gz)}};
    FusionVector accel = {.array = {ax / 9.80665f, ay / 9.80665f, az / 9.80665f}};
    FusionAhrsSetSamplePeriod(&tracker->ahrs, dt);
    gyro = FusionBiasUpdate(&tracker->bias, gyro);
    FusionAhrsUpdateNoMagnetometer(&tracker->ahrs, gyro, accel);
    FusionQuaternion q = FusionAhrsGetQuaternion(&tracker->ahrs);
    jfloat output[5] = {q.element.x, q.element.y, q.element.z, q.element.w,
                       FusionAhrsGetFlags(&tracker->ahrs).startup ? 1.0f : 0.0f};
    (*env)->SetFloatArrayRegion(env, result, 0, 5, output);
}
