// JNI bridge between the Kotlin VpnService and the OpenVPN 3 core client API.
//
// The core runs its own event loop inside OpenVPNClient::connect(), which is
// called from a Kotlin thread. Everything the core needs from Android (TUN
// setup, socket protection, logs, state changes) is forwarded to the Kotlin
// NativeCallbacks object passed in at create().

#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <string>
#include <vector>

#include <openvpn/io/io.hpp>

#include "ovpncli.hpp"

#define TAG "ShieldVpnNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace ovpn = openvpn::ClientAPI;

namespace {

JavaVM *g_vm = nullptr;

JNIEnv *currentEnv() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

// A Java exception left pending makes the next JNI call abort the process, so each call
// into Kotlin is followed by this. Returns true when the call threw.
bool threw(JNIEnv *env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jclass g_string = nullptr;
jmethodID g_stringFromBytes = nullptr;

// A Java string from UTF-8 that may be invalid: server text and log lines come from outside.
// NewStringUTF wants modified UTF-8 and aborts on anything else under CheckJNI.
jstring javaString(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    if (bytes == nullptr) {
        threw(env);
        return nullptr;
    }
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = static_cast<jstring>(env->NewObject(g_string, g_stringFromBytes, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    if (threw(env)) return nullptr;
    return out;
}

// The bytes of a Kotlin ByteArray, which the app fills with UTF-8.
std::string utf8(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return "";
    const jsize n = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

class ShieldClient : public ovpn::OpenVPNClient {
  public:
    ShieldClient(JNIEnv *env, jobject callbacks) {
        callbacks_ = env->NewGlobalRef(callbacks);
        jclass local = env->GetObjectClass(callbacks);
        cls_ = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);

        onEvent_ = env->GetMethodID(cls_, "onEvent", "(Ljava/lang/String;Ljava/lang/String;Z)V");
        onLog_ = env->GetMethodID(cls_, "onLog", "(Ljava/lang/String;)V");
        newBuilder_ = env->GetMethodID(cls_, "newBuilder", "()Z");
        setRemote_ = env->GetMethodID(cls_, "setRemoteAddress", "(Ljava/lang/String;Z)Z");
        addAddress_ = env->GetMethodID(cls_, "addAddress", "(Ljava/lang/String;IZ)Z");
        addRoute_ = env->GetMethodID(cls_, "addRoute", "(Ljava/lang/String;IZ)Z");
        setMtu_ = env->GetMethodID(cls_, "setMtu", "(I)Z");
        setSession_ = env->GetMethodID(cls_, "setSessionName", "(Ljava/lang/String;)Z");
        addDns_ = env->GetMethodID(cls_, "addDns", "(Ljava/lang/String;)Z");
        establish_ = env->GetMethodID(cls_, "establish", "()I");
        protect_ = env->GetMethodID(cls_, "protectSocket", "(ILjava/lang/String;Z)Z");
        teardown_ = env->GetMethodID(cls_, "teardown", "(Z)V");
        pkiCert_ = env->GetMethodID(cls_, "pkiCertificate", "(Ljava/lang/String;)Ljava/lang/String;");
        pkiChain_ = env->GetMethodID(cls_, "pkiChain", "(Ljava/lang/String;)Ljava/lang/String;");
        pkiSign_ = env->GetMethodID(cls_, "pkiSign", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    }

    ~ShieldClient() override {
        JNIEnv *env = currentEnv();
        env->DeleteGlobalRef(callbacks_);
        env->DeleteGlobalRef(cls_);
    }

    bool autologin = false;

    /** Calls a Kotlin method that returns a String; "" when it returns null or throws. */
    template <typename... Args>
    std::string callString(JNIEnv *env, jmethodID method, Args... args) {
        auto result = static_cast<jstring>(env->CallObjectMethod(callbacks_, method, args...));
        if (threw(env)) return "";
        if (result == nullptr) return "";
        const char *chars = env->GetStringUTFChars(result, nullptr);
        std::string out = chars;
        env->ReleaseStringUTFChars(result, chars);
        env->DeleteLocalRef(result);
        return out;
    }

    // ---- Core -> app events -------------------------------------------------

    void event(const ovpn::Event &ev) override {
        JNIEnv *env = currentEnv();
        jstring name = javaString(env, ev.name);
        jstring info = javaString(env, ev.info);
        if (name != nullptr && info != nullptr) {
            env->CallVoidMethod(callbacks_, onEvent_, name, info, ev.error ? JNI_TRUE : JNI_FALSE);
            threw(env);
        }
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(info);
    }

    void log(const ovpn::LogInfo &li) override {
        JNIEnv *env = currentEnv();
        jstring text = javaString(env, li.text);
        if (text == nullptr) return;
        env->CallVoidMethod(callbacks_, onLog_, text);
        threw(env);
        env->DeleteLocalRef(text);
    }

    void acc_event(const ovpn::AppCustomControlMessageEvent &) override {}
    bool pause_on_connection_timeout() override { return false; }
    // ---- A certificate installed on the TV: the key stays in Android's keystore ----

    void external_pki_cert_request(ovpn::ExternalPKICertRequest &req) override {
        JNIEnv *env = currentEnv();
        jstring alias = javaString(env, req.alias);
        req.cert = callString(env, pkiCert_, alias);
        req.supportingChain = callString(env, pkiChain_, alias);
        env->DeleteLocalRef(alias);
        if (req.cert.empty()) {
            req.error = true;
            req.invalidAlias = true;
            req.errorText = "The certificate installed on this TV could not be read";
        }
    }

    void external_pki_sign_request(ovpn::ExternalPKISignRequest &req) override {
        JNIEnv *env = currentEnv();
        jstring alias = javaString(env, req.alias);
        jstring data = javaString(env, req.data);
        jstring algorithm = javaString(env, req.algorithm);
        jstring hashalg = javaString(env, req.hashalg);
        jstring saltlen = javaString(env, req.saltlen);
        req.sig = callString(env, pkiSign_, alias, data, algorithm, hashalg, saltlen);
        env->DeleteLocalRef(alias);
        env->DeleteLocalRef(data);
        env->DeleteLocalRef(algorithm);
        env->DeleteLocalRef(hashalg);
        env->DeleteLocalRef(saltlen);
        if (req.sig.empty()) {
            req.error = true;
            req.errorText = "The key installed on this TV could not sign";
        }
    }

    // ---- TUN builder: forwarded to VpnService.Builder on the Kotlin side ----

    bool tun_builder_new() override {
        JNIEnv *env = currentEnv();
        jboolean ok = env->CallBooleanMethod(callbacks_, newBuilder_);
        return !threw(env) && ok == JNI_TRUE;
    }

    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override {
        return callStrBool(setRemote_, address, ipv6);
    }

    bool tun_builder_add_address(const std::string &address,
                                 int prefix_length,
                                 const std::string & /*gateway*/,
                                 bool ipv6,
                                 bool /*net30*/) override {
        return callStrIntBool(addAddress_, address, prefix_length, ipv6);
    }

    bool tun_builder_add_route(const std::string &address,
                               int prefix_length,
                               int /*metric*/,
                               bool ipv6) override {
        return callStrIntBool(addRoute_, address, prefix_length, ipv6);
    }

    bool tun_builder_exclude_route(const std::string & /*address*/,
                                   int /*prefix_length*/,
                                   int /*metric*/,
                                   bool /*ipv6*/) override {
        // Android has no per-route exclusion in VpnService; the core copes
        // because the server address is protected at the socket level.
        return true;
    }

    bool tun_builder_reroute_gw(bool /*ipv4*/, bool /*ipv6*/, unsigned int /*flags*/) override {
        // Default-route changes arrive as explicit add_route() calls.
        return true;
    }

    bool tun_builder_set_mtu(int mtu) override {
        JNIEnv *env = currentEnv();
        jboolean ok = env->CallBooleanMethod(callbacks_, setMtu_, static_cast<jint>(mtu));
        return !threw(env) && ok == JNI_TRUE;
    }

    bool tun_builder_set_session_name(const std::string &name) override {
        return callStrBool(setSession_, name);
    }

    bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
        for (const auto &[priority, server] : dns.servers) {
            for (const auto &addr : server.addresses) {
                callStrBool(addDns_, addr.address);
            }
        }
        return true;
    }

    bool tun_builder_add_wins_server(const std::string & /*address*/) override { return true; }

    int tun_builder_establish() override {
        JNIEnv *env = currentEnv();
        jint fd = env->CallIntMethod(callbacks_, establish_);
        return threw(env) ? -1 : static_cast<int>(fd);
    }

    // The interface may outlive a session: the core keeps it while it reconnects (tunPersist).
    bool tun_builder_persist() override { return true; }

    void tun_builder_teardown(bool disconnect) override {
        JNIEnv *env = currentEnv();
        env->CallVoidMethod(callbacks_, teardown_, disconnect ? JNI_TRUE : JNI_FALSE);
        threw(env);
    }

    // The transport socket must bypass the tunnel, otherwise the VPN would
    // try to carry its own packets.
    bool socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6) override {
        JNIEnv *env = currentEnv();
        jstring host = javaString(env, remote);
        if (host == nullptr) return false;
        jboolean ok = env->CallBooleanMethod(callbacks_, protect_, static_cast<jint>(socket), host,
                                             ipv6 ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(host);
        return !threw(env) && ok == JNI_TRUE;
    }

  private:
    bool callStrBool(jmethodID method, const std::string &s, bool flag = false) {
        JNIEnv *env = currentEnv();
        jstring js = javaString(env, s);
        if (js == nullptr) return false;
        jboolean ok = env->CallBooleanMethod(callbacks_, method, js, flag ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(js);
        return !threw(env) && ok == JNI_TRUE;
    }

    bool callStrIntBool(jmethodID method, const std::string &s, int n, bool flag) {
        JNIEnv *env = currentEnv();
        jstring js = javaString(env, s);
        if (js == nullptr) return false;
        jboolean ok = env->CallBooleanMethod(callbacks_, method, js, static_cast<jint>(n),
                                             flag ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(js);
        return !threw(env) && ok == JNI_TRUE;
    }

    jobject callbacks_ = nullptr;
    jclass cls_ = nullptr;
    jmethodID onEvent_ = nullptr;
    jmethodID onLog_ = nullptr;
    jmethodID newBuilder_ = nullptr;
    jmethodID setRemote_ = nullptr;
    jmethodID addAddress_ = nullptr;
    jmethodID addRoute_ = nullptr;
    jmethodID setMtu_ = nullptr;
    jmethodID setSession_ = nullptr;
    jmethodID addDns_ = nullptr;
    jmethodID establish_ = nullptr;
    jmethodID protect_ = nullptr;
    jmethodID teardown_ = nullptr;
    jmethodID pkiCert_ = nullptr;
    jmethodID pkiChain_ = nullptr;
    jmethodID pkiSign_ = nullptr;
};

jstring errorOrEmpty(JNIEnv *env, bool error, const std::string &message) {
    if (!error) return env->NewStringUTF("");
    jstring out = javaString(env, message);
    return out != nullptr ? out : env->NewStringUTF("Unknown error");
}

// A whole number from our own options, or [fallback] when it is missing or not a number.
int number(const std::string &text, int fallback) {
    char *end = nullptr;
    const long n = std::strtol(text.c_str(), &end, 10);
    return text.empty() || *end != '\0' ? fallback : static_cast<int>(n);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("java/lang/String");
    g_string = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_stringFromBytes = env->GetMethodID(g_string, "<init>", "([BLjava/lang/String;)V");
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_create(JNIEnv *env, jclass /*cls*/, jobject callbacks) {
    return reinterpret_cast<jlong>(new ShieldClient(env, callbacks));
}

// The value of "key=value" on its own line in [options], or "" when it is not there.
std::string option(const std::string &options, const std::string &key) {
    const std::string prefix = key + "=";
    size_t start = 0;
    while (start < options.size()) {
        size_t end = options.find('\n', start);
        if (end == std::string::npos) end = options.size();
        if (options.compare(start, prefix.size(), prefix) == 0) return options.substr(start + prefix.size(), end - start - prefix.size());
        start = end + 1;
    }
    return "";
}

JNIEXPORT jstring JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_evaluate(JNIEnv *env, jclass /*cls*/, jlong handle, jbyteArray config, jbyteArray options) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    const std::string o = utf8(env, options);
    ovpn::Config cfg;
    cfg.content = utf8(env, config);
    // "asym" accepts compression the server pushes but never compresses what the TV sends.
    // The core rejects pushed compression by default, which drops the tunnel.
    const std::string compress = option(o, "compress");
    cfg.compressionMode = compress.empty() ? "asym" : compress;
    // Give up on a server that never answers, instead of retrying forever.
    cfg.connTimeout = number(option(o, "timeout"), 30);
    // Keep the VPN interface while the core reconnects (a timeout, the server restarting, a new
    // network). Closed in between, Android sends every app's traffic outside the tunnel until the
    // new session is up, kill switch or not; kept, it waits in the tunnel instead. A session that
    // needs other addresses or routes gets a new interface, which takes over before the old closes.
    cfg.tunPersist = true;
    cfg.protoOverride = option(o, "proto");
    cfg.tlsVersionMinOverride = option(o, "tlsMin");
    cfg.enableLegacyAlgorithms = option(o, "legacy") == "1";
    // A certificate installed on the TV: the core asks the app for it and for each signature.
    cfg.externalPkiAlias = option(o, "epki");
    // A profile without a client certificate, which signs in with a password alone.
    cfg.disableClientCert = option(o, "nocert") == "1";

    ovpn::EvalConfig eval = client->eval_config(cfg);
    client->autologin = eval.autologin;
    return errorOrEmpty(env, eval.error, eval.message);
}

JNIEXPORT jboolean JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_needsCredentials(JNIEnv * /*env*/, jclass /*cls*/, jlong handle) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    return client->autologin ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_provideCredentials(JNIEnv *env, jclass /*cls*/, jlong handle,
                                                        jbyteArray username, jbyteArray password) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    // As UTF-8 bytes: GetStringUTFChars would send an emoji in a password as other bytes.
    ovpn::ProvideCreds creds;
    creds.username = utf8(env, username);
    creds.password = utf8(env, password);

    ovpn::Status status = client->provide_creds(creds);
    return errorOrEmpty(env, status.error, status.message);
}

// Blocks until the tunnel ends. Returns "" on a clean disconnect, otherwise the error.
JNIEXPORT jstring JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_connect(JNIEnv *env, jclass /*cls*/, jlong handle) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    ovpn::Status status = client->connect();
    return errorOrEmpty(env, status.error, status.message);
}

JNIEXPORT void JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_stop(JNIEnv * /*env*/, jclass /*cls*/, jlong handle) {
    reinterpret_cast<ShieldClient *>(handle)->stop();
}

// Bytes in and out over the transport since the session started.
JNIEXPORT jlongArray JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_stats(JNIEnv *env, jclass /*cls*/, jlong handle) {
    ovpn::TransportStats t = reinterpret_cast<ShieldClient *>(handle)->transport_stats();
    jlong values[2] = {static_cast<jlong>(t.bytesIn), static_cast<jlong>(t.bytesOut)};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, values);
    return out;
}

// Drops the transport and connects again after [seconds], for example after a network change.
JNIEXPORT void JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_reconnect(JNIEnv * /*env*/, jclass /*cls*/, jlong handle, jint seconds) {
    reinterpret_cast<ShieldClient *>(handle)->reconnect(seconds);
}

JNIEXPORT void JNICALL
Java_dev_shieldvpn_vpn_OpenVpnEngine_destroy(JNIEnv * /*env*/, jclass /*cls*/, jlong handle) {
    delete reinterpret_cast<ShieldClient *>(handle);
}

}  // extern "C"
