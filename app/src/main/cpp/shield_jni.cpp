// JNI bridge between the Kotlin VpnService and the OpenVPN 3 core client API.
//
// The core runs its own event loop inside OpenVPNClient::connect(), which is
// called from a Kotlin thread. Everything the core needs from Android (TUN
// setup, socket protection, logs, state changes) is forwarded to the Kotlin
// NativeCallbacks object passed in at create().

#include <android/log.h>
#include <jni.h>

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
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return "";
        }
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
        jstring name = env->NewStringUTF(ev.name.c_str());
        jstring info = env->NewStringUTF(ev.info.c_str());
        env->CallVoidMethod(callbacks_, onEvent_, name, info, ev.error ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(info);
    }

    void log(const ovpn::LogInfo &li) override {
        JNIEnv *env = currentEnv();
        jstring text = env->NewStringUTF(li.text.c_str());
        env->CallVoidMethod(callbacks_, onLog_, text);
        env->DeleteLocalRef(text);
    }

    void acc_event(const ovpn::AppCustomControlMessageEvent &) override {}
    bool pause_on_connection_timeout() override { return false; }
    // ---- A certificate installed on the TV: the key stays in Android's keystore ----

    void external_pki_cert_request(ovpn::ExternalPKICertRequest &req) override {
        JNIEnv *env = currentEnv();
        jstring alias = env->NewStringUTF(req.alias.c_str());
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
        jstring alias = env->NewStringUTF(req.alias.c_str());
        jstring data = env->NewStringUTF(req.data.c_str());
        jstring algorithm = env->NewStringUTF(req.algorithm.c_str());
        jstring hashalg = env->NewStringUTF(req.hashalg.c_str());
        jstring saltlen = env->NewStringUTF(req.saltlen.c_str());
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
        return currentEnv()->CallBooleanMethod(callbacks_, newBuilder_) == JNI_TRUE;
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
        return currentEnv()->CallBooleanMethod(callbacks_, setMtu_, static_cast<jint>(mtu)) == JNI_TRUE;
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
        return static_cast<int>(currentEnv()->CallIntMethod(callbacks_, establish_));
    }

    bool tun_builder_persist() override { return false; }

    void tun_builder_teardown(bool disconnect) override {
        currentEnv()->CallVoidMethod(callbacks_, teardown_, disconnect ? JNI_TRUE : JNI_FALSE);
    }

    // The transport socket must bypass the tunnel, otherwise the VPN would
    // try to carry its own packets.
    bool socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6) override {
        JNIEnv *env = currentEnv();
        jstring host = env->NewStringUTF(remote.c_str());
        jboolean ok = env->CallBooleanMethod(callbacks_, protect_, static_cast<jint>(socket), host,
                                             ipv6 ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(host);
        return ok == JNI_TRUE;
    }

  private:
    bool callStrBool(jmethodID method, const std::string &s, bool flag = false) {
        JNIEnv *env = currentEnv();
        jstring js = env->NewStringUTF(s.c_str());
        jboolean ok = env->CallBooleanMethod(callbacks_, method, js, flag ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(js);
        return ok == JNI_TRUE;
    }

    bool callStrIntBool(jmethodID method, const std::string &s, int n, bool flag) {
        JNIEnv *env = currentEnv();
        jstring js = env->NewStringUTF(s.c_str());
        jboolean ok = env->CallBooleanMethod(callbacks_, method, js, static_cast<jint>(n),
                                             flag ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(js);
        return ok == JNI_TRUE;
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
    return env->NewStringUTF(error ? message.c_str() : "");
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
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
Java_dev_shieldvpn_vpn_OpenVpnEngine_evaluate(JNIEnv *env, jclass /*cls*/, jlong handle, jstring config, jstring options) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    const char *text = env->GetStringUTFChars(config, nullptr);
    const char *opts = env->GetStringUTFChars(options, nullptr);
    const std::string o = opts;
    ovpn::Config cfg;
    cfg.content = text;
    // "asym" accepts compression the server pushes but never compresses what the TV sends.
    // The core rejects pushed compression by default, which drops the tunnel.
    const std::string compress = option(o, "compress");
    cfg.compressionMode = compress.empty() ? "asym" : compress;
    // Give up on a server that never answers, instead of retrying forever.
    const std::string timeout = option(o, "timeout");
    cfg.connTimeout = timeout.empty() ? 30 : std::stoi(timeout);
    cfg.protoOverride = option(o, "proto");
    cfg.tlsVersionMinOverride = option(o, "tlsMin");
    cfg.enableLegacyAlgorithms = option(o, "legacy") == "1";
    // A certificate installed on the TV: the core asks the app for it and for each signature.
    cfg.externalPkiAlias = option(o, "epki");
    // A profile without a client certificate, which signs in with a password alone.
    cfg.disableClientCert = option(o, "nocert") == "1";
    env->ReleaseStringUTFChars(options, opts);
    env->ReleaseStringUTFChars(config, text);

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
                                                        jstring username, jstring password) {
    auto *client = reinterpret_cast<ShieldClient *>(handle);
    const char *user = env->GetStringUTFChars(username, nullptr);
    const char *pass = env->GetStringUTFChars(password, nullptr);
    ovpn::ProvideCreds creds;
    creds.username = user;
    creds.password = pass;
    env->ReleaseStringUTFChars(username, user);
    env->ReleaseStringUTFChars(password, pass);

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
