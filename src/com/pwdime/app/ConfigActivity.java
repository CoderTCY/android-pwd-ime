package com.pwdime.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 配置界面：选择目标输入法（从系统已启用输入法中选）+ 开关。
 * 配置写入 Settings.Secure（pwdime_ime_id / pwdime_enabled / pwdime_terminal_enabled），
 * 模块实时读取，立即生效。
 *
 * 写入需要 WRITE_SECURE_SETTINGS：优先直接写（已 pm grant / root 授权时），
 * 失败则尝试 su（本机为 root 环境），仍失败则提示 adb 授权命令。
 */
public class ConfigActivity extends Activity {

    public static final String KEY_IME_ID = "pwdime_ime_id";
    public static final String KEY_ENABLED = "pwdime_enabled";
    public static final String KEY_TERMINAL_ENABLED = "pwdime_terminal_enabled";
    private static final String LAUNCHER_ALIAS = "com.pwdime.app.LauncherAlias";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("密码框 / 终端自动切换输入法");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("聚焦密码框或终端时自动切换到所选输入法，离开后恢复。修改立即生效，无需重启。");
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        Switch enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用自动切换");
        enabledSwitch.setChecked(readBool(KEY_ENABLED, true));
        root.addView(enabledSwitch);

        Switch terminalSwitch = new Switch(this);
        terminalSwitch.setText("终端视图自动切换（Termux / MT 终端等）");
        terminalSwitch.setChecked(readBool(KEY_TERMINAL_ENABLED, true));
        root.addView(terminalSwitch);

        Switch hideIconSwitch = new Switch(this);
        hideIconSwitch.setText("隐藏桌面图标（仅桌面/抽屉，Vector 与 adb 仍可打开）");
        hideIconSwitch.setChecked(isIconHidden());
        root.addView(hideIconSwitch);

        TextView imeLabel = new TextView(this);
        imeLabel.setText("目标输入法（从已启用的输入法中选择）");
        imeLabel.setPadding(0, dp(20), 0, dp(8));
        root.addView(imeLabel);

        RadioGroup group = new RadioGroup(this);
        String current = readImeId();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        PackageManager pm = getPackageManager();
        List<InputMethodInfo> list = imm.getEnabledInputMethodList();
        final int[] lastCheckedId = {-1};
        for (InputMethodInfo imi : list) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(imi.loadLabel(pm) + "\n" + imi.getId());
            rb.setTag(imi.getId());
            group.addView(rb);
            if (imi.getId().equals(current)) {
                rb.setChecked(true);
                lastCheckedId[0] = rb.getId();
            }
        }
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（无已启用的输入法）");
            root.addView(empty);
        } else {
            root.addView(group, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(root);
        setContentView(scroll);

        enabledSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (!write(KEY_ENABLED, checked ? "1" : "0")) {
                btn.setChecked(!checked);
            }
        });
        terminalSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (!write(KEY_TERMINAL_ENABLED, checked ? "1" : "0")) {
                btn.setChecked(!checked);
            }
        });
        hideIconSwitch.setOnCheckedChangeListener((btn, checked) -> {
            setIconHidden(checked);
            toast(checked ? "已隐藏：桌面与抽屉不再显示本应用图标"
                    : "已恢复显示（若图标未出现，从抽屉重新拖到桌面）");
        });
        final boolean[] reverting = {false};
        group.setOnCheckedChangeListener((g, id) -> {
            if (reverting[0]) {
                return;
            }
            RadioButton rb = g.findViewById(id);
            if (rb != null) {
                if (!write(KEY_IME_ID, (String) rb.getTag())) {
                    reverting[0] = true;
                    if (lastCheckedId[0] != -1) {
                        g.check(lastCheckedId[0]);
                    } else {
                        g.clearCheck();
                    }
                    reverting[0] = false;
                    return;
                }
                lastCheckedId[0] = id;
            }
        });
    }

    /** 通过启用/禁用 LAUNCHER activity-alias 控制桌面图标，主 Activity 始终保持启用 */
    private boolean isIconHidden() {
        return getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private String readImeId() {
        try {
            return Settings.Secure.getString(getContentResolver(), KEY_IME_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean readBool(String key, boolean defaultValue) {
        try {
            return Settings.Secure.getInt(getContentResolver(), key, defaultValue ? 1 : 0) == 1;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean write(String key, String value) {
        try {
            Settings.Secure.putString(getContentResolver(), key, value);
            toast("已保存");
            return true;
        } catch (SecurityException se) {
            if (writeViaSu(key, value)) {
                toast("已保存（经 root）");
                return true;
            }
            showGrantDialog();
            return false;
        }
    }

    private boolean writeViaSu(String key, String value) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "settings put secure " + key + " " + value});
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showGrantDialog() {
        new AlertDialog.Builder(this)
                .setTitle("无法写入设置")
                .setMessage("请先授权后重试：\n\n"
                        + "adb shell \"su -c 'pm grant com.pwdime.switcher"
                        + " android.permission.WRITE_SECURE_SETTINGS'\"")
                .setPositiveButton("好", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
