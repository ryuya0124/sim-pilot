# Third-party notices

SIM Pilot keeps third-party licensing separate from the proprietary license covering SIM Pilot itself.

| Component | Version | Copyright | License | Source |
| --- | --- | --- | --- | --- |
| NetMonster Core | 1.3.0 | Copyright 2019 Michal Mroček | Apache License 2.0 | https://github.com/mroczis/netmonster-core |
| AndroidX / Jetpack Compose | Versions pinned in `app/build.gradle.kts` | The Android Open Source Project contributors | Apache License 2.0 | https://developer.android.com/jetpack/androidx |
| Shizuku API / Provider | 13.1.5 | Copyright (c) 2021 RikkaW | MIT License | https://github.com/RikkaApps/Shizuku-API |

The complete Apache License 2.0 text used by NetMonster Core is included in
`app/src/main/assets/licenses/netmonster-core-LICENSE.txt` and is packaged in the APK.
The complete Shizuku MIT License is included in
`app/src/main/assets/licenses/shizuku-LICENSE.txt` and is packaged in the APK.

When adding or updating a dependency:

1. Verify its license from the upstream project and published artifact metadata.
2. Record the exact component version, copyright holder, license, and source here.
3. Package any required license or NOTICE text in `app/src/main/assets/licenses/`.
4. Update the in-app “ライセンス” page when user-visible attribution changes.
5. Do not remove upstream copyright, patent, trademark, or attribution notices.
