package com.reasonix.allappsblurfix;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 工作台模式（MiuiHome 的 laptop mode）应用列表背景修复。
 *
 * === 背景机制（反汇编 AllAppsAnimController / AllAppsBackground / WindowBlurUtils 得到）===
 *
 *   AllAppsAnimController.ensureBackgroundState(true):        // 打开应用列表
 *       allAppBackground.setAlpha(1.0f);                      // ① 铺一张不透明的静态模糊图
 *       WindowBlurUtils.setBlur(viewRootImpl, 0.4f, null);    // ② 开启窗口级实时模糊
 *
 *   AllAppsBackground.showBlurEffect():
 *       Bitmap bmp = WallpaperManagerCompat.getInstance(ctx).getCurrentWallpaperAsBitmap(ctx);
 *       setBackground(new BitmapDrawable(getResources(), blur(bmp)));
 *
 *   WindowBlurUtils.blurRadiusOfRatio(ratio):                 // ratio -> 实际模糊半径(px)
 *       if (ratio == 0) return 0;
 *       return (int) Folme.valueFromPer(ratio, 1.0f, 100.0f);  // 映射到 [1, 100]
 *
 * === 原问题 ===
 *
 * 那张静态图的数据源 getCurrentWallpaperAsBitmap() 最终落到
 * MiuiWallpaperManager.getMiuiWallpaperPreview(1)，也就是 system_server 缓存的
 * /data/system/users/0/blurwallpaper。对**第三方动态壁纸**（如 Wallpaper Engine），
 * 这份缓存是用「内置默认壁纸」生成的（拿不到动态壁纸的真实帧），
 * 于是背景既和当前壁纸无关、也不会动。
 *
 * === 本模块的两步修复 ===
 *
 * ① 让 AllAppsBackground 不再铺那张错误的不透明静态图（清成透明），
 *    使**窗口自带的实时模糊**露出来 —— 背景变成实时跟随的动态壁纸，
 *    也不会再冻结在"打开瞬间"那一帧。
 *
 * ② 原代码只把模糊比例开到 0.4f（实际半径仅 ≈40），太轻、下层 app 内容仍可辨认。
 *    这里接管 blurRadiusOfRatio()，把半径改成用户配置值（默认 150 = SurfaceFlinger 上限）。
 *
 * 不用「定时截屏壁纸图层再贴回去」的做法：ScreenCapture 会迫使 SurfaceFlinger
 * 反复做额外合成与 GPU 回读，抢占合成带宽，导致滑动/打开动画/下拉通知栏全面掉帧。
 * 本方案完全走系统原生窗口模糊，零额外开销。
 *
 * === 配置与热更新 ===
 *
 * 模糊半径通过 LSPosed 的 remote preferences 与设置界面共享：
 * group = settings，key = blur_radius。
 *
 * 注意：不能只依赖 registerOnSharedPreferenceChangeListener —— 实测跨进程的变更
 * 通知并不总能到达目标进程。所以这里做双保险：
 *   a) 每次 blurRadiusOfRatio() 被调用时都现读一次配置（保证新打开的应用列表用新值）；
 *   b) 同时注册监听，收到变更时主动对"当前已打开的窗口"重新应用一次模糊（做到实时生效）。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "AllAppsBlurFix";
    private static final String TARGET_PKG = "com.miui.home";
    private static final String BG_CLASS = "com.miui.home.launcher.laptop.AllAppsBackground";
    private static final String WBU_CLASS = "com.miui.launcher.utils.WindowBlurUtils";

    private SharedPreferences mPrefs;
    /** 最近一次 setBlur() 作用的窗口对象（ViewRootImpl），用于配置变化后重新应用。 */
    private volatile Object mBlurWindow;
    private Method mSetBlur;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            Class<?> wbuClass = Class.forName(WBU_CLASS, false, cl);

            // -------- 配置 --------
            setupPreferences();

            // -------- ① 清掉那张内容错误的静态模糊图，露出窗口实时模糊 --------
            Class<?> bgClass = Class.forName(BG_CLASS, false, cl);
            Executable showBlurEffect = bgClass.getDeclaredMethod("showBlurEffect");
            hook(showBlurEffect).intercept(chain -> {
                // 先跑完原有逻辑，保留它的副作用
                // （Palette 取色 / setWallpaperHintColorMode / 15% 前景遮罩 /
                //   mAllAppsController.onAllAppsWallpaperColorChanged()）
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        // 清掉不透明背景 -> View 透明 -> 露出窗口级实时模糊
                        ((View) self).setBackground(null);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "clear background failed", t);
                }
                return result;
            });

            // -------- ② 接管模糊半径，用用户配置值 --------
            Executable radiusOfRatio = wbuClass.getDeclaredMethod("blurRadiusOfRatio", float.class);
            hook(radiusOfRatio).intercept(chain -> {
                int radius = (int) chain.proceed();
                if (radius <= 0) {
                    // 0 表示关闭模糊（应用列表关闭时），保持不变
                    return radius;
                }
                // 现读配置，避免依赖跨进程变更通知
                return currentRadius();
            });

            // -------- ③ 记录 setBlur 的目标窗口，供配置变化时重新应用 --------
            try {
                mSetBlur = wbuClass.getDeclaredMethod(
                        "setBlur", Object.class, float.class, Consumer.class);
                mSetBlur.setAccessible(true);
                hook(mSetBlur).intercept(chain -> {
                    mBlurWindow = chain.getArg(0);
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook setBlur failed (热更新将退化为下次打开生效)", t);
            }

            log(Log.INFO, TAG, "hook installed; blur radius = " + currentRadius());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook install failed", t);
        }
    }

    /** 读取配置；失败时回退默认值。 */
    private int currentRadius() {
        SharedPreferences p = mPrefs;
        if (p == null) {
            return SettingsActivity.DEFAULT_RADIUS;
        }
        try {
            return p.getInt(SettingsActivity.KEY_RADIUS, SettingsActivity.DEFAULT_RADIUS);
        } catch (Throwable t) {
            return SettingsActivity.DEFAULT_RADIUS;
        }
    }

    private void setupPreferences() {
        try {
            mPrefs = getRemotePreferences(SettingsActivity.GROUP);
            mPrefs.registerOnSharedPreferenceChangeListener((p, key) -> {
                if (SettingsActivity.KEY_RADIUS.equals(key)) {
                    log(Log.INFO, TAG, "blur radius -> " + currentRadius());
                    reapplyBlur();
                }
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "read remote preferences failed, using default", t);
        }
    }

    /** 配置变化后，立刻对已打开的窗口重新应用模糊。 */
    private void reapplyBlur() {
        Object window = mBlurWindow;
        Method setBlur = mSetBlur;
        if (window == null || setBlur == null) {
            return;
        }
        try {
            // ratio 传 1.0f：确保内部走"非零"分支，实际半径由 blurRadiusOfRatio 里
            // 我们的 hook 替换成用户配置值。
            setBlur.invoke(null, window, 1.0f, null);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "reapply blur failed", t);
        }
    }
}
