# Rokid 本机 3DoF 文字维稳 Demo

适用于所提供 `GlassesBareDevSample.zip` 对应的 Rokid 开发机：Android 12+、480×640、黑底单绿色显示，安全区 Y=80..560。应用直接运行在眼镜上，不依赖手机、网络或摄像头。

## 使用

1. 安装 `outputs/rokid-3dof-demo-debug.apk`：

   ```powershell
   adb install -r outputs/rokid-3dof-demo-debug.apk
   adb shell am start -n com.rokid.stabledemo/.MainActivity
   ```

2. 启动只有一个「开始」按钮。点击按钮、触摸板单击或镜腿单击后，保持头部静止片刻；有效姿态就绪后自动回正并开始打字。
3. 缓慢左右转头、抬头低头、左右歪头，观察文字面板是否留在初始方向。
4. 单击切换「维稳开／关」，文字与播放进度继续，方便比较；长按触摸板回正；双击触摸板或返回键回到开始页。

原始 IMU 备用路径会显示约三秒的初始化提示。无可用传感器时显示错误，单击重试。数据断流时不把静止文字假装成维稳，等待切换数据源或长按重新连接。应用进入后台时停止传感器，回到前台自动重新初始化与回正，重新开始循环。

## 实现方式与开源依据

- 保留参考 Demo 的画布、安全区、按键和广播事件定义，重新实现聚焦于文字维稳的单页应用；没有导入拍照、录像、录音等功能。
- 优先使用 Android `TYPE_GAME_ROTATION_VECTOR`，其次 `TYPE_ROTATION_VECTOR`。后一种可能受磁场影响。传感器注册失败、首次数据超时或持续断流时自动尝试下一数据源。
- 无融合姿态时，将眼镜本机陀螺仪与加速度计接入 [x-io Fusion](https://github.com/xioTechnologies/Fusion)，采用 MIT 开源的无磁力计 AHRS 与静止零偏估计。C 源码随仓库固定在 commit `a8d7224f36a0ec82345ef49a3db50e65f8d3bab8`，通过 JNI 运行，包含 arm64-v8a、armeabi-v7a、x86_64。
- 在独立线程读取传感器采集时间戳，绘制时取最新姿态。用四元数逆旋转和透视单应变换补偿整块文字平面，包含偏航、俯仰与横滚；不将欧拉角简单换成像素位移。内容离开视场时自然裁切。
- 按 VSync 由硬件 Canvas 渲染。文字按完整文本预排版，以单调时钟每 90 ms 增加一个 Unicode 字符，全部显示后停留 2400 ms，持续循环。切换模式与回正不重置播放。
- 未添加姿态预测或厂商重投影接口。显示端仍有传感器、绘制、合成与扫描输出延迟，需要真机观察后再决定预测参数。

没有加入研究文档中的 SLAM/VIO/Gyroflow：此版本的输入是本机 IMU，目标是虚拟文本方向稳定。

## 真机必须确认的两个参数

**轴向：** Demo 的默认动作绑定为 pitch=X、yaw=Z、roll=Y，但其注释对 Android 设备轴语义有冲突。本应用默认按 Demo 绑定，用保持右手性的基变换 `(x,y,z) → (x,z,-y)`。如果固件已经按 Android 屏幕坐标输出（右 X、上 Y、朝向佩戴者 Z），使用 `axes=android`。

**视场角：** 资料没有光学内参或真实 FOV。默认水平 30° 是待校准的工程初值，不能当作设备规格。参数指 480×480 安全显示区对应的水平视角；假设方形像素和安全区中心为主点。真实设备若存在光学畸变、非对称投影或显示区映射差异，需替换为测量后的投影参数。

可通过启动参数调整，不增加启动页按钮：

```powershell
# 参数仅作用于这次启动；先停止已有进程再设置。
adb shell am force-stop com.rokid.stabledemo
adb shell am start -n com.rokid.stabledemo/.MainActivity --es axes android --ef horizontal_fov_deg 30

# 强制走 Fusion 原始六轴，用于比较系统融合与开源算法。
adb shell am force-stop com.rokid.stabledemo
adb shell am start -n com.rokid.stabledemo/.MainActivity --ez force_fusion true
```

`axes` 支持 `demo`（默认）或 `android`；FOV 范围为 5°..120°，非法值回退 30°。需要永久修改时调整 `MainActivity` 的默认配置并重新构建。

## 构建与测试

需要 JDK 17+（本机验证为 Android Studio JBR 25）、Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1`。Gradle wrapper 为 9.5.0，AGP 为 9.3.0。首次构建需要下载 AndroidX 和 JUnit。

```powershell
./tools/build.ps1

# 指定本机路径
./tools/build.ps1 -AndroidSdk 'C:/Users/yourname/AppData/Local/Android/Sdk' -JavaHome 'C:/Program Files/Android/Android Studio/jbr'

# 对明确指定的测试设备额外安装和运行仪器测试
./tools/build.ps1 -DeviceSerial emulator-5580
```

脚本执行单元测试、Lint、APK 构建、签名验证，并将 APK、SHA-256 和检查记录写到 `outputs/`。原始 Gradle 产物在 `app/build/outputs/apk/debug/`。输出目录不提交到 Git，源代码与固定版本的开源依赖提交到仓库。

## 真机检查流程

| 动作 | 应观察到的行为 |
| --- | --- |
| 正视后点击开始 | 自动回正，绿色中文逐字出现并循环 |
| 小幅左右转头 | 维稳开启时文字尽量保持原方向；关闭时随眼镜屏幕转动 |
| 抬头、低头 | 文字按相反方向补偿，幅度应与真实视场匹配 |
| 左右歪头 | 文字执行横滚反向旋转 |
| 单击比较 | 播放不重启，状态条显示开／关 |
| 长按 | 文字回到当前正前方 |
| 大幅转头 | 文字自然离开有限视场，不能贴在屏幕边缘 |
| 双击/返回 | 返回唯一开始按钮，停止传感器 |
| 切后台再回来 | 重新初始化与回正，避免跨暂停积分 |
| 静止一至五分钟 | 观察静止抖动与航向漂移，记录数据源、采样率与回正次数 |

屏幕上的 Hz 是姿态样本到达频率的估计，**不是端到端延迟或维稳精度**。模拟器验证能够检查代码与交互，不能确认眼镜的光学效果、功耗、真实漂移和运动到显示延迟。IMU 3DoF 不补偿走动产生的位置变化；无可靠航向参考时仍会产生漂移。

开源许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
