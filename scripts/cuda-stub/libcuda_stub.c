/*
 * Stand-in for libcuda.so.1 (the NVIDIA CUDA *driver* library).
 *
 * Why this file exists
 * -------------------
 * libNSurveillance.so is linked with a hard DT_NEEDED entry on libcuda.so.1,
 * unconditionally - the vendor did not make it a runtime dlopen(). So the dynamic
 * loader refuses to load libNSurveillance.so unless *something* named libcuda.so.1
 * provides the 7 symbols below, even when the engine runs purely on CPU
 * (sentiveillance.sdk.use-gpu=false, which is the only supported mode on a
 * Kepler-era card: the SDK bundles TensorRT 10, which needs far newer hardware).
 *
 * Installing a real NVIDIA driver to satisfy a link-time reference is the wrong
 * trade: the driver packages add kernel modules, modprobe rules, firmware and
 * initramfs hooks that take over the display at boot. This file is a plain
 * userspace library instead - it is loaded only into the JVM process that runs
 * this application, and the kernel and display server never see it.
 *
 * The implementations answer truthfully: there is no CUDA device here. If the SDK
 * ever probes for a GPU it gets a clean "no device" instead of a crash.
 *
 * Build: scripts/build-cuda-stub.sh
 * Undo:  delete $SENTIVEILLANCE_SDK_ROOT/Lib/Linux_x86_64/libcuda.so.1
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
#include <stddef.h>
#include <string.h>

/* From cuda.h - we deliberately do not depend on the CUDA headers. */
#define CUDA_SUCCESS             0
#define CUDA_ERROR_NO_DEVICE   100

typedef int CUresult;
typedef int CUdevice;

CUresult cuInit(unsigned int flags) {
    (void) flags;
    return CUDA_ERROR_NO_DEVICE;
}

CUresult cuDeviceGetCount(int *count) {
    if (count != NULL) {
        *count = 0;
    }
    return CUDA_SUCCESS;          /* query succeeded; the answer is "zero GPUs" */
}

CUresult cuDeviceGet(CUdevice *device, int ordinal) {
    (void) ordinal;
    if (device != NULL) {
        *device = 0;
    }
    return CUDA_ERROR_NO_DEVICE;
}

CUresult cuDeviceGetAttribute(int *pi, int attrib, CUdevice dev) {
    (void) attrib;
    (void) dev;
    if (pi != NULL) {
        *pi = 0;
    }
    return CUDA_ERROR_NO_DEVICE;
}

CUresult cuDeviceGetName(char *name, int len, CUdevice dev) {
    (void) dev;
    if (name != NULL && len > 0) {
        name[0] = '\0';
    }
    return CUDA_ERROR_NO_DEVICE;
}

CUresult cuDeviceGetUuid(void *uuid, CUdevice dev) {
    (void) dev;
    if (uuid != NULL) {
        memset(uuid, 0, 16);      /* sizeof(CUuuid) */
    }
    return CUDA_ERROR_NO_DEVICE;
}

CUresult cuGetErrorString(CUresult error, const char **pStr) {
    (void) error;
    if (pStr != NULL) {
        *pStr = "no CUDA driver present (libcuda.so.1 stub, CPU-only build)";
    }
    return CUDA_SUCCESS;
}
