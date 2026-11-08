# Fumofumo_Mobile

把 3D 桌宠搬到 Android：**悬浮在其他应用之上**的 fumo，能拖、能甩、能转、会掉到屏幕底部弹起来。

这是桌面版 [Fumofumo_Windows](https://github.com/FunnyArchbishop/Fumofumo_Windows)（原 WINobj）的 Android 重写版。桌面版是 Python + Qt + moderngl，手机上这些都用不了，所以外壳整体重写成 Kotlin + OpenGL ES 3.0；物理和着色器从桌面版移植，手感一致。

模型可以从 [Fumofumo_Models](https://github.com/FunnyArchbishop/Fumofumo_Models) 下载（20 个 fumo 扫描件，CC BY 4.0）。

## 效果

- 桌宠浮在桌面 / 其他 App 之上，透明背景
- 拖起来可以甩出去，撞到屏幕边缘会弹回来，最后落到屏幕底部
- 双指拖动可以自由转动模型（3D 自转，不只是平面旋转）
- **桌宠以外的地方照常可以点** —— 悬浮窗只包住 fumo 本身，不会挡住你操作手机
- 长按桌宠出菜单：参数设置 / 停止
- 模型支持多种格式，贴图还可以自己换一张

## 环境要求

| | |
|---|---|
| Android | 8.0（API 26）及以上 |
| GPU | 支持 OpenGL ES 3.0 |
| 构建 | JDK 17 + Android SDK 35（Android Studio 打开即可） |

## 支持的模型格式

| 格式 | 说明 | 备注 |
| --- | --- | --- |
| `.glb` | glTF 二进制单文件，贴图内嵌 | **最推荐**，最省事 |
| `.gltf` | JSON + 外部 `.bin` 与贴图 | 需要连同资源一起打成 `.zip` 再导入 |
| `.obj` | Wavefront OBJ（+ `.mtl` 与贴图） | 同上，打成 `.zip` 才能带上贴图 |
| `.usdz` | Pixar USDZ（Apple AR 常用格式） | 内含的 `.usdc` 由本程序自己解析 |
| `.usdc` | USD Crate 二进制 | 单独导入或放在 zip 里都行 |
| `.zip` | 压缩包 | 自动解包并挑出里面的模型 |

**扩展名不可信。** 导入时是按**文件内容**判断格式的，下载工具把 `model.glb` 改名成 `.circ`、`.bin` 或者没有扩展名，照样能打开。

**贴图可以自己换。** 首页「自定义贴图」挑一张图片覆盖模型自带的贴图；「清除贴图」恢复原样。

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

三个读取器都有测试，其中两个拿**真实模型**和桌面版的解析结果对拍（模型不进仓库，默认跳过）：

```bash
./gradlew testDebugUnitTest \
  -DtestGlb=/path/to/model.glb \
  -DtestUsdc=/path/to/scene.usdc
```

断言内容：三角面数、包围盒、UV 数组长度、以及数据里没有 NaN。

## 使用

1. 打开 App，点「选择模型」，挑一个模型文件
2. 点状态栏里那行「悬浮窗权限：未授权」，去系统设置打开「显示在其他应用上层」
3. 回到 App 点「启动桌宠」——它会掉到屏幕底部
4. 长按桌宠可以打开菜单（参数设置 / 停止）

| 手势 | 作用 |
|---|---|
| 单指拖动 | 拎起来移动（按住点当支点，其余受重力自然垂下），松手按速度甩出去 |
| 双指拖动 | 横向转 Y 轴、纵向转 X 轴 |
| 长按 | 打开菜单（设置 / 停止 / 取消） |

通知栏里也有「设置」和「停止」两个快捷入口。

## 参数

设置界面顶部有 5 套**参数预设**，点一下换整套手感（只动重力/弹性/摩擦/甩出/阻尼，不会改模型大小）：

| 预设 | 手感 |
| --- | --- |
| 玩偶 | 默认，像一只软玩偶 |
| 气球 | 轻飘飘，甩出去慢慢荡 |
| 弹力球 | 落地来回蹦，甩出转得欢 |
| 铁块 | 掉得快、落地就停、不怎么转 |
| 失重 | 不往下掉，甩出去慢慢飘 |

也可以自己拧下面的滑条，随时微调：重力、弹性、摩擦、空气阻力、甩出力度、**甩出时翻滚**、**旋转阻尼**、**拖动时摆动阻尼**、模型大小、占屏高度、环境光、伽马、贴图翻转。

几个容易搞反的量，这里说清楚：

| 参数 | 含义 | 0 表示 |
| --- | --- | --- |
| 空气阻力 | 每秒的速度衰减系数，越大减速越快 | **完全无阻力**，甩出去会一直飞 |
| 旋转阻尼 | 每秒的角速度衰减系数，越大停得越快 | 一直转 |
| 拖动时摆动阻尼 | 拎在手上时摆动的衰减，越大停得越快 | 一直荡 |
| 甩出时翻滚 | 甩出时按速度给的翻滚强度 | 只平移不翻滚 |

以上都是按时间指数衰减，和帧率无关。

觉得甩出去转得太凶：把「甩出时翻滚」调小、把「旋转阻尼」调大（阻尼是每秒的指数衰减系数，越大停得越快）。

按住拖动时，模型是**绕你按住的那个点**受重力摆动的（杠杆）：抓在边缘它会垂下来，抓在正中心则不转。「拖动时摆动阻尼」越大停得越快，调到 0 会一直荡。

参数写进 `SharedPreferences`，渲染线程每帧读取，所以是**实时生效**的。

「贴图上下翻转」默认关闭（glTF 的 UV 约定就是这样）。如果某只模型的贴图倒了，打开它。

## 实现要点

| 问题 | 做法 |
| --- | --- |
| 悬浮窗挡住其他应用 | 窗口**只包住模型**并跟着它移动。全屏窗口会把所有触摸吃掉——Android 按窗口边界决定触摸归属，视图里 `return false` 并不能让给下层 |
| 悬浮窗透明 | 用 TextureView + 自建 EGL 线程（SurfaceView 在悬浮窗里做不出真透明） |
| 手机性能 | 碰撞用包围盒 8 个角点，而不是每帧变换全部顶点 |
| USDZ | 自己写了 USDC（USD Crate）二进制解析器，格式参考 Pixar OpenUSD 与 MIT 许可的 [@cinevva/usdjs](https://github.com/cinevva-engine/usdjs) |

## 代码结构

```
app/src/main/java/com/funnyarchbishop/fumofumo/
├── MainActivity.kt      权限引导、选模型、选贴图、启动/停止
├── SettingsActivity.kt  参数滑条
├── PetService.kt        前台服务 + 跟随模型的小悬浮窗 + 长按菜单
├── PetView.kt           TextureView + 手势
├── PetRenderer.kt       自建 EGL 线程 + 着色器 + 窗口跟随
├── Physics.kt           2D 刚体 + 3D 自转（移植自 WINobj.py）
├── ModelImporter.kt     按文件头分派到对应读取器
├── GltfLoader.kt        glb / gltf
├── ObjLoader.kt         obj（+ mtl 贴图）
├── UsdcLoader.kt        usdc（USD Crate 二进制）
├── ModelRepository.kt   模型与自定义贴图落地
└── PetPrefs.kt          参数持久化
```

## 已知限制

- **不支持 Draco 压缩的 glTF**，会明确报错。请下载未压缩版本。
- 一个模型只用一张贴图：优先 `baseColorTexture`，没有就用 `emissiveTexture`（Sketchfab 上的 Shadeless 扫描件会把颜色放在自发光通道）。
- 多材质模型统一用面数最多那块的贴图。
- 动画、骨骼、蒙皮不参与（桌宠只看静态网格）。
- USD 只支持单层场景，不支持外部引用其它 `.usd` 层的资产；`.usda` 文本格式暂不支持（请用 `.usdz` 或 `.glb`）。
- **iOS 做不了**：系统不允许跨 App 悬浮窗。

## 许可

MIT。模型素材请自行注意原作者的授权（Sketchfab 上的模型多为 CC-BY，记得署名）。

## 图标

应用图标是仓库根目录的 `Fumofumo_icon.png`（1254×1254），已按 Android 各密度生成：

```
app/src/main/res/mipmap-mdpi/ic_launcher.png      48×48
app/src/main/res/mipmap-hdpi/ic_launcher.png      72×72
app/src/main/res/mipmap-xhdpi/ic_launcher.png     96×96
app/src/main/res/mipmap-xxhdpi/ic_launcher.png   144×144
app/src/main/res/mipmap-xxxhdpi/ic_launcher.png  192×192
```

**Android 8+ 使用自适应图标**：`mipmap-anydpi-v26/ic_launcher.xml` 组合前景层（`ic_launcher_foreground.png`，108dp 画布上画面占 70%，避开系统圆形/方形遮罩）和纯色背景（`#1D1D1D`，取自图标主色）。老系统回退到上面的传统 PNG。

通知栏小图标仍然是 `drawable/ic_pet.xml`（单色矢量）—— Android 会把通知小图标渲染成白色剪影，用彩色图会变成一团白块。

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| **[Fumofumo_Mobile](https://github.com/FunnyArchbishop/Fumofumo_Mobile)** | ← 你在这里：Android 悬浮桌宠（Kotlin + OpenGL ES 3.0） |
| [Fumofumo_Windows](https://github.com/FunnyArchbishop/Fumofumo_Windows) | Windows 桌面版（Python + Qt + moderngl），本项目的起点，源自 [CaxyLZ/WINobj](https://github.com/CaxyLZ/WINobj) |
| [Fumofumo_Models](https://github.com/FunnyArchbishop/Fumofumo_Models) | 20 个 fumo 3D 扫描模型（CC BY 4.0，含完整署名），手机端可直接导入 |

