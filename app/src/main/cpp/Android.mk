LOCAL_PATH := $(call my-dir)

# Project root and vendored sources (see third_party/).
ROOT := $(LOCAL_PATH)/../../../..
TP   := $(ROOT)/third_party
# Source paths compiled into the library (asserts' __FILE__) start at the project, not at the
# folders of the machine that built it.
PREFIX_MAP := -ffile-prefix-map=$(abspath $(ROOT))=.
OVPN := $(TP)/openvpn3
# OpenSSL is built once per ABI (third_party/OPENSSL-BUILD.md).
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
OSSL := $(TP)/openssl-install-armeabi-v7a
else
OSSL := $(TP)/openssl-install
endif

include $(CLEAR_VARS)
LOCAL_MODULE := crypto_static
LOCAL_SRC_FILES := $(OSSL)/lib/libcrypto.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ssl_static
LOCAL_SRC_FILES := $(OSSL)/lib/libssl.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := shieldvpn

LOCAL_SRC_FILES := \
    shield_jni.cpp \
    $(OVPN)/client/ovpncli.cpp \
    $(OVPN)/openvpn/crypto/data_epoch.cpp \
    $(OVPN)/openvpn/openssl/xkey/xkey_helper.c \
    $(OVPN)/openvpn/openssl/xkey/xkey_provider.c \
    $(TP)/xxhash/xxhash.c \
    $(TP)/lz4/lib/lz4.c \
    $(TP)/lz4/lib/lz4hc.c \
    $(TP)/lz4/lib/lz4frame.c

LOCAL_C_INCLUDES := \
    $(TP)/xxhash \
    $(OVPN) \
    $(OVPN)/client \
    $(TP)/asio-1.30.2/asio/include \
    $(TP)/fmt/include \
    $(TP)/lz4/lib \
    $(OSSL)/include

# ENABLE_EXTERNAL_PKI: a client key kept in Android's keystore signs through the app (xkey provider).
LOCAL_CFLAGS := -O2 -DASIO_STANDALONE -DUSE_ASIO -DHAVE_LZ4 -DUSE_OPENSSL -DFMT_HEADER_ONLY -DENABLE_EXTERNAL_PKI
LOCAL_CPPFLAGS := -std=c++20 -fexceptions -frtti -Wno-unused-parameter -Wno-deprecated-declarations
# Size: each function and constant in its own section, hidden unless JNI needs it, and the
# linker drops whatever nothing reaches, including unused parts of the static OpenSSL.
LOCAL_CFLAGS += -ffunction-sections -fdata-sections -fvisibility=hidden $(PREFIX_MAP)
LOCAL_CPPFLAGS += -fvisibility-inlines-hidden
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--icf=all -Wl,--exclude-libs,ALL -Wl,--version-script=$(LOCAL_PATH)/exports.map
LOCAL_LDLIBS := -llog -landroid
LOCAL_STATIC_LIBRARIES := ssl_static crypto_static

include $(BUILD_SHARED_LIBRARY)

# The bridge to wireguard-go (libwg-go.so, from the WireGuard Android library), found at run time.
include $(CLEAR_VARS)
LOCAL_MODULE := shieldwg
LOCAL_SRC_FILES := wg_jni.c
LOCAL_CFLAGS := -O2 -fvisibility=hidden -Wall $(PREFIX_MAP)
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
