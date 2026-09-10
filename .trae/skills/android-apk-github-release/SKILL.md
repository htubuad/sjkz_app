---
name: "android-apk-github-release"
description: "Builds Android debug APK and uploads it to the project's GitHub remote to provide a direct download link. Invoke when user asks to build/package the APK and share a download link."
---

# Android APK GitHub Release

Build the Android debug APK, push it to the project's GitHub remote, and return a direct raw download URL.

## When to Invoke

- User asks to "build APK", "package the app", "打包", or "生成安装包"
- User asks for a download link to the latest APK
- User says "更新一下安装包" or "给我下载链接"

## Workflow

### 1. Build the APK

From the Android project root, run the debug assemble task:

```bash
./gradlew assembleDebug
```

Output path: `app/build/outputs/apk/debug/app-debug.apk`

> If the project uses a Gradle wrapper, verify it exists (`./gradlew`). If Gradle times out fetching dependencies, retry — network to GitHub/Maven repositories can be flaky.

### 2. Verify the APK exists

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Confirm the size is reasonable (several MB, not a stub).

### 3. Force-add the APK (bypass .gitignore)

The `app/build/` directory is typically gitignored. Use `-f` to stage the APK:

```bash
git add -f app/build/outputs/apk/debug/app-debug.apk
```

Also stage any modified source files the user wants included in this release.

### 4. Commit

```bash
git commit -m "build: update debug APK"
```

### 5. Push to the remote

```bash
git push origin <branch>
```

Detect the current branch with `git branch --show-current`. If the push fails due to authentication, surface the error to the user — do not attempt to fabricate credentials.

### 6. Return the download link

Construct the raw URL from the remote:

```
https://github.com/<owner>/<repo>/raw/<branch>/app/build/outputs/apk/debug/app-debug.apk
```

Verify it returns HTTP 200 with the expected content length before handing it to the user:

```bash
curl -sI -L "https://github.com/<owner>/<repo>/raw/<branch>/app/build/outputs/apk/debug/app-debug.apk" | grep -iE "HTTP/|content-length"
```

## Notes

- Only use this for **debug** APKs. Release APKs should go through the official signing/release process.
- The APK is committed directly to the repo (not GitHub Releases) for simplicity. If the file exceeds 100 MB, GitHub will reject the push — use Git LFS or GitHub Releases instead.
- Do not include any credentials, tokens, or internal URLs in the skill or commits.
