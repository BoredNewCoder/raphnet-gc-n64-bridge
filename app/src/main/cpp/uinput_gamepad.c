// Real Linux uinput virtual gamepad backend for RaphnetInjectorService.
// Runs inside the Shizuku shell-UID process (/dev/uinput is only writable there, not from
// the main app's UID — same constraint discovered and documented in the sibling
// 8bitdo-xbox-bridge project).
//
// Button/axis codes verified against the real kernel uapi header
// (torvalds/linux include/uapi/linux/input-event-codes.h), not assumed.
//
// The whole point of this app: raphnet's GC/N64-to-USB adapter reports D-Pad Left as raw
// BTN_MODE, which Android intercepts system-wide before any app (RetroArch included) ever
// sees it — same class of reservation as an Xbox controller's Guide button. Reading the
// adapter's raw USB HID reports directly (bypassing Android's InputManager entirely, see
// RaphnetBridgeService.kt) and re-emitting through THIS device instead lets us choose a
// different, non-reserved code for that one bit. Every other bit is re-emitted under the
// exact same code the adapter's own kernel hid-generic driver already used (confirmed via
// live evdev capture earlier this session), so existing RetroArch binds for every other
// button/axis keep working unchanged — only the previously-unbindable D-Pad Left needs a
// fresh bind, onto BTN_0 (0x100, aka BTN_MISC).
//
// Real substitute-code history: BTN_TRIGGER_HAPPY1 (0x2c0) was tried first — a real Linux
// code meant for exactly this "extra button, no standard meaning" case — but live-tested
// and confirmed dead: pulled this Shield's actual /system/usr/keylayout/Generic.kl and it
// has NO entry at all for 0x2c0/704, so Android never turns the raw EV_KEY press into a
// KeyEvent (unmapped keys aren't delivered), meaning RetroArch's bind-capture screen never
// saw it even though `getevent` on our own device showed it firing correctly. BTN_0
// (0x100/256 decimal) IS mapped in that same real file ("key 256 BUTTON_1"), confirmed by
// reading it directly rather than assumed.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <android/log.h>

#define LOG_TAG "RaphnetUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void write_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        LOGE("write_event(type=%u code=%u) failed: %s", type, code, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_vanzetta_raphnetbridge_GamepadInjectorService_nativeOpenUinput(JNIEnv *env, jobject thiz, jstring jname) {
    (void) thiz;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    int fd = open("/dev/uinput", O_RDWR);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // Bit0..15 of the raphnet report, in order, re-emitted under the SAME evdev codes the
    // adapter's own kernel driver already used (real, live-captured this session) — except
    // bit12 (D-Pad Left), the one actually broken. See file header.
    static const int buttons[] = {
        BTN_SOUTH,              // bit0  A
        BTN_EAST,               // bit1  B
        BTN_C,                  // bit2  Z trigger (N64) / Z (GC)
        BTN_NORTH,              // bit3  Start
        BTN_WEST,               // bit4  L
        BTN_Z,                  // bit5  R
        BTN_TL,                 // bit6  C-Up (N64 only)
        BTN_TR,                 // bit7  C-Down (N64) / X (GC)
        BTN_TL2,                // bit8  C-Left (N64) / Y (GC)
        BTN_TR2,                // bit9  C-Right (N64 only)
        BTN_SELECT,             // bit10 D-Pad Up
        BTN_START,              // bit11 D-Pad Down
        BTN_0,                  // bit12 D-Pad Left — THE FIX (was BTN_MODE, system-reserved)
        BTN_THUMBL,             // bit13 D-Pad Right
        BTN_THUMBR,             // bit14 unused (always 0 on real hardware)
    };
    for (size_t i = 0; i < sizeof(buttons) / sizeof(buttons[0]); i++) {
        ioctl(fd, UI_SET_KEYBIT, buttons[i]);
    }

    static const int absAxes[] = { ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ };
    for (size_t i = 0; i < sizeof(absAxes) / sizeof(absAxes[0]); i++) {
        ioctl(fd, UI_SET_ABSBIT, absAxes[i]);
    }

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x289b;   // real raphnet vendor ID (10395 decimal), confirmed via dumpsys usb
    dev.id.product = 0x0060;  // real raphnet GC/N64-to-USB v3.6 product ID (96 decimal)
    dev.id.version = 1;

    // Main stick + C-stick: firmware centers both at 16000 with a ±16000 real range
    // (confirmed from raphnet's own usbpad.c: "xval += 16000" after a -16000..+16000 scale)
    // — Java side pre-subtracts the 16000 offset before calling nativeSendReport, so the
    // range declared here is the already-centered -16000..16000 real value.
    dev.absmin[ABS_X] = -16000; dev.absmax[ABS_X] = 16000;
    dev.absmin[ABS_Y] = -16000; dev.absmax[ABS_Y] = 16000;
    dev.absmin[ABS_RX] = -16000; dev.absmax[ABS_RX] = 16000;
    dev.absmin[ABS_RY] = -16000; dev.absmax[ABS_RY] = 16000;
    // Triggers: firmware baseline is also 16000, deflecting positive only in the default
    // (non-FULL_SLIDERS) mode — Java side pre-subtracts 16000 the same way.
    dev.absmin[ABS_Z] = 0; dev.absmax[ABS_Z] = 16000;
    dev.absmin[ABS_RZ] = 0; dev.absmax[ABS_RZ] = 16000;

    if (write(fd, &dev, sizeof(dev)) < 0) {
        LOGE("write uinput_user_dev failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    (*env)->ReleaseStringUTFChars(env, jname, name);
    LOGI("uinput gamepad created, fd=%d", fd);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_vanzetta_raphnetbridge_GamepadInjectorService_nativeCloseUinput(JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
    }
}

// buttons: bit N corresponds to the Nth entry in the `buttons[]` table above (bit15/the
// report's 16th button is never emitted — real hardware never sets it).
JNIEXPORT void JNICALL
Java_com_vanzetta_raphnetbridge_GamepadInjectorService_nativeSendReport(
        JNIEnv *env, jobject thiz, jint fd,
        jint buttonBits, jint x, jint y, jint cx, jint cy, jint lt, jint rt) {
    (void) env; (void) thiz;
    if (fd < 0) return;

    static const int buttonCodes[] = {
        BTN_SOUTH, BTN_EAST, BTN_C, BTN_NORTH, BTN_WEST, BTN_Z,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
        BTN_0, BTN_THUMBL, BTN_THUMBR,
    };
    for (size_t i = 0; i < sizeof(buttonCodes) / sizeof(buttonCodes[0]); i++) {
        write_event(fd, EV_KEY, buttonCodes[i], (buttonBits & (1 << i)) ? 1 : 0);
    }

    write_event(fd, EV_ABS, ABS_X, x);
    write_event(fd, EV_ABS, ABS_Y, y);
    write_event(fd, EV_ABS, ABS_RX, cx);
    write_event(fd, EV_ABS, ABS_RY, cy);
    write_event(fd, EV_ABS, ABS_Z, lt);
    write_event(fd, EV_ABS, ABS_RZ, rt);

    write_event(fd, EV_SYN, SYN_REPORT, 0);
}
