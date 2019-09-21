/*
 * Akismet.kt
 *
 * Copyright (c) 2019, Erik C. Thauvin (erik@thauvin.net)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *   Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *   Neither the name of this project nor the names of its contributors may be
 *   used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.thauvin.erik.akismet

import net.thauvin.erik.semver.Version
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A small Kotlin/Java library for accessing the Akismet service.
 *
 * @constructor Create new instance using the provided [Akismet](https://www.askimet.com/) API key.
 */
@Version(properties = "version.properties", type = "kt")
open class Akismet(apiKey: String) {
    private val apiEndPoint = "https://%srest.akismet.com/1.1/%s"
    private val libUserAgent = "${GeneratedVersion.PROJECT}/${GeneratedVersion.VERSION}"
    private val verifyMethod = "verify-key"
    private var apiKey: String
    private var client: OkHttpClient

    /**
     * The URL registered with Akismet.
     */
    var blog = ""
        set(value) {
            require(!value.isBlank()) { "A Blog URL must be specified." }
            field = value
        }

    /**
     * Check if the API Key has been verified.
     */
    var isVerifiedKey: Boolean = false
        private set

    /**
     * The HTTP status code of the last operation.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var httpStatusCode: Int = 0
        private set

    /**
     * The actual response sent by Aksimet from the last operation.
     *
     * For example: ```true```, ```false```, ```valid```, ```invalid```, etc.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var response: String = ""
        private set

    /**
     * The X-akismet-pro-tip header from the last operation, if any.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var proTip: String = ""
        private set

    /**
     * Set to true if Akismet has determined that the last [checked comment][checkComment] is blatant spam, and you
     * can safely discard it without saving it in any spam queue. Read more about this feature in this
     * [Akismet blog post](https://blog.akismet.com/2014/04/23/theres-a-ninja-in-your-akismet/).
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var isDiscard: Boolean = false
        private set

    /**
     * The X-akismet-error header from the last operation, if any.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var error: String = ""
        private set

    /**
     * The X-akismet-debug-help header from the last operation, if any.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var debugHelp: String = ""
        private set

    /**
     * The logger instance.
     */
    val logger: Logger by lazy { Logger.getLogger(Akismet::class.java.simpleName) }

