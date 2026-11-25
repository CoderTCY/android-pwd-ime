# PwdImeSwitcher — 全系统密码框自动切换输入法（Vector / libxposed API 102）

针对 **Android 16 (API 36)**，基于 **Vector / libxposed API 102**（`io.github.libxposed:api:102.0.0`），作用域仅系统框架（`system` / `system_server`），带配置 App UI，无硬编码输入法，支持模块热重载（`autoHotReload=true`）。

## 原理

模块入口 `com.pwdime.hook.PwdImeSwitcher` 继承 `io.github.libxposed.api.XposedModule`（声明于 `META-INF/xposed/java_init.list`），在 `onSystemServerStarting` 中通过 `XposedInterface.hook(Executable).setId(...).intercept(Chain)` Hook `InputMethodManagerService` 的三个入口（A16 实测确认缺一不可）：

1. `startInputOrWindowGainedFocus` — 所有输入框聚焦/startInput 的 Binder 入口（ZeroJankProxy async 变体最终也回调它）。`EditorInfo` 命中密码变体（`TYPE_TEXT_VARIATION_PASSWORD` / `VISIBLE_PASSWORD` / `WEB_PASSWORD` / `TYPE_NUMBER_VARIATION_PASSWORD`）或终端特征（`TYPE_MASK_CLASS == TYPE_NULL` 且带 `IME_FLAG_NO_FULLSCREEN` / `IME_FLAG_NO_EXTRACT_UI` 或 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`，覆盖 Termux / MT 终端 / DroidSpaces 终端如 `0x80090` 等，并保留普通非编辑窗口 `imeOptions=0` 不误触发）→ 记住当前常规输入法，反射调用内部 `setInputMethodLocked(pwdImeId, -1, userId)` 切到目标输入法；离开目标框 → 恢复记住的常规输入法。
2. `showSoftInput` — App 侧 Binder 发起的显示（public 入口；private `showSoftInputLocked` 在 AOT 下被内联，hook 不生效）。
3. `showCurrentInputInternal` — **系统侧发起的显示**（WMS/InsetsController 点击已聚焦输入框时经 `registerImeRequestedChangedListener` 回调，不经过任何 Binder 入口）。不 hook 它则「密码框已聚焦但被手动切走输入法后再点它」无法纠正。

Show 路径的编辑器信息优先取 `userData.mCurEditorInfo`（同窗口内换输入框时持续更新），为空回退 `mImeBindingState.mFocusedWindowEditorInfo`（仅窗口焦点变化时更新，可能是旧值）。

关键签名均已对照 AOSP `android-16.0.0_r1` 与 PLF110 ColorOS `services.jar` / `oplus-services.jar` 核实：
- `setInputMethodLocked(String id, int subtypeIndex, int userId)`（`ImfLock` 临界区内切换运行时绑定并解绑当前 IME）
- `InputMethodSettingsRepository.get(userId).getSelectedInputMethod()` / `.getEnabledInputMethodList()`
- `InputMethodSettings` 读写虚拟化（`putSelectedInputMethod` / `putSelectedSubtype` / `saveCurrentInputMethodAndSubtypeToHistory` / `putSelectedDefaultDeviceInputMethod` / `getSelectedInputMethod` / `getSelectedInputMethodSubtypeHashCode`）：模块触发切换时仅更新内存覆盖态，**不修改 `Settings.Secure.default_input_method`、`selected_input_method_subtype` 与切换历史**；用户手动切走或外部修改设置时自动清除覆盖态并放行落盘
- ColorOS 扩展适配：
  - `InputMethodManagerServiceExtImpl#notifyImeAttributeChanged` 会从 `OplusInputMethodSettingsHelper.mDefaultInputMethod` 比对当前绑定 IME 并在不一致时强制回切，非持久化覆盖期间临时同步该字段并在 `finally` 中还原
  - `AppStatusManager`（`STATUS_INPUT`）与 `OplusHansDBConfig#isDefaultInputPkg` 在读取侧同时放行模块配置的目标输入法，并在切入时经 `OplusHansManager#hansUnFreeze` 主动解冻，避免因 `Settings.Secure.default_input_method` 未变导致 ColorOS Hans 墓碑冻结、Osense `boot_clear` 查杀或 `osense.compress` 内存压缩目标输入法（导致输入卡顿）
- `resolveImeUserIdFromWindowLocked(windowToken)`（showCurrentInputInternal 路径解析 userId）
- `NOT_A_SUBTYPE_INDEX = -1`

### Vector API 102 特性

- **元数据配置**（`META-INF/xposed/`）：
  - `module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`autoHotReload=true`
  - `java_init.list`：`com.pwdime.hook.PwdImeSwitcher`
  - `scope.list`：`system`（表示 `system_server`）
- **热重载（Hot Reload）**：所有 Hook 均带有稳定 `id`（`HookBuilder#setId`）；`onHotReloading` 以纯引导类加载器类型（`HashMap` / `String` / `Integer` / `ClassLoader`）保存 `mSavedImeId`、`mSavedSubtypeIndex`、`mOverrideImeId`、`mOverrideSubtypeHash` 与系统 `ClassLoader`，`onHotReloaded` 恢复状态并原子替换全部 Hook 后清理旧句柄。从旧版 API 82 首次升级到 API 102 需重启一次生效，之后更新模块可直接在 `system_server` 内自动热重载。
- **进程分离**：`onModuleLoaded` 中若非 `system_server` 进程则立即调用 `detach()`。

线程安全：主 hook 在 `ImfLock` 临界区内执行；内存覆盖表与配置缓存由独立细粒度锁 `mStateLock` 保护（绝不持锁调用 IMMS）；模块自身的 pre/post 逻辑包裹 try-catch，异常不会泄漏进 system_server。

