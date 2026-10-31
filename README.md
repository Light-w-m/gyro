# 三轴角度

安卓手机三轴姿态角度工具：纯黑背景、白色数字。打开后自动读取传感器，三轴上下平分屏幕；点任意一轴进入横屏大字显示，再点屏幕或使用系统返回键恢复三轴。

支持 Android 6.0（API 23）及以上，不需要网络、浏览器或账户登录。前台保持屏幕常亮，退到后台停止读取传感器。当前安装包版本为 1.0。

## 直接安装

本地交付文件夹的 `release/三轴角度-v1.0.apk` 是安装包，发送到安卓手机后打开安装。

详细操作见 [使用说明](docs/使用说明.md)。

## 文件结构

```text
三轴角度项目/
├── README.md                   项目入口
├── .gitignore                  Git 忽略规则
├── android/                    当前安卓应用源码
│   ├── AndroidManifest.xml     应用配置与版本号
│   ├── build.ps1               编译和签名脚本
│   ├── src/                    Java 源码
│   ├── res/                    黑色主题与图标
│   └── tests/                  角度换算测试
├── docs/
│   ├── 使用说明.md
│   ├── 构建说明.md
│   └── 发布说明-v1.0.md
├── release/                    本地安装包与校验值，不提交 Git
└── legacy-web/                 早期网页版本，保留参考
```

## 三轴含义

| 轴 | 含义 | 范围 |
| --- | --- | --- |
| X | 前后倾斜 | −180° 到 180° |
| Y | 左右倾斜 | −90° 到 90° |
| Z | 平面转向 | 0° 到 360° |

使用系统融合后的姿态角，优先读取 `TYPE_GAME_ROTATION_VECTOR`，缺失时尝试 `TYPE_ROTATION_VECTOR`。横屏只改变显示方向，三轴仍对应手机的物理轴。Z 轴不作为罗盘航向使用。欧拉角在特定姿态会出现表示跳变；未收到数据时显示 `—.—`。

## 开发与检查

无需 Gradle。构建需要 JDK、Android SDK Build Tools 35.0.0 和 Android API 35，操作见 [构建说明](docs/构建说明.md)。

本次交付已通过编译、APK 签名验证，以及 10,270 组旋转矩阵换算测试；尚未进行真实手机的传感器和显示实测。

## 以后上传 GitHub

源码、资源、测试、脚本和文档提交到仓库即可。`release/` 中的 APK 不需要随源码提交；本项目的 `.gitignore` 已将安装包、编译中间文件和签名私钥排除。

GitHub Release 是可选的版本发布功能。需要让别人方便下载 APK 时，再创建一个版本（例如 `v1.0`），将 APK 和校验文件作为附件上传。仅备份或分享代码时，可以不创建 Release。

[GitHub Releases 说明](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
