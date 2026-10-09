package com.reasonix.allappsblurfix;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块设置界面：调整工作台模式「应用列表」背景的模糊强度。
 *
 * 配置通过 LSPosed 的 remote preferences 与 hook 侧共享
 * （group = settings，key = blur_radius），改完立即生效，无需重启。
 */
public class SettingsActivity extends Activity {

    /** 与 hook 侧共用的 remote preferences 组名 / 键名。 */
    public static final String GROUP = "settings";
    public static final String KEY_RADIUS = "blur_radius";

    /** 默认模糊半径。150 是 SurfaceFlinger 允许的窗口模糊上限。 */
    public static final int DEFAULT_RADIUS = 150;
    public static final int MIN_RADIUS = 0;
    public static final int MAX_RADIUS = 150;

    /** 系统桌面的包名与主 Activity，用于「重启系统桌面」。 */
    private static final String LAUNCHER_PKG = "com.miui.home";
    private static final String LAUNCHER_ACTIVITY = "com.miui.home/.launcher.Launcher";

    private XposedService mService;
    private SharedPreferences mPrefs;

    private TextView mValueText;
    private TextView mStatusText;
    private SeekBar mSeekBar;
    private TextView mRestartStatus;
    /** 回调触发的写入打标记，避免被自身的监听回调覆盖 UI。 */
    private boolean mSelfChange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        XposedServiceHelper.registerListener(new ServiceListener(this));

        mSeekBar.setOnSeekBarChangeListener(new SeekListener(this));
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mService = null;
        mPrefs = null;
    }

    // ---------------- UI ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(22f);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.settings_desc);
        desc.setTextSize(14f);
        desc.setPadding(0, pad / 2, 0, pad);
        root.addView(desc);

        mValueText = new TextView(this);
        mValueText.setTextSize(18f);
        root.addView(mValueText);

        mSeekBar = new SeekBar(this);
        mSeekBar.setMax(MAX_RADIUS - MIN_RADIUS);
        root.addView(mSeekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatusText = new TextView(this);
        mStatusText.setTextSize(13f);
        mStatusText.setPadding(0, pad, 0, 0);
        root.addView(mStatusText);

        // ---- 重启系统桌面 ----
        TextView restartHint = new TextView(this);
        restartHint.setText(R.string.settings_restart_hint);
        restartHint.setTextSize(13f);
        restartHint.setPadding(0, pad * 2, 0, pad / 2);
        root.addView(restartHint);

        Button restartButton = new Button(this);
        restartButton.setText(R.string.settings_restart_launcher);
        restartButton.setAllCaps(false);
        restartButton.setOnClickListener(v -> restartLauncher());
        root.addView(restartButton);

        mRestartStatus = new TextView(this);
        mRestartStatus.setTextSize(13f);
        mRestartStatus.setPadding(0, pad / 2, 0, 0);
        root.addView(mRestartStatus);

        return root;
    }

    // ---------------- 重启系统桌面 ----------------

    /**
     * 重启 com.miui.home。用于「重新安装模块 APK 之后」让新代码生效
     * （hook 是在桌面进程启动时装上的，不重启就不会加载新版本）。
     *
     * 通过 su 调用 am force-stop + 重新拉起；首次会请求 root 授权。
     */
    private void restartLauncher() {
        if (mRestartStatus != null) {
            mRestartStatus.setText(R.string.settings_restart_running);
        }
        Thread t = new Thread(() -> {
            int code;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c",
                        "am force-stop " + LAUNCHER_PKG
                                + "; sleep 1; am start -n " + LAUNCHER_ACTIVITY
                });
                code = p.waitFor();
            } catch (Throwable e) {
                code = -1;
            }
            final int exit = code;
            runOnUiThread(() -> {
                if (mRestartStatus != null) {
                    mRestartStatus.setText(exit == 0
                            ? getString(R.string.settings_restart_ok)
                            : getString(R.string.settings_restart_failed, exit));
                }
            });
        }, "restart-launcher");
        t.start();
    }

    // ---------------- 状态 ----------------

    /** 服务状态变化（bind / died）时调用；可能不在主线程，所以都切回主线程。 */
    void onServiceStateChanged(XposedService service, boolean bound) {
        runOnUiThread(() -> {
            mService = bound ? service : null;
            if (bound && service != null) {
                try {
                    mPrefs = service.getRemotePreferences(GROUP);
                } catch (Throwable t) {
                    mPrefs = null;
                }
            } else {
                mPrefs = null;
            }
            refreshUi();
        });
    }

    private void refreshUi() {
        if (mSeekBar == null) {
            return;
        }
        int radius = currentRadius();
        mSelfChange = true;
        mSeekBar.setProgress(radius - MIN_RADIUS);
        mSelfChange = false;
        mValueText.setText(getString(R.string.settings_value, radius, MAX_RADIUS));
        mStatusText.setText(mPrefs != null
                ? getString(R.string.settings_status_ok)
                : getString(R.string.settings_status_no_service));
    }

    private int currentRadius() {
        if (mPrefs == null) {
            return DEFAULT_RADIUS;
        }
        return mPrefs.getInt(KEY_RADIUS, DEFAULT_RADIUS);
    }

    /** SeekBar 拖动回调，可能不在主线程。 */
    void onRadiusPicked(int radius) {
        if (mSelfChange) {
            return;
        }
        int clamped = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        mValueText.setText(getString(R.string.settings_value, clamped, MAX_RADIUS));
        SharedPreferences prefs = mPrefs;
        if (prefs == null) {
            mStatusText.setText(R.string.settings_status_no_service);
            return;
        }
        prefs.edit().putInt(KEY_RADIUS, clamped).apply();
    }
}

/** 顶层实现类（避免匿名内部类，d8 处理匿名类时曾触发内部错误）。 */
final class ServiceListener implements XposedServiceHelper.OnServiceListener {

    private final SettingsActivity mActivity;

    ServiceListener(SettingsActivity activity) {
        mActivity = activity;
    }

    @Override
    public void onServiceBind(XposedService service) {
        mActivity.onServiceStateChanged(service, true);
    }

    @Override
    public void onServiceDied(XposedService service) {
        mActivity.onServiceStateChanged(service, false);
    }
}

/** 顶层实现类（同上）。 */
final class SeekListener implements SeekBar.OnSeekBarChangeListener {

    private final SettingsActivity mActivity;

    SeekListener(SettingsActivity activity) {
        mActivity = activity;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            mActivity.onRadiusPicked(progress + SettingsActivity.MIN_RADIUS);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
    }
}
