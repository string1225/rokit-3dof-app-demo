# 周期预测实现计划

用户已授权本轮直接实现。在现有分支内顺序完成，不另建线程或派生代理。

- [x] 在`DemoInstrumentedTest`加入首页预测设置测试，先在旧版确认缺少入口。
- [x] 新增`core/PitchPredictor.java`及测试：时间窗口、频率拟合、稳定性/突变门控、不可变状态、限幅短时预测；用同一未来时刻的姿态误差验证收益。
- [x] `TrackingController`在真实姿态发布时更新预测器，并随Snapshot发布不可变状态；初始化/源切换清空模型。
- [x] `DemoView`在实测跟随之后使用预测头姿投影，显示预测状态；`MainActivity`提供第三项设置及启动参数。
- [x] `MotionDiagnostics`在同一预计呈现时刻计算普通与当前残差；`MotionChart`及`MotionLog`展示/导出对比与预测元数据，保留旧CSV列顺序。
- [x] 运行核心测试、Lint、构建、相关仪器测试和截图检查，确认耗电/媒体流程未受影响。
- [x] 更新README与验证记录，校验签名及文件，复制新版APK到手机Download，提交并推送当前分支。
