# How openssl-install was built

`openssl-install/` holds the static OpenSSL 3.5 that `app/src/main/cpp/Android.mk` links into
`libshieldvpn.so`. It is built from `openssl/` with only what an OpenVPN client uses, and with
each function in its own section, so the app's linker (`--gc-sections`) can drop the rest.
This cut the native library from 7.5 MB to 5.6 MB.

```
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/26.1.10909125
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH
cd openssl
perl ./Configure android-arm64 -D__ANDROID_API__=28 --prefix=$PWD/../openssl-install \
  no-shared no-tests no-docs no-apps \
  no-quic no-dtls no-srp no-psk no-ct no-ocsp no-ts no-cms no-cmp no-http \
  no-sm2 no-sm3 no-sm4 no-aria no-camellia no-seed no-idea no-cast no-whirlpool no-rmd160 \
  no-mdc2 no-siphash no-scrypt no-ec2m no-engine no-dso no-comp no-ui-console no-gost \
  no-ssl-trace no-unit-test no-padlockeng no-afalgeng no-capieng no-loadereng \
  no-devcryptoeng no-ktls no-fips no-acvp-tests no-sctp \
  -ffunction-sections -fdata-sections
make -j8 build_libs && make install_dev
```

Kept on purpose, because OpenVPN 3 or its profiles use them: AES (CBC, CTR, GCM), ChaCha20-Poly1305,
DES and 3DES, Blowfish and RC2 (old `.p12` files), MD4, MD5, SHA-1 and SHA-2, RSA, EC,
Ed25519/Ed448, DH and PKCS#12. With `no-dso` the legacy provider is built into libcrypto,
so "Allow legacy ciphers" (BF-CBC) works.

`openssl-install.full/` is the earlier full build, kept only as a fallback. It can be deleted.
