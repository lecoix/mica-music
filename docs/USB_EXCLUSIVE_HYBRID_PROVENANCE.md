# USB Exclusive Hybrid provenance

Captured on 2026-08-20 before importing source into the Hybrid worktree.

## Baseline

- Hybrid base: `0b6e982ad48d4128f7d689791734c845ec1b476e`
- Hybrid branch: `codex/usb-exclusive-hybrid-20260820`
- P1 HEAD: `6f119d86d3604c032a6d392a9e3b3b69220a8f99`
- Rewrite HEAD: `0b6e982ad48d4128f7d689791734c845ec1b476e`
- P1 dirty-manifest SHA-256: `6cd5787860dbe22ececbc83db2a15cfe9e694708beedfdc26408a56782bd40ea`
- Rewrite dirty-manifest SHA-256: `cc2716822b1de0016fb860d350a5b10b4696864025a355c3764d1ee8ba55a7ea`

Both sources were dirty. Their validation records are evidence and design input, not inherited Hybrid PASS results. P1 implementation files are not copied. Rewrite files are copied only when explicitly listed below.

## Rewrite candidate source hashes

```text
194f3eebcbe485fa2454865159a58716805867d8126cd1e4973f6a9f9cf8a519  app/src/main/java/com/mica/music/media/MicaUsbDsdRenderer.kt
07dd5995e1538283f72906b0c5a35cb3905ef188c578e63b053125ca8238a382  app/src/main/java/com/mica/music/media/MicaUsbExclusiveAudioSink.kt
807714313f6dbf1e4c1357f5513cdd47cd49e0bc3d396fb410dc672b07473329  app/src/main/java/com/mica/music/media/MicaUsbHiResPcmAudioSink.kt
c2da69231935f33d0a9e1f5cab4149f07d61bf2aaa85709ac0f95d69fac53dc3  app/src/main/java/com/mica/music/media/UsbExclusiveDeviceAccess.kt
1016a9bf3a7741af8fed729fa4da19af97c9342e019d91452324885c18b06190  app/src/main/java/com/mica/music/media/UsbExclusiveDiagnosticsReport.kt
7ff897be052a3f33fd1097c33875028595c09ffd9e6193f9cf734371b4f2ecd6  app/src/main/java/com/mica/music/media/UsbExclusiveRuntimeMonitor.kt
2e010aa8bf25d59e123e40ca03486d34f535e49b555f4fd6b9cb345363177b8b  docs/USB_EXCLUSIVE_REWRITE_REFERENCE_BASELINE.md
```

## Apache-2.0 reference transport hashes

The imported transport retains `third_party/sylvakru-usb-transport/LICENSE`.

```text
abcf35306f8d47b677833f82369229a0a616b6d9f40822f9e3c41e171d8e90b2  build.gradle.kts
71e9a7eeab84ef033446c91b65500d9c8bd4a7e3b2ea654670631950c3de6aa2  LICENSE
5f186b36e88d0790218727c84305828e7eb355ac1b4c37b6e9fb282fd0e84192  README.md
343d5be1ac17cdd45d1a2f2ddc616c509290e655869125a9422a96d88aa66066  src/main/AndroidManifest.xml
c070ad94c8704d826162bae179d850d8340828df8d0cb013c02ed3c1e26fc4af  src/main/assets/usb_dac_quirks.json
4c23e52f01d94ed84a4c1a38534a3d3bf0c72fe12396662b1e8ffbc599b60247  src/main/cpp/CMakeLists.txt
29f7443acf19f734550f7b610df863f18276b5f97f0643d11bf253b0167e1a4b  src/main/cpp/usb_exclusive_engine.cpp
f4d179460ecf4501de1b4ad4fc4fee9df8f0e0a70f680f1b1d1e7143522705a3  src/main/kotlin/com/afalphy/sylvakru/DsfPlanarBlockConverter.kt
28746dafb6e8fb4bc351441dbb27e5440a61dd111c10062d7f3c78db0c75737b  src/main/kotlin/com/afalphy/sylvakru/UsbDacQuirks.kt
030cf1889c53fd0efd679b6474d4f5bd7200977be4f3c7cfa18f0f491efc2c18  src/main/kotlin/com/afalphy/sylvakru/UsbDiagnostics.kt
a016939d28564a954416940711dcb4992dc680d165d86fea035ea351a7dbca63  src/main/kotlin/com/afalphy/sylvakru/UsbDsd.kt
50692f67c664159fdda3fc7e790c0d594d313deb6cadda3b20167dfc444099e7  src/main/kotlin/com/afalphy/sylvakru/UsbExclusiveAudioTransport.kt
2feb4cb308755fb7b75be2d63bac4cc1b614bb236b309e48483579d9b2980273  src/main/kotlin/com/afalphy/sylvakru/UsbExclusiveNative.kt
d7e540b21ab6f43a82784a2a818925a151192d4059243911a48dd52041df3c8e  src/main/kotlin/com/afalphy/sylvakru/UsbPcmIsoPacketizer.kt
6e535ba5caceae822d60f26c43e2fb4c9f22c72f8e2cc56bb472fda309b4097a  src/main/kotlin/com/afalphy/sylvakru/UsbStreamingTargetResolver.kt
89ef8f46813245ad21e28a6bb4d7e02086f3014a3ce897a71ac932ef13456039  src/test/kotlin/com/afalphy/sylvakru/DsfPlanarBlockConverterTest.kt
e721bc0be6f3945d734a69bedfcd23aba6480759e8a41ff379097dc5cfb9fdf2  src/test/kotlin/com/afalphy/sylvakru/UsbDacQuirksTest.kt
163123bdf06149f3662ce891fdc9485e8e5067aad6cc69342703de1fdc72bf3c  src/test/kotlin/com/afalphy/sylvakru/UsbDsdTest.kt
eabc76bf08eb150e7bc2db8108554c4b1a6ba5cbc609e3db26b6335672cae42c  src/test/kotlin/com/afalphy/sylvakru/UsbPcmIsoPacketizerTest.kt
```

Build outputs under `build/` and `.cxx/` are deliberately excluded.
