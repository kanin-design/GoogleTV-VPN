// JNI bridge to wireguard-go, as built into libwg-go.so by the official WireGuard Android library.
//
// That library exports wgTurnOn, wgTurnOff, wgGetSocketV4/V6, wgGetConfig and wgVersion as C
// functions (next to its own JNI functions, which belong to its GoBackend class). They are looked
// up at run time, so this module links against nothing but libdl, and a build without WireGuard's
// library simply reports that it is missing.

#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

struct go_string {
    const char *str;
    long n;
};

typedef int (*turn_on_fn)(struct go_string ifname, int tun_fd, struct go_string settings);
typedef void (*turn_off_fn)(int handle);
typedef int (*socket_fn)(int handle);
typedef char *(*config_fn)(int handle);
typedef char *(*version_fn)(void);

static turn_on_fn turn_on;
static turn_off_fn turn_off;
static socket_fn socket_v4;
static socket_fn socket_v6;
static config_fn get_config;
static version_fn version;

static int ready(void) {
    if (turn_on) return 1;
    void *lib = dlopen("libwg-go.so", RTLD_NOW);
    if (lib == NULL) return 0;
    turn_off = (turn_off_fn)dlsym(lib, "wgTurnOff");
    socket_v4 = (socket_fn)dlsym(lib, "wgGetSocketV4");
    socket_v6 = (socket_fn)dlsym(lib, "wgGetSocketV6");
    get_config = (config_fn)dlsym(lib, "wgGetConfig");
    version = (version_fn)dlsym(lib, "wgVersion");
    turn_on_fn on = (turn_on_fn)dlsym(lib, "wgTurnOn");
    if (!turn_off || !socket_v4 || !socket_v6 || !get_config || !version) return 0;
    turn_on = on;
    return turn_on != NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_ready(JNIEnv *env, jclass cls) {
    return ready() ? JNI_TRUE : JNI_FALSE;
}

// Starts a tunnel on the interface [tunFd] (wireguard-go owns it from now on) with [settings] in
// the userspace configuration format. Returns a handle of 0 or more, or -1.
JNIEXPORT jint JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_turnOn(JNIEnv *env, jclass cls, jstring ifname, jint tunFd, jbyteArray settings) {
    if (!ready()) return -1;
    const char *name = (*env)->GetStringUTFChars(env, ifname, NULL);
    jsize n = (*env)->GetArrayLength(env, settings);
    char *text = malloc((size_t)n + 1);
    if (name == NULL || text == NULL) {
        if (name != NULL) (*env)->ReleaseStringUTFChars(env, ifname, name);
        free(text);
        return -1;
    }
    (*env)->GetByteArrayRegion(env, settings, 0, n, (jbyte *)text);
    text[n] = '\0';
    struct go_string goName = {name, (long)strlen(name)};
    struct go_string goSettings = {text, (long)n};
    int handle = turn_on(goName, tunFd, goSettings);
    // The settings hold the private key: wipe this copy.
    memset(text, 0, (size_t)n);
    free(text);
    (*env)->ReleaseStringUTFChars(env, ifname, name);
    return handle;
}

JNIEXPORT void JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_turnOff(JNIEnv *env, jclass cls, jint handle) {
    if (ready()) turn_off(handle);
}

JNIEXPORT jint JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_socketV4(JNIEnv *env, jclass cls, jint handle) {
    return ready() ? socket_v4(handle) : -1;
}

JNIEXPORT jint JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_socketV6(JNIEnv *env, jclass cls, jint handle) {
    return ready() ? socket_v6(handle) : -1;
}

// The device's state as "key=value" lines. It holds private_key, so callers read it and drop it.
JNIEXPORT jbyteArray JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_config(JNIEnv *env, jclass cls, jint handle) {
    if (!ready()) return NULL;
    char *text = get_config(handle);
    if (text == NULL) return NULL;
    size_t n = strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)text);
    memset(text, 0, n);
    free(text);
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_shieldvpn_vpn_WireGuardNative_version(JNIEnv *env, jclass cls) {
    if (!ready()) return NULL;
    char *v = version();
    if (v == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, v);
    free(v);
    return out;
}
