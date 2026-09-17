# AMLL 第一阶段接入

第一阶段将 AMLL TTML 的主歌词、逐字时间和行内翻译映射到现有 `LyricLine` / `WordInfo`，复用原有歌词界面与动画。

## 数据流程

`PlaybackRepositoryImpl.getLyrics` → `LyricResolver` → 并发请求 AMLL 和网易云 → 返回一次歌词结果。

- `AmllLyricsClient` 创建独立 OkHttpClient，禁用 Cookie，不使用网易云客户端和拦截器。主镜像、作者镜像、GitHub 直链依次尝试；404、网络错误、空内容、解析失败会尝试下一个源。
- `TtmlLyricParser` 是可在 JVM 测试的解析器。支持命名空间、XML 实体、行时间、绝对词时间及 `x-translation`。拒绝 DOCTYPE、实体声明和外部实体，限制内容大小及遍历深度。
- 无有效词时间的 TTML 降级为逐行显示，保留文本；无有效行时间的行被跳过。重复行时间稳定排序。
- 质量优先级：有效 AMLL 逐字 → 有效网易云 YRC → AMLL 逐行 → 网易云 LRC → 空歌词。保留网易云纯音乐标识及翻译匹配。
- 初次选择窗口为 2500 ms，窗口结束时已有可用歌词就立即采用并取消另一来源，之后不热切换。真机在移动网络或 VPN 下的 TLS 与 TTML 解析可能超过 1.8 秒，因此放宽窗口以确保 AMLL 能参与择优。若两方均无可用结果，继续等待受限请求；AMLL 总超时 4 秒，网易云 5 秒。优先级只作用于选择时已获得的候选。
- 切歌通过原有 `collectLatest` 取消解析任务树，同时取消底层 AMLL HTTP Call，避免旧歌词写回。

## 阶段边界

音译、背景人声展示、对唱对齐、多行同时激活属于第二阶段。第一阶段忽略 `x-roman` / `x-bg` 注释，避免其混入主歌词；不同歌手的主歌词仍作为普通行显示。缓存、预加载、开关与来源展示属于第三阶段。

第二阶段现已完成，当前实现与验证结果见 `docs/AMLL-phase2.md`。

## 验证

运行 `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`。

2026-09-13 本地与真机验证：上述构建通过，169 项 JVM 单元测试全部成功；在 Android 15（API 35）设备上完成 2 项仪器测试。真实请求歌曲 `36990266` 得到 54 行 AMLL 逐字歌词和 54 行翻译，播放器日志确认 `source=AMLL format=WORD`。快速连续切歌未发生崩溃或旧歌词写回。生成 `app/build/outputs/apk/debug/Melodia-v1.0.0-debug.apk`，并已通过 ADB 安装到测试设备。

新增测试覆盖 TTML 时间转换、实体和命名空间、翻译、异常时间、零时长词、重复时间、损坏 XML、外部实体拒绝、择优、失败回退、选择窗口、任务取消、镜像备用路径、404/500/空响应/超大响应/超时及 Cookie 隔离。原有歌词解析测试保留。

真机检查已完成：AMLL 逐字歌词、翻译、原有高亮动画与滚动均能显示；未收录歌词可回退网易云；连续切歌后应用稳定。验证截图位于 `docs/validation/device-player.png`。

## 来源

歌词来自 [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db)，请求地址参考上游 README 的镜像源及接入说明。上游自主制作部分采用 CC0，外来数据遵循原提供方协议。
