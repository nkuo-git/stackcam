# StackCam

多幀疊圖相機 app（Pixel 5 / Android 8.0+）。Kotlin + CameraX，package `com.nick.stackcam`。

模式：一般疊圖（8 張）、夜景（15 張）、HDR 強對比（-2/0/+2 EV）、超解析變焦（2x / 3x）。
照片存到 `Pictures/StackCam`。

## 編譯

```bash
source ~/tools/env.sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 簽章金鑰（很重要）

APK 一律用 debug keystore `~/.android/debug.keystore` 簽章（`app/build.gradle.kts` 有明確指定）。
**之後的每一版都必須用同一把金鑰**，否則手機會拒絕覆蓋安裝，只能先解除安裝（照片不受影響，但 app 設定會清掉）。

- 備份：`/workspace/keystore-backup/debug.keystore`
  （憑證 SHA-256 `31:12:CA:35:BA:4E:65:7A:0F:96:DE:51:B7:A5:95:D9:60:13:8E:A2:10:B0:53:AB:29:A4:19:BF:14:C6:C2:58`）
- 換電腦或金鑰遺失時：`cp /workspace/keystore-backup/debug.keystore ~/.android/debug.keystore`
- 金鑰不放進這個公開儲存庫（`.gitignore` 已排除 `*.keystore`）。

## App 內自動更新

右上角的「v2.1 · 檢查更新」按鈕會查詢
`https://api.github.com/repos/nkuo-git/stackcam/releases/latest`（不需登入），
讀取 `tag_name`（如 `v2.2`）、第一個 `.apk` 附件與說明文字。
有新版就顯示更新內容 →「立即更新」→ app 內下載（有進度條）→ 系統安裝畫面。
第一次更新時會請使用者到設定允許「安裝不明應用程式」。
開啟 app 時也會在背景安靜檢查，有新版時按鈕會變成黃色「● 有新版 vX.Y」。

注意：tag 的版本號要大於 app 的 `versionName`，APK 的 `versionCode` 也必須比已安裝的大，否則 app 會拒絕安裝。

## 發佈新版流程

1. 修改 `app/build.gradle.kts`：`versionName = "X.Y"`、`versionCode` +1。
2. 編譯：`source ~/tools/env.sh && ./gradlew testDebugUnitTest assembleDebug`
3. `cp app/build/outputs/apk/debug/app-debug.apk StackCam.apk`
4. `git commit -am "vX.Y" && git push`
5. `gh release create vX.Y StackCam.apk --repo nkuo-git/stackcam --title "StackCam X.Y" --notes "更新內容…"`

或用腳本一次做完（自動改版本號、編譯、測試、commit、tag、push、發佈）：

```bash
./release.sh 2.2 "- 新增 XXX\n- 修正 YYY"
./release.sh 2.2 --notes-file notes.md
./release.sh 2.1 "..." --dry-run    # 只編譯和測試，不 commit 也不發佈
```

需要先 `gh auth login`，且 GitHub 上已有公開儲存庫 `nkuo-git/stackcam`。
