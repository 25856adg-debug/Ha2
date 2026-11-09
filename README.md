# ScreenWake5 v2
新增权限引导：悬浮窗、通知、电池优化设置入口；并引导用户自行检查 ColorOS 自启动/后台弹出。
打开应用后逐项设置；点击“立即测试”启动图案页面，显示倒计时 5 分钟。

注意：
- OPPO 自启动和后台弹出是厂商设置，没有通用 Android 授权弹窗，应用不能静默开启。
- 悬浮窗权限不等于后台 Activity 启动许可；系统仍可能阻止开机后弹出页面。
- 屏幕唤醒锁 API 已过时，系统可能忽略。
- 不会绕过 PIN、图案或密码锁。
构建：GitHub Actions -> Android CI -> 下载 ScreenWake5-APK artifact。
