# HyperOS Workbench AllApps Live-Blur Fix

[中文](#中文) · [English](#english)

一个 LSPosed 模块，修复 HyperOS 小米平板「工作台模式」下应用列表背景不跟随动态壁纸的问题。
An LSPosed module that fixes the app-drawer background in HyperOS tablet "Workbench (laptop) mode" so it
follows your live wallpaper instead of showing a stale static blur of the built-in default wallpaper.

---

## 中文

### 问题现象

小米平板开启「工作台模式」后，若把壁纸设为**第三方动态壁纸**（例如 Wallpaper Engine），
从 Dock 打开**应用列表**时，背景会变成**系统默认壁纸的静态模糊图**：

- 内容与当前壁纸**完全无关**（是内置默认壁纸，不是你在用的那张）
- 而且是**静态**的，不会动

而使用**系统自带动态壁纸**时，背景是正常的动态模糊画面。

### 修复效果

| | 修复前 | 修复后 |
|---|---|---|
| 背景内容 | 系统默认壁纸（与当前壁纸无关） | **当前壁纸的实时画面** |
| 是否动态 | 静态死图 | **实时跟随（会动）** |

### 原理

HyperOS 工作台模式（MiuiHome 里的 `laptop mode`）的应用列表背景其实有**两条路径同时生效**：

```java
// AllAppsAnimController.ensureBackgroundState(true)  —— 打开应用列表时
allAppBackground.setAlpha(1.0f);                    // ① 铺一张不透明的静态模糊图
WindowBlurUtils.setBlur(viewRootImpl, 0.4f, null);  // ② 同时开启窗口级实时模糊

// AllAppsBackground.showBlurEffect()
Bitmap bmp = WallpaperManagerCompat.getInstance(ctx).getCurrentWallpaperAsBitmap(ctx);
setBackground(new BitmapDrawable(getResources(), blur(bmp)));  // ① 这张图把 ② 完全盖住了
```

`getCurrentWallpaperAsBitmap()` 最终拿到的是 `MiuiWallpaperManager.getMiuiWallpaperPreview(1)` →
system_server 里缓存的 `/data/system/users/0/blurwallpaper`。对**第三方动态壁纸**，这份缓存是
用**内置默认壁纸**生成的（取不到动态壁纸的真实帧），于是背景既错误又不会动。

**窗口实时模糊（②）其实一直在正常运行**，只是被 ① 遮住了。

因此本模块**不去修那张图**，而是 hook `AllAppsBackground#showBlurEffect`：在它执行完原有逻辑
（Palette 取色、图标颜色提示模式、15% 前景遮罩、通知 `AllAppsController`）之后，把这个 View 的
背景清成透明 —— **让窗口自身的实时模糊露出来**，背景就变成实时模糊的动态壁纸。

### 适用范围

- **设备**：小米平板（使用「工作台模式 / laptop mode」的机型）
- **系统**：HyperOS 3.x（Android 16）
- **已验证**：Xiaomi Pad 7 Ultra（`25032RP42C` / `jinghu`），`OS3.0.301.0.WOXCNXM`（Android 16 / API 36）
- **前置条件**：已装 LSPosed（**API 101+**）且启用 Zygisk

> 其他机型的类名/逻辑若不同，模块会静默不生效（不会崩溃）—— 可以用 LSPosed 的日志确认是否 hook 成功。

### 安装

1. 从 [Releases](../../releases) 下载 `HyperOS-Workbench-Blur-Fix.apk`（或自行构建）
2. 安装到设备
3. 打开 LSPosed，启用模块 **AllApps 动态模糊修复**，作用域勾选 **「系统桌面」**（`com.miui.home`）
4. 重启桌面（安装后用模块自带的「重启系统桌面」按钮即可，见下）

**验证是否生效**：LSPosed 日志里应出现

```
(com.miui.home)[com.reasonix.allappsblurfix,AllAppsBlurFix,...] hook installed;
blur radius = 150
```

### 设置界面

模块带一个设置界面（桌面图标「**AllApps 动态模糊修复**」），可以：

- **调节模糊强度**：滑块范围 `0 ~ 150`（默认 150 = SurfaceFlinger 允许的窗口模糊上限）
  - 改完**立即生效**，不需要重启桌面 —— 即使应用列表已经开着，也会立刻重新应用
- **一键「重启系统桌面」**：更新模块（重装 APK）后，新代码要重启桌面进程才会加载。
  点这个按钮即可，不用再手动敲命令。**首次使用会请求 root 授权**（KernelSU/Magisk 弹窗，同意一次即可）。

> 模糊半径的实现：hook 了 `WindowBlurUtils.blurRadiusOfRatio(float)`，
> 原本应用列表只开到 `0.4f`（实际半径 ≈40），现在替换为你的配置值。
> 配置通过 LSPosed 的 remote preferences 与 hook 侧共享。

### 从源码构建

**依赖**

- JDK 17+
- Android SDK（含 `build-tools` 与 `platforms;android-34` 以上）
- LibXposed API（脚本自动下载）

**构建**

```bash
git clone https://github.com/mar08meow/HyperOS-Workbench-Blur-Fix.git
cd HyperOS-Workbench-Blur-Fix

# 1. 下载 LibXposed API（Maven Central 的 artifact 是 .aar，脚本会解出 classes.jar）
bash scripts/fetch-libxposed.sh

# 2. 指定 JDK 与 Android SDK 后构建
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

产物：`HyperOS-Workbench-Blur-Fix.apk`

可选环境变量：`LIBXPOSED_JAR`（自定义 API jar 路径）、`BUILD_TOOLS_VER`、`PLATFORM_VER`。

> 不依赖 Gradle：`build.sh` 直接用 `aapt2` + `javac` + `d8` + `zipalign` + `apksigner` 手工构建，
> 依赖少、构建快。CI（GitHub Actions）用的也是同一套脚本。

### 常见问题

**Q：装上了但没效果？**

1. 确认 LSPosed 里模块已启用、作用域勾选了 `com.miui.home`
2. 确认 LSPosed 版本支持 **API 101+**（模块 `module.prop` 中 `minApiVersion=101`）
3. 重启桌面进程后再试：`adb shell su -c "am force-stop com.miui.home"`

**Q：背景变成透明/黑色？**

说明 hook 生效了但该机型没有走窗口实时模糊路径。请提 issue 并附上 LSPosed 日志。

**Q：为什么不用 Magisk 模块改文件的方式？**

试过 —— 改 `/data/system/users/0/blurwallpaper` 无效：`getMiuiWallpaperPreview()` 读的是
system_server 内存里的位图，而且文件被删后系统会自动重建一份同样错误的图。真正的解法是让
静态图不再遮挡实时模糊，这只能从 MiuiHome 侧入手。

### 免责声明

本项目仅用于**个人设备的界面美化**。修改系统行为存在风险，请确保你已了解 LSPosed 的基本用法
并具备恢复能力（能进入 recovery 之类）。作者不对任何设备损坏、数据丢失负责。

### License

[MIT](LICENSE)

---

## English

### The problem

On Xiaomi tablets running HyperOS with **Workbench (laptop) mode** enabled, if you set a
**third-party live wallpaper** (e.g. Wallpaper Engine), opening the **app list** from the dock shows a
background that is:

- a **static blur of the built-in default wallpaper** — unrelated to your actual wallpaper, and
- **frozen** (it does not animate).

With a **built-in live wallpaper**, the background instead animates correctly.

### What this module does

| | Before | After |
|---|---|---|
| Background content | Built-in default wallpaper (unrelated) | **Your current wallpaper, live** |
| Animated | No | **Yes — real-time** |

### How it works

In MiuiHome's `laptop mode`, the app-list background is driven by **two paths at once**:

```java
// AllAppsAnimController.ensureBackgroundState(true)  — when opening the app list
allAppBackground.setAlpha(1.0f);                    // (1) paint an opaque static blur
WindowBlurUtils.setBlur(viewRootImpl, 0.4f, null);  // (2) also enable real window blur

// AllAppsBackground.showBlurEffect()
Bitmap bmp = WallpaperManagerCompat.getInstance(ctx).getCurrentWallpaperAsBitmap(ctx);
setBackground(new BitmapDrawable(getResources(), blur(bmp)));  // (1) covers (2) completely
```

`getCurrentWallpaperAsBitmap()` ends up at `MiuiWallpaperManager.getMiuiWallpaperPreview(1)` — the
`/data/system/users/0/blurwallpaper` cache inside `system_server`. For third-party live wallpapers that
cache is generated from the **built-in default wallpaper** (the real live frame is unavailable), so the
background is both wrong and static.

**The real window blur (2) is running all along** — it is simply hidden behind (1).

So instead of trying to fix that bitmap, this module hooks `AllAppsBackground#showBlurEffect` and, after
the original logic has run (Palette, wallpaper color-mode hint, 15% foreground scrim, notifying
`AllAppsController`), clears the View's background — **letting the window's own real-time blur show
through**.

### Requirements

- Xiaomi tablet with HyperOS **Workbench (laptop) mode**
- HyperOS 3.x (Android 16)
- Verified on Xiaomi Pad 7 Ultra (`25032RP42C` / `jinghu`), `OS3.0.301.0.WOXCNXM` (Android 16 / API 36)
- **LSPosed API 101+** with Zygisk enabled

> On other models with different class names, the module silently does nothing (it will not crash).
> Check the LSPosed log to confirm the hook was installed.

### Install

1. Download `HyperOS-Workbench-Blur-Fix.apk` from [Releases](../../releases) (or build it yourself)
2. Install it on your device
3. In LSPosed, enable **AllApps 动态模糊修复** and tick scope **“系统桌面”** (`com.miui.home`)
4. Restart the launcher — use the module's own **“重启系统桌面”** button (see below)

**Verifying**: the LSPosed log should contain

```
(com.miui.home)[com.reasonix.allappsblurfix,AllAppsBlurFix,...] hook installed;
blur radius = 150
```

### Settings

The module ships a settings screen (launcher icon **“AllApps 动态模糊修复”**):

- **Blur strength** — slider `0 ~ 150` (default `150` = the maximum window blur radius
  SurfaceFlinger allows). Takes effect **immediately**, without restarting the launcher;
  even an already-open app list is re-blurred on the spot.
- **“Restart launcher”** — after updating the module (reinstalling the APK) the new code only
  loads when the launcher process restarts. This button does it for you instead of running
  `am force-stop` manually. **It asks for root on first use** (KernelSU/Magisk prompt — approve once).

> Under the hood the blur radius is applied by hooking `WindowBlurUtils.blurRadiusOfRatio(float)`:
> the app list originally requests `0.4f` (≈40px), which this module replaces with your configured value.
> The value is shared with the hook through LSPosed remote preferences.

### Building from source

Requirements: **JDK 17+**, **Android SDK** (with `build-tools` and `platforms;android-34` or newer),
and the **LibXposed API** (downloaded by script).

```bash
git clone https://github.com/mar08meow/HyperOS-Workbench-Blur-Fix.git
cd HyperOS-Workbench-Blur-Fix

bash scripts/fetch-libxposed.sh      # downloads io.github.libxposed:api and extracts classes.jar
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh                        # -> HyperOS-Workbench-Blur-Fix.apk
```

No Gradle required — `build.sh` drives `aapt2` + `javac` + `d8` + `zipalign` + `apksigner` directly.
GitHub Actions uses the very same script.

### FAQ

**It installed but nothing changed.**
Check that the module is enabled and scoped to `com.miui.home`, that your LSPosed supports API 101+,
then restart the launcher process.

**The background went transparent/black.**
The hook works but this device does not take the real-time window-blur path. Please open an issue with
your LSPosed log.

**Why not just patch the wallpaper file with a Magisk module?**
Tried it — it does not work: `getMiuiWallpaperPreview()` reads an in-memory bitmap inside
`system_server`, and deleting the file just makes the system regenerate an equally wrong one. The actual
fix is to stop the static image from covering the real-time blur, which can only be done launcher-side.

### Disclaimer

This project is for **cosmetic tweaks on your own device**. Modifying system behaviour carries risk —
make sure you understand LSPosed and have a recovery path. The authors are not responsible for any
damage or data loss.

### License

[MIT](LICENSE)
