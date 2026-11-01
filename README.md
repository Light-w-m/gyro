# 三轴角度

安卓手机三轴姿态角度工具：纯黑背景、白色数字。打开后自动读取传感器，三轴上下平分屏幕；点任意一轴进入横屏大字显示，再点屏幕或使用系统返回键恢复三轴。

支持 Android 6.0（API 23）及以上，不需要网络、浏览器或账户登录。前台保持屏幕常亮，退到后台停止读取传感器。当前安装包版本为 1.0。

## 直接安装

安装包发送到安卓手机后打开安装。

详细操作见 [使用说明](docs/使用说明.md)。

## 三轴含义

| 轴 | 含义 | 范围 |
| --- | --- | --- |
| X | 前后倾斜 | −180° 到 180° |
| Y | 左右倾斜 | −90° 到 90° |
| Z | 平面转向 | 0° 到 360° |

使用系统融合后的姿态角，优先读取 `TYPE_GAME_ROTATION_VECTOR`，缺失时尝试 `TYPE_ROTATION_VECTOR`。横屏只改变显示方向，三轴仍对应手机的物理轴。Z 轴不作为罗盘航向使用。欧拉角在特定姿态会出现表示跳变；未收到数据时显示 `—.—`。

## 开发与检查

无需 Gradle。构建需要 JDK、Android SDK Build Tools 35.0.0 和 Android API 35，操作见 [构建说明](docs/构建说明.md)。

[GitHub Releases 说明](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
