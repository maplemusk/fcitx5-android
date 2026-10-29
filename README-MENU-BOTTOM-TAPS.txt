Fcitx5 Android keyboard menu: bottom-edge tap mitigation

Targets: the grid of buttons/icons in the keyboard status/options menu.
Symptom: taps stop working at the very bottom; moving slightly up restores them.

The menu's RecyclerView has its overscroll/stretch effect disabled. Normal list
scrolling and the existing button actions remain in place. In RecyclerView 1.4.0,
an active bottom edge effect can intercept ACTION_DOWN at the scroll boundary
instead of delivering the tap to an item. Android 12+ exposes edge distance;
scrolling slightly away bypasses that interception condition.
This matches the reported behavior, but the affected device has not been tested.
This patch targets the menu grid; separate native popup lists are outside its scope.

Install on Windows

1. Close Android Studio's active build, then back up the current source project.
2. Extract this ZIP into the actual project root containing gradlew.bat and
   settings.gradle.kts. Merge folders and replace StatusAreaWindow.kt.
   Apply it on top of the previously supplied schema-font app source update.
3. Rebuild the main app:
     .\gradlew.bat :app:assembleRelease
4. Sign with the SAME certificate as the currently installed custom app, and
   install the resulting main-app APK. Keep the installed Rime plugin and data.
5. Open the keyboard menu. Scroll firmly to the very bottom, release the finger,
   then tap several menu buttons without first scrolling upward. Repeat after
   closing/reopening the keyboard and while using Wubi06/86/98.

If taps still fail, report whether this is the grid or a smaller popup list,
plus device model and Android version. Font resets/reimports are not required
for this scroll-boundary mitigation. No Rime Deploy is required by this source change.

源码应用方法：将ZIP内app目录合并到原项目根目录，覆盖StatusAreaWindow.kt。
执行 .\gradlew.bat :app:assembleRelease，用原签名安装主程序，然后直接测试
键盘菜单在最底部能否点击。此补丁只关闭菜单按钮网格的越界滚动特效；
无需重装字体、导入码表或重新部署Rime。若实际卡住的是较小的弹出列表，
请说明，这个补丁的作用范围是菜单网格。

Verification: :app:compileDebugKotlin passed; diff whitespace and patch reverse
application checks passed. No new unit test merely asserting a view property was
added. No signed APK was produced and no Android-device touch test was run here.

Previous schema-font commit: 09a0fbf431854c546a05e356c895063ae44f7c91
New source commit: fb785ec25ccb91ec70f68850637fa5b5a61f7ad5
Included Git patch is optional; use either the file overlay or the patch.