## 配置（Settings.Secure，立即生效，无需重启）

配置由 App UI（`ConfigActivity`）写入，模块每 2s 缓存读取，改动即时生效：

| 键 | 说明 |
|---|---|
| `pwdime_ime_id` | 目标输入法 ID（`ComponentName#flattenToShortString()`），从已启用输入法中单选 |
| `pwdime_enabled` | 1/0 总开关，缺省视为开 |
| `pwdime_terminal_enabled` | 1/0 终端视图自动切换开关，缺省视为开 |

**隐藏桌面图标实现**：采用标准 `activity-alias` 拆分入口（无 system_server hook）。主 `ConfigActivity` 仅声明 `android.intent.action.MAIN` + `de.robv.android.xposed.category.MODULE_SETTINGS` 并保持启用；桌面启动器入口（`CATEGORY_LAUNCHER`）放在独立的 `com.pwdime.app.LauncherAlias` 别名上。开关切换时通过 `PackageManager.setComponentEnabledSetting` 启用/禁用 `LauncherAlias`，系统原生触发 `PACKAGE_CHANGED` 刷新桌面/抽屉，同时 Vector 与 `adb am start` 入口始终可用。

App 需要 `WRITE_SECURE_SETTINGS`（需在 Manifest 声明后用 `adb shell pm grant com.pwdime.switcher android.permission.WRITE_SECURE_SETTINGS` 授权，ColorOS 的 shell 无此权限需 `su -c pm grant`；未授权时回退 `su`）。配置 App 列出其他 IME 需 manifest 声明 `<queries><intent><action android:name="android.view.InputMethod"/></intent></queries>`（targetSdk 30+ 包可见性）。

## 构建与 CI

无 gradle，直接用 SDK 工具链，提供 Windows（`build.ps1`）与 Linux/CI（`build.sh`）脚本：

```powershell
# Windows
.\build.ps1
```

```bash
# Linux / macOS
chmod +x build.sh
./build.sh
```

签名策略：
- 优先使用环境变量 `KEYSTORE_B64`（CI Secrets）或 `KEYSTORE_PATH` / 本地 `keystore.pfx`（配合 `KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS`、`KEYSTORE_TYPE`）。
- 未提供签名密钥时自动生成临时调试证书 `build/debug.pfx` 完成签名，仓库本身不包含私钥文件。

已内置双平台 CI 工作流（构建产物 `build/apk/PwdImeSwitcher.apk`，可选配置上述签名 Secrets；推送 `v*` 标签或在 `workflow_dispatch` 指定 `release_tag` 时自动创建 Release 并上传 APK）：
- GitHub Actions：`.github/workflows/build.yml`
- Codeberg / Forgejo Actions：`.forgejo/workflows/build.yml`

产物：`build/apk/PwdImeSwitcher.apk`（已签名，zipalign 校验通过）。

## 部署（Vector API 102）

1. `adb install -r --no-incremental build/apk/PwdImeSwitcher.apk`
   （**必须 `--no-incremental`**：增量安装的 APK 带 block-verity 保护，system_server 读取失败，模块静默不加载）
2. 启用模块并配置作用域（Vector 2.2+ / API 102，`scope.list` 已静态声明 `system`）：

```
adb shell su -c '/data/adb/lspd/cli modules enable com.pwdime.switcher'
adb shell su -c '/data/adb/lspd/cli scope set com.pwdime.switcher system/0'
```

3. 从旧版 API 82 首次升级到 API 102 需重启设备一次使 `system_server` 重新加载；后续版本升级支持 `autoHotReload=true` 在运行中自动热重载。
4. 密码输入法（fcitx5）保持已启用状态。

## 验证（实测日志）

```
adb shell su -c '/data/adb/lspd/cli log cat' | grep PwdImeSwitcher
```

预期启动日志：

```
PwdImeSwitcher: active in system_server (API 102, hotReload=false), hooked=1, hookedShow=1, hookedInternal=1, hookedSettings=7, hookedExt=1, hookedOsense=6 (non-persistent mode, config via Settings.Secure: pwdime_ime_id)
PwdImeSwitcher: password field -> temp switched to org.fcitx.fcitx5.android/.input.FcitxInputMethodService
PwdImeSwitcher: left target field -> restored com.tencent.wetype/.plugin.hld.WxHldService
```

端到端回归（uiautomator 定位 + input tap，检查 `dumpsys input_method` 运行时状态与 `settings get secure default_input_method`）：
- 进入密码框：运行时 `mSelectedMethodId` / `mCurId` / `mCurImeId` 切至 fcitx5，`Settings.Secure.default_input_method`、`selected_input_method_subtype` 与 `input_methods_subtype_history` 保持原值不变
- 切回普通框：恢复 wetype，持久设置仍未改动

### 设备适配结论（PLF110 ColorOS）

- IMMS 本体与 AOSP `android-16.0.0_r1` 一致，但 ColorOS 在 `oplus-services.jar` 中通过 `InputMethodManagerServiceExtImpl#notifyImeAttributeChanged` 维护了独立的 `OplusInputMethodSettingsHelper.mDefaultInputMethod` 校验并在不一致时回切，模块已对此做无持久化的临时同步适配。

## 风险与边界

- **ROM 适配**：代码按 A16 AOSP 结构写成，厂商 ROM 若重构 IMMS（常见于国产 ROM）需对照该 ROM 源码调整类名/方法名；`hooked=0` 时模块只记日志、不生效，不影响系统。
- **非持久化语义**：因不写入 `Settings.Secure.default_input_method`，外部直接读取 `Settings.Secure` 看到的始终是用户的常规输入法；即使在密码框内异常断电重启，开机后默认输入法也保持常规输入法不变。
- **多用户**：按 `userId` 分别记忆/恢复，各用户互不干扰。
