# Fumofumo-Mobile

把 3D 桌宠搬到 Android：**悬浮在其他应用之上**的 fumo，能拖、能甩、能转、会掉到屏幕底部弹起来。

这是桌面版 [WINobj](../WINobj) 的 Android 重写版。桌面版是 Python + Qt + moderngl，手机上这些都用不了，所以外壳整体重写成了 Kotlin + OpenGL ES 3.0；物理和着色器是从桌面版移植过来的，手感一致。

## 效果

- 桌宠浮在桌面 / 其他 App 之上，透明背景
- 拖起来可以甩出去，撞到屏幕边缘会弹回来，最后落到屏幕底部
- 双指拖动可以自由转动模型（3D 自转，不只是平面旋转）
- 模型从相册/文件里挑，`.glb` 单文件即可（贴图内嵌）

## 环境要求

| | |
|---|---|
| Android | 8.0（API 26）及以上 |
| GPU | 支持 OpenGL ES 3.0 |
| 构建 | JDK 17 + Android SDK 35（Android Studio 打开即可） |

## 构建

用 Android Studio 直接打开这个目录，等 Gradle 同步完，点 Run。

命令行（需要 JDK 17，Android SDK 35）：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 仓库里的 `gradle-wrapper.properties` 把 Gradle 发行包指向了腾讯云镜像（`mirrors.cloud.tencent.com`），因为官方 `services.gradle.org` 在国内经常连不上。想换回官方源，把 `distributionUrl` 改成
> `https\://services.gradle.org/distributions/gradle-8.9-bin.zip` 即可。

### 单元测试

解析器有一个拿真实模型对拍的测试（默认跳过，因为模型不进仓库）：

```bash
./gradlew testDebugUnitTest -DtestModel=/path/to/your/model.glb
```

它会断言三角面数、包围盒与桌面版 WINobj（trimesh）对同一模型的解析结果一致。

## 使用

1. 打开 App，点「选择模型（.glb）」，挑一个 glb 文件（Sketchfab 下载的 GLB 就行）
2. 点状态栏里那行「悬浮窗权限：未授权」，去系统设置里打开「显示在其他应用上层」
3. 回到 App 点「启动桌宠」——它会掉到屏幕底部

| 手势 | 作用 |
|---|---|
| 单指拖动 | 拎起来移动，松手按速度甩出去 |
| 双指拖动 | 横向转 Y 轴、纵向转 X 轴 |
| 长按 | 打开参数设置 |
| 点模型以外的地方 | 触摸透传给下面的应用（不会挡住你操作） |

通知栏里有「设置」和「停止」两个快捷入口。

## 参数

设置界面里可调：重力、弹性、摩擦、空气阻力、甩出力度、模型大小、占屏高度、环境光、伽马、贴图翻转。

参数直接写进 `SharedPreferences`，渲染线程每帧读取，所以是**实时生效**的。

「贴图翻转」默认开启（和桌面版一致）。如果模型贴图上下颠倒了，关掉它。

## 和桌面版的差别

| | 桌面版 WINobj | 这里 |
|---|---|---|
| 语言/框架 | Python + PySide6 + moderngl | Kotlin + OpenGL ES 3.0 |
| 渲染窗口 | 全屏透明 Qt 窗口 | TextureView + 自建 EGL 线程（SurfaceView 在悬浮窗里做不出真透明） |
| 碰撞 | 每帧变换全部顶点求包围盒 | 只变换包围盒 8 个角点（手机上 29 万顶点逐帧变换扛不住） |
| 支持格式 | obj / glb / gltf / usdz / zip | 只支持单文件 `.glb` |
| 旋转控制 | 键盘 W/A/S/D/Q/E | 双指手势 |

## 代码结构

```
app/src/main/java/com/funnyarchbishop/fumofumo/
├── MainActivity.kt      权限引导、选模型、启动/停止
├── SettingsActivity.kt  参数滑条
├── PetService.kt        前台服务 + 悬浮窗（桌宠能飘起来的关键）
├── PetView.kt           TextureView + 手势（拖拽 / 双指旋转 / 长按）
├── PetRenderer.kt       自建 EGL 线程 + 着色器 + 渲染循环
├── Physics.kt           2D 刚体 + 3D 自转（移植自 WINobj.py）
├── GlbLoader.kt         极简 GLB 解析（节点变换 / UV / baseColor 与 emissive 贴图）
├── ModelRepository.kt   模型文件落地到 filesDir
└── PetPrefs.kt          参数持久化
```

## 已知限制

- **只支持单文件 `.glb`**。需要外部 `.bin` 的 `.gltf`、Draco 压缩的 glb、usdz 都不支持（桌面版可以用它们，也可以拿桌面版把模型另存成 glb）。
- 一个模型只用一张贴图：优先 `baseColorTexture`，没有就用 `emissiveTexture`（Sketchfab 上的 Shadeless 扫描件会把颜色放在自发光通道）。
- 多材质的模型会统一用面数最多那块的贴图。
- 动画、骨骼、蒙皮不参与（桌宠只看静态网格）。
- **iOS 做不了**：系统不允许跨 App 悬浮窗，桌宠这个概念在 iOS 上不存在。

## 许可

MIT。模型素材请自行注意原作者的授权（Sketchfab 上的模型多为 CC-BY，记得署名）。
