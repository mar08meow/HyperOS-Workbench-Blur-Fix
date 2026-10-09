package com.reasonix.allappsblurfix;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 工作台模式（MiuiHome 的 laptop mode）应用列表背景修复。
 *
 * 背景机制（反汇编 AllAppsAnimController / AllAppsBackground 得到）：
 *   AllAppsAnimController.ensureBackgroundState(true):
 *       allAppBackground.setAlpha(1.0f);                     // 铺一张不透明的静态模糊图
 *       WindowBlurUtils.setBlur(viewRootImpl, 0.4f, null);   // 同时开了窗口级实时模糊
 *
 *   AllAppsBackground.showBlurEffect():
 *       Bitmap bmp = WallpaperManagerCompat.getInstance(ctx).getCurrentWallpaperAsBitmap(ctx);
 *       setBackground(new BitmapDrawable(getResources(), blur(bmp)));   // <- 盖住了实时模糊
 *
 * 第三方动态壁纸（Wallpaper Engine）下这张静态图取到的是「系统默认壁纸的模糊图」，
 * 于是背景既不正确、也不会动。
 *
 * 修复：保留 showBlurEffect 的全部原有逻辑（Palette 取色、图标颜色提示模式、
 * 15% 前景遮罩、通知 AllAppsController），只在其执行完之后把背景清成透明，
 * 让窗口自身的实时模糊露出来 —— 背景即变成实时模糊的动态壁纸。
 *
 * 本类使用 LibXposed API（io.github.libxposed），对应 LSPosed 的 API 101+；
 * 入口在 META-INF/xposed/java_init.list 中声明。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "AllAppsBlurFix";
    private static final String TARGET_PKG = "com.miui.home";
    private static final String BG_CLASS = "com.miui.home.launcher.laptop.AllAppsBackground";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            Class<?> bgClass = Class.forName(BG_CLASS, false, cl);
            Executable target = bgClass.getDeclaredMethod("showBlurEffect");
            hook(target).intercept(chain -> {
                // 先跑完原始逻辑（Palette 取色 / 颜色提示模式 / 前景遮罩 / controller 回调）
                Object result = chain.proceed();
                // 再把不透明的静态模糊背景清掉 -> View 透明 -> 露出窗口实时模糊
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        ((View) self).setBackground(null);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "clear background failed", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hook installed on " + BG_CLASS + "#showBlurEffect");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook install failed", t);
        }
    }
}
