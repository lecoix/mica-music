# sylvakru USB transport (vendored)

This module vendors the low-level Android usbfs isochronous transport from the local `sylvakru-usb` reference tree under Apache-2.0.

- Upstream project: `AfalpHy/sylvakru`
- License: Apache License 2.0 (`LICENSE` in this module)
- Vendored native source: `src/main/cpp/usb_exclusive_engine.cpp`
  - import SHA-256: `29F7443ACF19F734550F7B610DF863F18276B5F97F0643D11BF253B0167E1A4B`
- Vendored DSD source: `src/main/kotlin/com/afalphy/sylvakru/UsbDsd.kt`
  - import SHA-256: `A016939D28564A954416940711DCB4992DC680D165D86FEA035EA351A7DBCA63`
- Vendored diagnostics/quirk loader are also kept byte-for-byte aligned with the audited reference copy.
- Import date: 2026-08-20

The C++ transport, DSD reader/packetizers, diagnostics, and quirk loader are intentionally kept byte-for-byte identical to the audited reference sources. Mica-specific build glue and Media3 adaptation live outside those files.

`src/main/assets/usb_dac_quirks.json` started from the reference asset and is the intended device-data extension point. The rewrite adds a device-verified Fosi Audio SK02 (`0x262a:0x0001`) clock quirk after hardware validation showed UAC2 `GET_CUR` returning garbage (`2 Hz`) while `SET_CUR` succeeded and feedback/write cadence became correct with the reference project's `skipGetCurValidation` policy.
