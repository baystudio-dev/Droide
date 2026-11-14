# Current native JNI rebuild gate

The targeted export fix adds `NativeFilePublication.exportDescriptorLock(Int, Boolean): Int`, JVM descriptor `(IZ)I`. It REQUIRES freshly built Android `libdroide_files.so` for every packaged ABI. A host `.so` passing the symbol check is not an Android artifact and must never be copied into an APK.

The normal `current_debug_inputs.py` and `_v2.py` now invoke `verify_composite_jni(root, composite)` before their existing composite validation. Existing Gradle `verifyCurrentComposite` preBuild/check dependency is unchanged. The check validates the exact current Kotlin JNI method-name set against loader-linked, defined, visible function exports in each of the four existing ABI directories. It supplements, never replaces, source/context receipts, Android ELF/API/NDK checks, descriptor certification and final APK gates.

## Portable4064 adapter is a separate path

The historical `current_debug_inputs_portable4064.py` imports legacy `sha`/`regular` but DOES NOT call `legacy.verify`; importing legacy does not execute this guard. Its immutable4064 source count/pins should reject the successor tree. Do not relax or reuse its old manifest/receipt.

When producing a reviewed successor portable adapter:
1. Import `verify_composite_jni` from `current_native_jni_guard`.
2. In its `verify(root, composite, context)`, after the selected-root and nonlinked-root checks and BEFORE accepting context/composite bytes, call `verify_composite_jni(root, composite)`.
3. Pin the new guard, adapter, full source inventory and rebuilt composite in the successor manifest/context. Preserve exact membership/hash enforcement; do not change4064 to a larger number without a new reviewed manifest.
4. Add the same hook regression used by `test_both_current_validators_invoke_guard_before_external_verifiers`, targeting the successor portable module. Verify an old composite is rejected with missing `exportDescriptorLock`; a missing ABI, undefined/hidden export, and symbol-name-only appended bytes are rejected.
5. Confirm the selected Gradle branch invokes that successor adapter and still gates preBuild/check. Do not claim portable coverage until this test/build wiring is verified.

## Mandatory real Android build work

- Merge the targeted native C/Kotlin delta plus this guard. The guard fails deliberately if the new Kotlin declaration is not present.
- Existing `filesystem_native_v1` now parses `Boolean -> Z` and emits `jboolean`; the input contract is now reviewed for18 methods, adding only `(IZ)I` and the exact C/Kotlin source hashes from the reviewed export delta. All previous17 descriptors, CMake/Termux pins and tool pins remain unchanged. This updates INPUTS, not any produced build receipt; use the generated jboolean header in the real build.
- The unchanged `sourceArchiveSha256` denotes historical base-source ancestry, NOT an archive of the successor source. `filesystem-native-input-review.json` links this input update to reviewed export delta manifest `d0d2c5fb1f3d666a45067586152edc0732f8f62bbfbccb49b59209cbcbb81959`; no new archive hash is asserted.
- Rebuild all four ABI native payloads using the existing reviewed Android NDK/API configuration, then regenerate native/composite receipts from actual outputs. Preserve all previous17 functions and add exactly the new function.
- Reissue V2 bridge/source projection or successor portable context through its existing reviewed process. Old exact bridge/receipt pins must not be bypassed.
- Run full native ELF/descriptor validation, both relevant consumer path tests and final APK gates; inspect packaged `.so` for all ABI entries. Device-test export lock acquisition/release and concurrent-reader behavior. No Android rebuild or device validation was performed in this lane.