    init {
        require(!apiKey.isBlank() || apiKey.length != 12) { "An Akismet API key must be specified." }

        this.apiKey = apiKey

        val logging = HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, message.replace(apiKey, "xxxxxxxx" + apiKey.substring(8), true))
                }
            }
        })
        logging.level = HttpLoggingInterceptor.Level.BODY
        client = OkHttpClient.Builder().addInterceptor(logging).build()
    }

    /**
     * Create a new instance using an [Akismet](https://www.askimet.com/) API key and blog URL registered with Akismet.
     */
    constructor(apiKey: String, blog: String) : this(apiKey) {
        this.blog = blog
    }

    /**
     * Key Verification.
     * See the [Akismet API](https://akismet.com/development/api/#verify-key) for more details.
     */
    fun verifyKey(): Boolean {
        val body = FormBody.Builder().apply {
            add("key", apiKey)
            add("blog", blog)
        }.build()
        isVerifiedKey = executeMethod(buildApiUrl(verifyMethod), body)
        return isVerifiedKey
    }

    /**
     * Comment Check. See the [Akismet API](https://akismet.com/development/api/#comment-check) for more details.
     */
    fun checkComment(comment: AkismetComment): Boolean {
        return executeMethod(buildApiUrl("comment-check"), buildFormBody(comment))
    }

    /**
     * Submit Spam (missed spam).
     * See the [Akismet API](https://akismet.com/development/api/#submit-spam) for more details.
     */
    fun submitSpam(comment: AkismetComment): Boolean {
        return executeMethod(buildApiUrl("submit-spam"), buildFormBody(comment))
    }

    /**
     * Submit Ham.
     * See the [Akismet API](https://akismet.com/development/api/#submit-ham) for more details.
     */
    fun submitHam(comment: AkismetComment): Boolean {
        return executeMethod(buildApiUrl("submit-ham"), buildFormBody(comment))
    }

    /**
     * Convert a date to a UTC timestamp.
     */
    fun dateToGmt(date: Date): String {
        return DateTimeFormatter.ISO_DATE_TIME.format(
            OffsetDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS))
    }

    /**
     * Convert a locale date/time to a UTC timestamp.
     */
    fun dateToGmt(date: LocalDateTime): String {
        return DateTimeFormatter.ISO_DATE_TIME.format(
            date.atOffset(OffsetDateTime.now().offset).truncatedTo(ChronoUnit.SECONDS))
    }

    /**
     *
     * Execute an Akismet REST API method.
     *
     * @param apiUrl The Akismet API URL endpoint. (e.g. https://rest.akismet.com/1.1/verify-key)
     * @param formBody The HTTP POST form body containing the request parameters to be submitted.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected fun executeMethod(apiUrl: HttpUrl?, formBody: FormBody): Boolean {
        if (apiUrl != null) {
            val request = Request.Builder().url(apiUrl).post(formBody).header("User-Agent", libUserAgent).build()
            try {
                val result = client.newCall(request).execute()
                httpStatusCode = result.code
                proTip = result.header("x-akismet-pro-tip", "").toString()
                isDiscard = proTip.equals("discard", true)
                error = result.header("x-akismet-error", "").toString()
                debugHelp = result.header("x-akismet-debug-help", "").toString()
                val body = result.body?.string()
                if (body != null) {
                    response = body.trim()
                    if (response.equals("valid", true) ||
                        response.equals("true", true) ||
                        response.startsWith("Thanks", true)) {
                        return true
                    }
                }
            } catch (e: IOException) {
                logger.log(Level.SEVERE, "An IO error occurred while communicating with the Akismet service.", e)
            }
        } else {
            logger.severe("Invalid API end point URL. The API Key is likely invalid.")
        }
        return false
    }

    private fun buildApiUrl(method: String): HttpUrl? {
        if (method == verifyMethod) {
            return String.format(apiEndPoint, "", method).toHttpUrlOrNull()
        }
        return String.format(apiEndPoint, "$apiKey.", method).toHttpUrlOrNull()
    }

    private fun buildFormBody(comment: AkismetComment): FormBody {
        require(!(comment.userIp.isBlank() && comment.userAgent.isBlank())) { "userIp and/or userAgent are required." }
        return FormBody.Builder().apply {
            add("blog", blog)
            add("user_ip", comment.userIp)
            add("user_agent", comment.userAgent)

            if (comment.referrer.isNotBlank()) {
                add("referrer", comment.referrer)
            }
            if (comment.permalink.isNotBlank()) {
                add("permalink", comment.permalink)
            }
            if (comment.type.isNotBlank()) {
                add("comment_type", comment.type)
            }
            if (comment.author.isNotBlank()) {
                add("comment_author", comment.author)
            }
            if (comment.authorEmail.isNotBlank()) {
                add("comment_author_email", comment.authorEmail)
            }
            if (comment.authorUrl.isNotBlank()) {
                add("comment_author_url", comment.authorUrl)
            }
            if (comment.content.isNotBlank()) {
                add("comment_content", comment.content)
            }
            if (comment.dateGmt.isNotBlank()) {
                add("comment_date_gmt", comment.dateGmt)
            }
            if (comment.postModifiedGmt.isNotBlank()) {
                add("comment_post_modified_gmt", comment.postModifiedGmt)
            }
            if (comment.blogLang.isNotBlank()) {
                add("blog_lang", comment.blogLang)
            }
            if (comment.blogCharset.isNotBlank()) {
                add("blog_charset", comment.blogCharset)
            }
            if (comment.userRole.isNotBlank()) {
                add("user_role", comment.userRole)
            }
            if (comment.isTest) {
                add("is_test", "1")
            }
            if (comment.recheckReason.isNotBlank()) {
                add("recheck_reason", comment.recheckReason)
            }

            comment.other.forEach { (k, v) -> add(k, v) }
        }.build()
    }
}
