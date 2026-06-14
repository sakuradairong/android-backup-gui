package com.example.androidbackupgui.backup.core

/**
 * 类型化应用错误层次。所有业务层错误统一为此 sealed interface。
 *
 * 使用方式：
 * ```
 * // 失败返回
 * return err(AppError.Remote("连接超时", "download", cause = e, retryable = true))
 *
 * // 模式匹配
 * when (error) {
 *     is AppError.Network -> showRetry()
 *     is AppError.Remote -> handleRemote(error)
 *     is AppError.Cancelled -> ignore()
 *     else -> showError(error.message)
 * }
 * ```
 */
sealed interface AppError {

    /** 人类可读的错误描述 */
    val message: String

    /** 错误解决建议 */
    val suggestion: String?

    /**
     * 网络/IO 类错误。
     * 用于 HTTP 请求超时、DNS 解析失败、连接被拒绝等可重试的网络异常。
     *
     * @property retryable 默认为 true，表示此错误可安全重试
     */
    data class Network(
        override val message: String,
        val cause: Throwable? = null,
        val retryable: Boolean = true,
        override val suggestion: String? = null
    ) : AppError

    /**
     * Root shell 命令执行错误。
     * 用于 cp、tar、pm path、dumpsys 等 root 命令的非零退出。
     */
    data class Shell(
        override val message: String,
        val command: String,
        val exitCode: Int,
        val stderr: String,
        override val suggestion: String? = null
    ) : AppError

    /**
     * 远端文件操作错误（WebDAV/SMB）。
     * 用于上传、下载、列出、删除远端文件时的协议层错误。
     *
     * @property phase 错误发生时所在的阶段，可取 "connecting"、"transferring"、"list"、"delete" 等
     * @property isNotFound 远端路径是否存在（区分 404 和其他错误）
     * @property retryable 默认为 false，明确标记为可重试需业务层判断
     */
    data class Remote(
        override val message: String,
        val phase: String,
        val cause: Throwable? = null,
        val isNotFound: Boolean = false,
        val retryable: Boolean = false,
        override val suggestion: String? = null
    ) : AppError

    /**
     * 本地文件/IO 错误。
     * 用于文件读写失败、磁盘空间不足、文件不存在等本地文件系统错误。
     */
    data class LocalIO(
        override val message: String,
        val path: String,
        val cause: Throwable? = null,
        override val suggestion: String? = null
    ) : AppError

    /**
     * restic 命令执行错误。
     * 用于 restic backup / restore / snapshots / forget 等子命令返回非零退出码。
     */
    data class Restic(
        override val message: String,
        val exitCode: Int,
        val stderr: String,
        override val suggestion: String? = null
    ) : AppError

    /**
     * 解析/配置错误。
     * 用于 JSON 解析失败、配置文件格式错误、参数校验失败等场景。
     */
    data class Parse(
        override val message: String,
        val detail: String = "",
        override val suggestion: String? = null
    ) : AppError

    /** 操作被取消（用户中止或协程取消）。不应重试。 */
    data object Cancelled : AppError {
        override val message: String = "操作被取消"
        override val suggestion: String? = null
    }
}

/**
 * 与 [AppError] 配套的类型化返回类型。
 *
 * 使用方式：
 * ```
 * fun load(): AppResult<List<Item>> {
 *     return AppResult.Success(items)
 *     // 或
 *     return err(AppError.Network("连接失败"))
 * }
 *
 * // 消费
 * when (val result = load()) {
 *     is AppResult.Success -> showItems(result.data)
 *     is AppResult.Failure -> showError(result.error.message)
 * }
 *
 * // 或使用 fold / map
 * result.fold(
 *     onSuccess = { items -> showItems(items) },
 *     onFailure = { error -> showError(error.message) }
 * )
 * ```
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    /** Returns `true` if this is a [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** Returns `true` if this is a [Failure]. */
    val isFailure: Boolean get() = this is Failure

    /** Returns the success value, or `null` if this is a [Failure]. */
    fun getOrNull(): T? = (this as? Success)?.data

    /** Returns the success value, or [default] if this is a [Failure]. */
    fun getOrDefault(default: @UnsafeVariance T): T =
        (this as? Success)?.data ?: default

    /**
     * Returns the success value, or throws a [RuntimeException]
     * wrapping the error message if this is a [Failure].
     */
    fun getOrThrow(): T =
        (this as? Success)?.data
            ?: throw RuntimeException((this as Failure).error.message)

    /**
     * Returns a [RuntimeException] representing the error, or `null` if this is a [Success].
     * Callers can access `.message` on the result.
     */
    fun exceptionOrNull(): Throwable? =
        (this as? Failure)?.let { RuntimeException(it.error.message) }

    /** Returns the [AppError], or `null` if this is a [Success]. */
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    /**
     * Fold: convert either branch into a single value [R].
     * [onSuccess] receives the success value; [onFailure] receives the typed [AppError].
     */
    inline fun <R> fold(
        crossinline onSuccess: (T) -> R,
        crossinline onFailure: (AppError) -> R,
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }

    inline fun <R> map(crossinline transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }


    /**
     * Transform the error using [transform], or pass through the success unchanged.
     */
    fun mapError(transform: (AppError) -> AppError): AppResult<T> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(error))
    }
}

/**
 * Create a failed [AppResult] wrapping the given [AppError].
 */
internal fun <T> err(error: AppError): AppResult<T> = AppResult.Failure(error)
