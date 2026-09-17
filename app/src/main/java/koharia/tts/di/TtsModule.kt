package koharia.tts.di

import android.app.Application
import koharia.data.tts.TtsProgressRepositoryImpl
import koharia.tts.TtsCache
import koharia.tts.TtsSecurePreferences
import koharia.tts.progress.TtsProgressNotifier
import koharia.tts.progress.TtsProgressRepository
import koharia.tts.reader.ChapterTextExtractor
import tachiyomi.data.Database
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * TTS 模块的 Injekt 依赖注入注册。
 *
 * 用法（在 App.kt 里加载）：
 * ```
 * Injekt.importModule(TtsModule(application = this))
 * ```
 *
 * Phase 1: 注入 MimoEngine + TtsCache + TtsEngine 接口 + ChapterTextExtractor
 * Phase 3: 注入 EdgeEngine 作为备用
 * Phase 4: 移除 [MimoEngine] / [TtsEngine] 单例注入 —— TtsService 现在用
 *   [koharia.tts.TtsVendor] + [TtsSecurePreferences] 在 `observeVendorPreference()`
 *   里动态构造。注入 [TtsSecurePreferences] 单例供引擎构造读取 API key。
 * Phase 4.1: 移除编译期 API key 注入 —— key 一律由用户在「设置 → TTS 引擎」
 *   配置,只存放于 [TtsSecurePreferences]（EncryptedSharedPreferences）,
 *   构建产物内不含任何 key。
 */
class TtsModule(
    /**
     * Application context(用于构造 [TtsSecurePreferences] 的 EncryptedSharedPreferences 文件)。
     */
    private val application: Application,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        // ===== 底层组件 =====

        addSingletonFactory { TtsCache(application.cacheDir.resolve("tts")) }

        // ChapterTextExtractor reads the live EpubReaderSession's publication
        // (remote or local) and strips tags via Jsoup; its constructor
        // default-resolves the session repository through Injekt, so no manual
        // wiring here.
        addSingletonFactory { ChapterTextExtractor() }

        // Phase 2: 句子级进度广播。TtsService 是进程内 Service，与阅读器共享同一
        // 单例；通过 Injekt 拿同一实例，避免 AIDL / Broadcast 的复杂度。
        addSingletonFactory { TtsProgressNotifier() }

        // Phase 2.5b: TTS 句子进度持久化（关闭 app 重开 → 从上次朗读到的句子继续）。
        // 单例；写失败不阻塞朗读主流程（实现内部 try/catch + logcat）。
        addSingletonFactory<TtsProgressRepository> { TtsProgressRepositoryImpl(get<Database>()) }

        // ===== Phase 4: 安全 prefs 注入 =====
        // TtsService 通过本单例读取/写入 API key / base URL;引擎构造走这里。
        // key 只存在于用户配置的加密存储中,构建期不再注入任何 key。
        addSingletonFactory { TtsSecurePreferences(application) }

        // ===== 引擎不再 Injekt 单例 =====
        // TtsService.observeVendorPreference() 根据当前 vendorId + securePrefs 动态构造:
        //   MimoEngine(apiKeyProvider = { securePrefs.getApiKey("mimo") }, ...)
        //   EdgeEngine()  // 无 key
        // 切换 vendor 时重建;key 变化不需要重建(provider lambda 每次重新读)。
    }
}
